package com.marcopolo.viewmodel

import android.app.Application
import android.content.Intent
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

data class MarcoUiState(
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
    val partnerDistance: Double? = null,   // straight-line meters (always computed)
    val partnerRevealed: Boolean = false,  // true when distance > REVEAL_THRESHOLD_M
    val hasPartnerLocation: Boolean = false, // true when any location data received from partner
    // Disconnect dialog
    val showDisconnectDialog: Boolean = false,
    // Found dialog
    val showFoundDialog: Boolean = false,
    // Minimum system time (ms) before found dialog can fire.
    // Gives GPS positions ~20s to settle after session activation.
    val foundDialogEnabledAtMs: Long = 0L,
    // Walking route (displayed on map when revealed)
    val walkRoute: RouteResult? = null,
    // Pre-reveal route cache — OSRM result saved here while partner not revealed,
    // promoted to walkRoute on reveal transition (avoids double OSRM call).
    val pendingWalkRoute: RouteResult? = null,
    // Raw partner coords (always set on receive, even before reveal)
    val rawPartnerLat: Double? = null,
    val rawPartnerLng: Double? = null,
    // Green checkmark (manual found when ≤30m)
    val showCheckmark: Boolean = false,
    // Debug counters
    val sentCount: Int = 0
)

/** Subset of UI state that drives the map rendering. Split from [MarcoUiState]
 * to avoid map recomposition on non-map state changes (e.g., countdown ticks, error messages). */
data class MarcoMapState(
    val ownLat: Double? = null,
    val ownLng: Double? = null,
    val ownBearing: Float? = null,
    val partnerLat: Double? = null,
    val partnerLng: Double? = null,
    val routeLatLngs: List<List<Double>>? = null,
    val routeSteps: List<RouteStep> = emptyList(),
    val distanceToTarget: Double? = null,
    val showCheckmark: Boolean = false,
    val isActive: Boolean = false,
    val hasPartnerLocation: Boolean = false
)

class MarcoViewModel(application: Application) : AndroidViewModel(application) {

    private val relayClient = RelayClient()
    private val _uiState = MutableStateFlow(MarcoUiState())
    val uiState: StateFlow<MarcoUiState> = _uiState.asStateFlow()

