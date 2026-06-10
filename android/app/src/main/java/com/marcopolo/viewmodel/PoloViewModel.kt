// SPDX-FileCopyrightText: 2026 Marco Polo Authors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.marcopolo.viewmodel

import android.app.Application
import android.content.Intent
import android.hardware.SensorManager
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marcopolo.network.RelayClient
import com.marcopolo.network.RouteFinder
import com.marcopolo.network.RouteResult
import com.marcopolo.network.RouteStep
import com.marcopolo.service.LocationService
import com.marcopolo.util.countdownFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PoloUiState(
    val roomCode: String? = null,
    val isActive: Boolean = false,
    val ownLat: Double? = null,
    val ownLng: Double? = null,
    val ownBearing: Float? = null,
    val partnerLat: Double? = null,
    val partnerLng: Double? = null,
    val remainingSeconds: Int = 15 * 60,
    val error: String? = null,
    val permissionsReady: Boolean = false,
    val locationReady: Boolean = false,
    // Distance & reveal
    val partnerDistance: Double? = null,
    val partnerRevealed: Boolean = false,
    val hasPartnerLocation: Boolean = false,
    // Disconnect dialog
    val showDisconnectDialog: Boolean = false,
    // Found dialog
    val showFoundDialog: Boolean = false,
    // Minimum system time (ms) before found dialog can fire.
    // Gives GPS positions ~20s to settle after session activation.
    val foundDialogEnabledAtMs: Long = 0L,
    // Walking route received from Marco (or calculated locally)
    val walkRoute: RouteResult? = null,
    // Pending route cached while partner not yet revealed
    val pendingWalkRoute: RouteResult? = null,
    // Raw partner coords (always set on receive, even before reveal)
    val rawPartnerLat: Double? = null,
    val rawPartnerLng: Double? = null,
    // Green checkmark (manual found when ≤30m)
    val showCheckmark: Boolean = false,
    // Compass accuracy from rotation-vector sensor (SENSOR_STATUS_*)
    val compassAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
    // GPS horizontal accuracy in meters (from location.accuracy)
    val gpsAccuracy: Float? = null,
    // Debug counters
    val sentCount: Int = 0
)

/** Subset of UI state that drives the map rendering. Split from [PoloUiState]
 * to avoid map recomposition on non-map state changes (e.g., countdown ticks, error messages). */
data class PoloMapState(
    val ownLat: Double? = null,
    val ownLng: Double? = null,
    val ownBearing: Float? = null,
    val partnerLat: Double? = null,
    val partnerLng: Double? = null,
    val routeLatLngs: List<List<Double>>? = null,
    val routeSteps: List<RouteStep> = emptyList(),
    val distanceToTarget: Double? = null,
    val routeDistance: Double? = null,
    val showCheckmark: Boolean = false,
    val compassAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
    val gpsAccuracy: Float? = null,
    val isActive: Boolean = false,
    val hasPartnerLocation: Boolean = false
)

class PoloViewModel(application: Application) : AndroidViewModel(application) {

    private val relayClient = RelayClient()
    private val _uiState = MutableStateFlow(PoloUiState())
    val uiState: StateFlow<PoloUiState> = _uiState.asStateFlow()