    /** Derive map-relevant state to avoid recomposition on UI-only changes */
    val mapState: StateFlow<MarcoMapState> = _uiState.map { ui ->
        MarcoMapState(
            ownLat = ui.ownLat,
            ownLng = ui.ownLng,
            ownBearing = ui.ownBearing,
            partnerLat = ui.partnerLat,
            partnerLng = ui.partnerLng,
            routeLatLngs = ui.walkRoute?.geometry,
            routeSteps = ui.walkRoute?.steps ?: emptyList(),
            distanceToTarget = ui.partnerDistance,
            showCheckmark = ui.showCheckmark,
            isActive = ui.isActive,
            hasPartnerLocation = ui.hasPartnerLocation
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MarcoMapState())

    private var timerJob: Job? = null
    private var locationJob: Job? = null
    private var routeJob: Job? = null

    // Route debounce tracking
    private var lastRouteCalcMs: Long = 0L
    private var lastRouteOwnLat: Double? = null
    private var lastRouteOwnLng: Double? = null
    private var lastRoutePartnerLat: Double? = null
    private var lastRoutePartnerLng: Double? = null

    /** Positions used for the currently DISPLAYED route (not just last calc attempt).
     *  Used to distinguish "positions actually changed" from "same positions, GPS jitter".
     *  Updated only when a new route is accepted into state.walkRoute. */
    private var displayedRouteOwnLat: Double? = null
    private var displayedRouteOwnLng: Double? = null
    private var displayedRoutePartnerLat: Double? = null
    private var displayedRoutePartnerLng: Double? = null

    companion object {
        private const val TAG = "MarcoPolo.Marco"
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
     * Called after location permissions are granted.
     * Creates room on relay, connects WebSocket, and starts GPS collection immediately.
     */
    fun onPermissionsGranted() {
        if (_uiState.value.permissionsReady) return
        _uiState.update { it.copy(permissionsReady = true) }

        // Start GPS collection immediately (preload location data before partner joins)
        onLocationReady()

        viewModelScope.launch {
            val result = relayClient.createRoom()
            result.fold(
                onSuccess = { code ->
                    _uiState.update { it.copy(roomCode = code) }
                    relayClient.connect(code)
                    listenForMessages()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(error = "Failed to create room: ${e.message}")
                    }
                }
            )
        }
    }

    /**
     * Called when LocationService has a fix and is producing GPS data.
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
                LocationService.compassHeading
            ) { location, heading ->
                // Prefer compass heading over GPS movement bearing
                val bearing = heading ?: (if (location?.hasBearing() == true) location.bearing else null)
                Pair(location, bearing)
            }.collect { (location, bearing) ->
                // Always update bearing (compass updates even when stationary)
                _uiState.update { it.copy(ownBearing = bearing) }
                // Only send location / update route when coordinates actually change
                if (location != null) {
                    val lat = location.latitude
                    val lng = location.longitude
                    if (lat != lastLat || lng != lastLng) {
                        lastLat = lat
                        lastLng = lng
                        logD { "own location: $lat, $lng  accuracy=${location.accuracy}" }
                        _uiState.update { it.copy(ownLat = lat, ownLng = lng, sentCount = it.sentCount + 1) }
                        relayClient.sendLocation(lat, lng, location.accuracy)
                        logD { "sendLocation called (sent=${_uiState.value.sentCount}) bearing=$bearing" }
                        requestRouteUpdate()
                    }
                }
            }
        }
    }

    /**
     * Calculate walking route via OSRM when both locations known.
     * Uses raw partner coords (pre-reveal) for faster route delivery.
     * Debounced: won't call more than once per 10s unless moved > 30m.
     * Sends the route to Polo over WebSocket.
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
        // Use raw partner coords for pre-reveal OSRM calculation
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

        // Update last known positions (using raw coords for partner)
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
            val result = RouteFinder.findRoute(ownLat, ownLng, partnerLat, partnerLng)

            Log.d(TAG, "requestRouteUpdate: walkResult=${result != null}")

            // Accept new route when positions actually changed (even if longer),
            // but reject GPS-jitter flips (same positions, near-equivalent path).
            // Track displayed-route positions to distinguish the two cases.
            val hadWalkRouteBefore = _uiState.value.walkRoute != null
            val keptRoute = if (result == null) null else {
                val currentWalkRoute = _uiState.value.walkRoute
                if (currentWalkRoute == null || force) {
                    // First route, OR forced refresh — always accept.
                    // Without `force` bypassing anti-flip, the delayed
                    // cold-GPS-settle refresh would get its new route
                    // rejected because positions hadn't moved >30m.
                    if (force) {
                        // Update displayedRoute* so subsequent anti-flip
                        // works from this settled GPS position.
                        displayedRouteOwnLat = ownLat; displayedRouteOwnLng = ownLng
                        displayedRoutePartnerLat = partnerLat; displayedRoutePartnerLng = partnerLng
                    }
                    result
                } else {
                    val ownChanged = displayedRouteOwnLat == null || distanceBetween(
                        displayedRouteOwnLat!!, displayedRouteOwnLng!!, ownLat, ownLng
                    ) > ROUTE_MOVEMENT_THRESHOLD_M
                    val partnerChanged = displayedRoutePartnerLat == null || distanceBetween(
                        displayedRoutePartnerLat!!, displayedRoutePartnerLng!!, partnerLat, partnerLng
                    ) > ROUTE_MOVEMENT_THRESHOLD_M
                    if (ownChanged || partnerChanged) {
                        // Positions moved — accept regardless of distance
                        displayedRouteOwnLat = ownLat; displayedRouteOwnLng = ownLng
                        displayedRoutePartnerLat = partnerLat; displayedRoutePartnerLng = partnerLng
                        result
                    } else if (result.distance < currentWalkRoute.distance) {
                        // Same positions, strictly shorter — anti-flip accept
                        result
                    } else {
                        currentWalkRoute
                    }
                }
            }

            _uiState.update { state ->
                if (state.partnerRevealed) {
                    // Store in walkRoute, clear pending cache
                    state.copy(walkRoute = keptRoute, pendingWalkRoute = null)
                } else {
                    // Cache in pendingWalkRoute — promoted on reveal transition
                    state.copy(walkRoute = null, pendingWalkRoute = keptRoute)
                }
            }

            // After the first route result, schedule a refresh once GPS settles
            // (8s). Cold GPS fix on first connection can be 20-50m off, giving
            // a route that starts on the wrong street. Without this refresh the
            // wrong route lingers until Polo sends a new location update.
            if (!hadWalkRouteBefore && keptRoute != null) {
                delay(8000L)
                requestRouteUpdate(force = true)
            }

            // Route sharing via WebSocket removed — both Maco and Polo call OSRM
            // independently with their own position as origin. Sending Marco's
            // route to Polo caused Polo to display Marco-oriented turn-by-turn
            // instructions instead of Polo's own (direction-reversed).
        }
    }

    /** Haversine distance in meters */
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
                                // Already found — don't change anything, found dialog
                                // handles navigation + cleanup after delay
                                current
                            } else {
                                current.copy(
                                    isActive = false,
                                    error = "Polo disconnected",
                                    showDisconnectDialog = true
                                )
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

                                // Always compute straight-line distance
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
                                    val wasFound = current.showFoundDialog
                                    val timeOk = System.currentTimeMillis() >= current.foundDialogEnabledAtMs
                                    val nowFound = dist != null && dist <= FOUND_THRESHOLD_M && timeOk
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
                                        // Promote pendingWalkRoute on reveal (avoids double OSRM call);
                                        // clear walkRoute when unrevealed.
                                        walkRoute = when {
                                            revealed && current.pendingWalkRoute != null -> current.pendingWalkRoute
                                            revealed -> current.walkRoute
                                            else -> null
                                        },
                                        pendingWalkRoute = if (revealed) null else current.pendingWalkRoute,
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

                                // Always request route calc using raw coords (pre-reveal caching)
                                // Force immediate calculation when revealed
                                logD { "requesting route update (revealed=$revealed)" }
                                requestRouteUpdate(force = revealed)
                            }
                        }
                    }
                    "session_complete" -> {
                        // Partner manually marked session as complete
                        _uiState.update { it.copy(showFoundDialog = true) }
                    }
                    "error" -> {
                        _uiState.update { it.copy(error = "Connection error") }
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
                if (seconds <= 0) {
                    cleanup()
                }
            }
        }
    }

    /** Manually mark the session as complete. Sends notification to partner
     *  and triggers the congratulations dialog on both sides. */
    fun completeSession() {
        _uiState.update { it.copy(showFoundDialog = true) }
        relayClient.sendSessionComplete()
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
        _uiState.update { MarcoUiState() }
    }

    override fun onCleared() {
        cleanup()
        super.onCleared()
    }
}