    /** Derive map-relevant state to avoid recomposition on UI-only changes */
    val mapState: StateFlow<PoloMapState> = _uiState.map { ui ->
        PoloMapState(
            ownLat = ui.ownLat,
            ownLng = ui.ownLng,
            ownBearing = ui.ownBearing,
            partnerLat = ui.partnerLat,
            partnerLng = ui.partnerLng,
            routeLatLngs = ui.walkRoute?.geometry,
            routeSteps = ui.walkRoute?.steps ?: emptyList(),
            distanceToTarget = ui.partnerDistance,
            routeDistance = ui.walkRoute?.distance,
            showCheckmark = ui.showCheckmark,
            compassAccuracy = ui.compassAccuracy,
            gpsAccuracy = ui.gpsAccuracy,
            isActive = ui.isActive,
            hasPartnerLocation = ui.hasPartnerLocation
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PoloMapState())

    private var timerJob: Job? = null
    private var locationJob: Job? = null
    private var routeJob: Job? = null

    // Route debounce tracking
    private var lastRouteCalcMs: Long = 0L
    private var lastRouteOwnLat: Double? = null
    private var lastRouteOwnLng: Double? = null
    private var lastRoutePartnerLat: Double? = null
    private var lastRoutePartnerLng: Double? = null

    /** Positions used for the currently DISPLAYED route. */
    private var displayedRouteOwnLat: Double? = null
    private var displayedRouteOwnLng: Double? = null
    private var displayedRoutePartnerLat: Double? = null
    private var displayedRoutePartnerLng: Double? = null

    private val prefs = getApplication<Application>().getSharedPreferences("marco_polo", 0)
    private val _useFootpath = MutableStateFlow(prefs.getBoolean("routing_footpath", true))
    val useFootpath: StateFlow<Boolean> = _useFootpath.asStateFlow()

    /** Ensures navigation_started fires only once per session */
    private var navigationStartedFired: Boolean = false

    fun setRoutingMode(footpath: Boolean) {
        _useFootpath.value = footpath
        prefs.edit().putBoolean("routing_footpath", footpath).apply()
        requestRouteUpdate(force = true)
    }

    companion object {
        private const val TAG = "MarcoPolo.Polo"
        private const val ROUTE_MIN_INTERVAL_MS = 10_000L   // Don't recalculate more than every 10s
        private const val ROUTE_MOVEMENT_THRESHOLD_M = 30f   // Recalculate only if moved > 30m
        private const val REVEAL_THRESHOLD_M = 10
        private const val FOUND_THRESHOLD_M = 15
        private const val CHECKMARK_THRESHOLD_M = 30

        /** Conditional debug log — avoids string allocation when not loggable */
        private inline fun logD(crossinline message: () -> String) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message())
        }
    }

    /**
     * Join a room by code. Connect WebSocket to relay.
     */
    fun joinRoom(code: String) {
        if (_uiState.value.roomCode != null) return
        _uiState.update { it.copy(roomCode = code) }

        relayClient.connect(code)
        listenForMessages()
    }

    /**
     * Called after location permissions are granted.
     * Starts GPS collection immediately (pre-loads location data before partner joins).
     */
    fun onPermissionsGranted() {
        if (_uiState.value.permissionsReady) return
        _uiState.update { it.copy(permissionsReady = true) }

        // Start GPS collection immediately (preload location data ahead of partner join)
        onLocationReady()
    }

    /**
     * Called when LocationService has a fix and location is ready.
     * Observes real GPS from LocationService and sends over WebSocket.
     */
    fun onLocationReady() {
        if (_uiState.value.locationReady) return
        _uiState.update { it.copy(locationReady = true) }

        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            var lastLat: Double? = null
            var lastLng: Double? = null
            combine(
                LocationService.currentLocation,
                LocationService.compassHeading,
                LocationService.compassAccuracy
            ) { location, heading, compAccuracy ->
                // Prefer compass heading over GPS movement bearing
                val bearing = heading ?: (if (location?.hasBearing() == true) location.bearing else null)
                Triple(location, bearing, compAccuracy)
            }.collect { (location, bearing, compAccuracy) ->
                // Always update bearing and compass accuracy (updates even when stationary)
                _uiState.update { it.copy(ownBearing = bearing, compassAccuracy = compAccuracy) }
                // Only send location / update route when coordinates actually change
                if (location != null) {
                    val lat = location.latitude
                    val lng = location.longitude
                    if (lat != lastLat || lng != lastLng) {
                        lastLat = lat
                        lastLng = lng
                        logD { "own location: $lat, $lng  accuracy=${location.accuracy}" }
                        _uiState.update { it.copy(ownLat = lat, ownLng = lng, gpsAccuracy = location.accuracy, sentCount = it.sentCount + 1) }
                        relayClient.sendLocation(lat, lng, location.accuracy)
                        logD { "sendLocation called (sent=${_uiState.value.sentCount}) bearing=$bearing" }
                        // Calculate route locally using own position + raw partner coords
                        requestRouteUpdate()

                        // Re-evaluate partner reveal: partner location may have arrived
                        // via WS before Polo's GPS fixed, leaving partnerLat = null
                        // even though partner is >10m away. Now that own GPS is set,
                        // recalculate distance and reveal if applicable.
                        val s = _uiState.value
                        val rLat = s.rawPartnerLat
                        val rLng = s.rawPartnerLng
                        if (rLat != null && rLng != null && !s.partnerRevealed) {
                            val d = distanceBetween(lat, lng, rLat, rLng).toDouble()
                            val revealed = d > REVEAL_THRESHOLD_M
                            if (revealed) {
                                val timeOk = System.currentTimeMillis() >= s.foundDialogEnabledAtMs
                                val nowFound = d <= FOUND_THRESHOLD_M && timeOk
                                val checkmarkVisible = d <= CHECKMARK_THRESHOLD_M && !s.showFoundDialog && !nowFound
                                _uiState.update {
                                    it.copy(
                                        partnerDistance = d,
                                        partnerLat = rLat,
                                        partnerLng = rLng,
                                        partnerRevealed = true,
                                        showCheckmark = checkmarkVisible,
                                        showFoundDialog = s.showFoundDialog || nowFound,
                                        showDisconnectDialog = if (s.showFoundDialog || nowFound) false else s.showDisconnectDialog,
                                        error = if (s.showFoundDialog || nowFound) null else s.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Straight-line distance in meters between two coordinates */
    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0]
    }

    private fun listenForMessages() {
        viewModelScope.launch {
            relayClient.messages.collect { msg ->
                when (msg.type) {
                    "partner_joined" -> {
                        _uiState.update {
                            it.copy(
                                isActive = true,
                                foundDialogEnabledAtMs = System.currentTimeMillis() + 20_000
                            )
                        }
                        startCountdown()
                        onLocationReady()
                    }
                    "partner_disconnected" -> {
                        _uiState.update { current ->
                            if (current.showFoundDialog) {
                                // Already found — don't change anything
                                current
                            } else {
                                // Partner was within found threshold when they left —
                                // treat as found to prevent race where one side
                                // navigates home before the other processes found.
                                val dist = current.partnerDistance
                                val timeOk = System.currentTimeMillis() >= current.foundDialogEnabledAtMs
                                if (dist != null && dist <= FOUND_THRESHOLD_M && timeOk) {
                                    current.copy(showFoundDialog = true)
                                } else {
                                    current.copy(
                                        isActive = false,
                                        error = "marco_disconnected",
                                        showDisconnectDialog = true
                                    )
                                }
                            }
                        }
                        // Don't clean up — let user dismiss the dialog first
                    }
                    "location" -> {
                        msg.lat?.let { lat ->
                            msg.lng?.let { lng ->
                                val state = _uiState.value
                                val ownLat = state.ownLat
                                val ownLng = state.ownLng

                                logD { "rcvd partner location: $lat,$lng  from=${msg.from}  ownGPS=$ownLat,$ownLng" }

                                val dist = if (ownLat != null && ownLng != null) {
                                    distanceBetween(ownLat, ownLng, lat, lng).toDouble()
                                } else {
                                    null
                                }
                                val revealed = dist != null && dist > REVEAL_THRESHOLD_M

                                logD { "partner distance=${dist}m  threshold=${REVEAL_THRESHOLD_M}m  revealed=$revealed" }

                                // Detect reveal transition so we can clear the stale
                                // displayedRoute* tracker (set during pre-reveal OSRM)
                                // that would otherwise reject the forced OSRM result.
                                val isRevealTransition = revealed && !_uiState.value.partnerRevealed

                                _uiState.update { current ->
                                    val wasRevealed = current.partnerRevealed
                                    val wasFound = current.showFoundDialog
                                    val timeOk = System.currentTimeMillis() >= current.foundDialogEnabledAtMs
                                    val nowFound = dist != null && dist <= FOUND_THRESHOLD_M && timeOk
                                    val justRevealed = isRevealTransition  // already computed outside
                                    // Show green checkmark when partner is ≤30m but auto-found hasn't triggered yet
                                    val checkmarkVisible = dist != null && dist <= CHECKMARK_THRESHOLD_M && !wasFound && !nowFound
                                    current.copy(
                                        // Always store raw coords for route calculation,
                                        // but only reveal exact location when >10m (privacy)
                                        rawPartnerLat = lat,
                                        rawPartnerLng = lng,
                                        partnerLat = if (revealed) lat else null,
                                        partnerLng = if (revealed) lng else null,
                                        partnerDistance = dist,
                                        partnerRevealed = revealed,
                                        hasPartnerLocation = true,
                                        showCheckmark = checkmarkVisible,
                                        walkRoute = when {
                                            justRevealed && current.pendingWalkRoute != null -> {
                                                Log.d(TAG, "promoting cached route on reveal")
                                                current.pendingWalkRoute
                                            }
                                            revealed -> current.walkRoute
                                            else -> null
                                        },
                                        pendingWalkRoute = if (justRevealed) null else current.pendingWalkRoute,
                                        showFoundDialog = wasFound || nowFound,
                                        // If this update triggers found, cancel any pending disconnect dialog
                                        showDisconnectDialog = if (wasFound || nowFound) false else current.showDisconnectDialog,
                                        error = if (wasFound || nowFound) null else current.error
                                    )
                                }
                                // On reveal transition: clear stale displayedRoute* so the forced
                                // OSRM result doesn't get rejected by anti-flip comparing against
                                // old pre-reveal positions (which may be meters away from current).
                                if (isRevealTransition) {
                                    displayedRouteOwnLat = null
                                    displayedRouteOwnLng = null
                                    displayedRoutePartnerLat = null
                                    displayedRoutePartnerLng = null
                                }
                                // Fire navigation_started once when partner enters visible range
                                if (isRevealTransition && !navigationStartedFired) {
                                    navigationStartedFired = true
                                }
                                // Request route calculation using raw partner coords
                                requestRouteUpdate(force = revealed)
                            }
                        }
                    }
                    "session_complete" -> {
                        // Partner manually marked session as complete
                        _uiState.update { it.copy(showFoundDialog = true) }
                    }
                    "route" -> {
                        // Route sent by Marco — both sides call OSRM independently now,
                        // so Marco's route is not used for display (Polo's own OSRM
                        // result has correctly-oriented steps for Polo's direction).
                        // Cache as pendingWalkRoute only (fallback for reveal promotion
                        // if Polo's own OSRM hasn't completed yet).
                        val geo = msg.geometry
                        val d = msg.distance
                        val dur = msg.duration
                        if (geo != null && d != null && dur != null) {
                            val route = RouteResult(
                                geometry = geo, distance = d, duration = dur,
                                steps = msg.steps ?: emptyList()
                            )
                            _uiState.update { it.copy(pendingWalkRoute = route) }
                            Log.d(TAG, "rcvd route from Marco, cached as pending")
                        } else {
                            Log.d(TAG, "route msg missing fields, ignored")
                        }
                    }
                    "error", "disconnected" -> {
                        _uiState.update { it.copy(error = "connection_lost") }
                    }
                }
            }
        }
    }

    private fun startCountdown() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            countdownFlow().collect { seconds ->
                _uiState.update { it.copy(remainingSeconds = seconds) }
                if (seconds <= 0) cleanup()
            }
        }
    }

    /** Manually mark the session as complete. Sends notification to partner
     *  and triggers the congratulations dialog on both sides. */
    fun completeSession() {
        _uiState.update { it.copy(showFoundDialog = true) }
        relayClient.sendSessionComplete()
    }

    /**
     * Calculate walking route via OSRM when both locations known.
     * Uses raw partner coords for pre-reveal route calculation.
     * Debounced: won't call more than once per 10s unless moved > 30m.
     *
     * @param force if true, bypasses debounce (used on reveal transition)
     */
    private fun requestRouteUpdate(force: Boolean = false) {
        val state = _uiState.value
        val ownLat = state.ownLat ?: run {
            Log.d(TAG, "requestRouteUpdate: ownLat is null, skipping")
            return
        }
        val ownLng = state.ownLng ?: run {
            Log.d(TAG, "requestRouteUpdate: ownLng is null, skipping")
            return
        }
        if (!state.hasPartnerLocation) {
            Log.d(TAG, "requestRouteUpdate: no partner location yet, skipping")
            return
        }
        val partnerLat = state.rawPartnerLat ?: run {
            Log.d(TAG, "requestRouteUpdate: rawPartnerLat is null, skipping")
            return
        }
        val partnerLng = state.rawPartnerLng ?: run {
            Log.d(TAG, "requestRouteUpdate: rawPartnerLng is null, skipping")
            return
        }

        Log.d(TAG, "requestRouteUpdate: own=$ownLat,$ownLng  partner=$partnerLat,$partnerLng  force=$force")

        // Debounce: check if enough time has passed OR if moved significantly
        val now = System.currentTimeMillis()
        val enoughTimePassed = (now - lastRouteCalcMs) >= ROUTE_MIN_INTERVAL_MS
        val ownMoved = lastRouteOwnLat == null || distanceBetween(
            lastRouteOwnLat!!, lastRouteOwnLng!!, ownLat, ownLng
        ) > ROUTE_MOVEMENT_THRESHOLD_M
        val partnerMoved = lastRoutePartnerLat == null || distanceBetween(
            lastRoutePartnerLat!!, lastRoutePartnerLng!!, partnerLat, partnerLng
        ) > ROUTE_MOVEMENT_THRESHOLD_M

        Log.d(TAG, "requestRouteUpdate: enoughTimePassed=$enoughTimePassed ownMoved=$ownMoved partnerMoved=$partnerMoved")

        if (!force && !enoughTimePassed && !ownMoved && !partnerMoved) {
            Log.d(TAG, "requestRouteUpdate: debounce skip")
            return
        }

        // Update last known positions
        lastRouteOwnLat = ownLat
        lastRouteOwnLng = ownLng
        lastRoutePartnerLat = partnerLat
        lastRoutePartnerLng = partnerLng
        lastRouteCalcMs = now

        // Cancel previous job AFTER starting new one avoids self-cancellation
        // when the delayed refresh (3s GPS settle) fires requestRouteUpdate.
        val prevRouteJob = routeJob
        routeJob = viewModelScope.launch {
            prevRouteJob?.cancel()
            Log.d(TAG, "requestRouteUpdate: calculating WALKING route")
            val result = RouteFinder.findRoute(ownLat, ownLng, partnerLat, partnerLng, _useFootpath.value)

            Log.d(TAG, "requestRouteUpdate: walkResult=${result != null}")

            val hadWalkRouteBefore = _uiState.value.walkRoute != null

            _uiState.update { state ->
                // Accept when positions changed (even if longer), reject
                // GPS-jitter flips (same positions, near-equivalent path).
                val bestWalk = if (state.partnerRevealed) {
                    when {
                        result == null -> null
                        state.walkRoute == null || force -> {
                            // First route, OR forced refresh — always accept.
                            // Without `force` bypassing anti-flip, the delayed
                            // GPS-settle refresh would get its new route rejected.
                            if (force) {
                                displayedRouteOwnLat = ownLat; displayedRouteOwnLng = ownLng
                                displayedRoutePartnerLat = partnerLat; displayedRoutePartnerLng = partnerLng
                            }
                            result
                        }
                        else -> {
                            val ownChanged = displayedRouteOwnLat == null || distanceBetween(
                                displayedRouteOwnLat!!, displayedRouteOwnLng!!, ownLat, ownLng
                            ) > ROUTE_MOVEMENT_THRESHOLD_M
                            val partnerChanged = displayedRoutePartnerLat == null || distanceBetween(
                                displayedRoutePartnerLat!!, displayedRoutePartnerLng!!, partnerLat, partnerLng
                            ) > ROUTE_MOVEMENT_THRESHOLD_M
                            if (ownChanged || partnerChanged) {
                                displayedRouteOwnLat = ownLat; displayedRouteOwnLng = ownLng
                                displayedRoutePartnerLat = partnerLat; displayedRoutePartnerLng = partnerLng
                                result
                            } else if (result.distance < state.walkRoute.distance) {
                                result
                            } else {
                                state.walkRoute
                            }
                        }
                    }
                } else {
                    state.walkRoute
                }
                state.copy(
                    walkRoute = bestWalk,
                    pendingWalkRoute = result ?: state.pendingWalkRoute
                )
            }

            // After the first route result, schedule a refresh once GPS settles
            // (8s). Cold GPS fix on first connection can be 20-50m off, giving
            // a route that starts on the wrong street. Without this refresh the
            // wrong route lingers until Polo sends a new location update.
            if (!hadWalkRouteBefore && result != null) {
                delay(8000L)
                requestRouteUpdate(force = true)
            }
        }
    }

    fun cleanup() {
        timerJob?.cancel()
        locationJob?.cancel()
        routeJob?.cancel()
        relayClient.disconnect()
        LocationService.clearCache()
        getApplication<Application>().stopService(
            Intent(getApplication(), LocationService::class.java)
        )
        _uiState.update { PoloUiState(permissionsReady = true) }
    }

    override fun onCleared() {
        cleanup()
        super.onCleared()
    }
}
