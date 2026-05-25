package com.marcopolo.viewmodel

import android.app.Application
import android.content.Intent
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marcopolo.model.WsMessage
import com.marcopolo.network.RelayClient
import com.marcopolo.network.RouteFinder
import com.marcopolo.network.RouteResult
import com.marcopolo.service.LocationService
import com.marcopolo.util.countdownFlow
import kotlinx.coroutines.Job
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
    // Walking route calculated by Marco
    val walkRoute: RouteResult? = null,
    // Raw partner coords (always set on receive, even before reveal)
    val rawPartnerLat: Double? = null,
    val rawPartnerLng: Double? = null,
    // Debug counters
    val sentCount: Int = 0
)

class MarcoViewModel(application: Application) : AndroidViewModel(application) {

    private val relayClient = RelayClient()
    private val _uiState = MutableStateFlow(MarcoUiState())
    val uiState: StateFlow<MarcoUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var locationJob: Job? = null
    private var routeJob: Job? = null

    // Route debounce tracking
    private var lastRouteCalcMs: Long = 0L
    private var lastRouteOwnLat: Double? = null
    private var lastRouteOwnLng: Double? = null
    private var lastRoutePartnerLat: Double? = null
    private var lastRoutePartnerLng: Double? = null

    companion object {
        private const val TAG = "MarcoPolo.Marco"
        private const val ROUTE_MIN_INTERVAL_MS = 10_000L   // Don't recalculate more than every 10s
        private const val ROUTE_MOVEMENT_THRESHOLD_M = 30f   // Recalculate only if moved > 30m
        private const val REVEAL_THRESHOLD_M = 10
        private const val FOUND_THRESHOLD_M = 15
    }

    /**
     * Called after location permissions are granted.
     * Creates room on relay, connects WebSocket, and starts GPS collection immediately.
     */
    fun onPermissionsGranted() {
        if (_uiState.value.permissionsReady) return
        _uiState.update { it.copy(permissionsReady = true) }

        // Start GPS collection immediately (pre-load location data before partner joins)
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
                        Log.d(TAG, "own location: $lat, $lng  accuracy=${location.accuracy}")
                        _uiState.update { it.copy(ownLat = lat, ownLng = lng, sentCount = it.sentCount + 1) }
                        relayClient.sendLocation(lat, lng, location.accuracy)
                        Log.d(TAG, "sendLocation called (sent=${_uiState.value.sentCount}) bearing=$bearing")
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

        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            Log.d(TAG, "requestRouteUpdate: calculating WALKING route")
            val result = RouteFinder.findRoute(ownLat, ownLng, partnerLat, partnerLng)

            Log.d(TAG, "requestRouteUpdate: walkResult=${result != null}")

            _uiState.update { state ->
                // Only show route on Marco's map when partner is revealed
                state.copy(walkRoute = if (state.partnerRevealed) result else null)
            }

            // Send route to Polo even when not revealed (Polo caches it)
            result?.let {
                relayClient.sendRoute(it.geometry, it.distance, it.duration, "foot")
                Log.d(TAG, "walk route sent to Polo (${it.geometry.size} pts)")
            }
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
                        _uiState.update { it.copy(isActive = true) }
                        startCountdown()
                        onLocationReady()
                    }
                    "partner_disconnected" -> {
                        _uiState.update {
                            it.copy(
                                isActive = false,
                                error = "Polo disconnected",
                                showDisconnectDialog = true
                            )
                        }
                        // Don't cleanup — let user dismiss the dialog first
                    }
                    "location" -> {
                        msg.lat?.let { lat ->
                            msg.lng?.let { lng ->
                                val state = _uiState.value
                                val ownLat = state.ownLat
                                val ownLng = state.ownLng

                                Log.d(TAG, "rcvd partner location: $lat,$lng  from=${msg.from}  ownGPS=$ownLat,$ownLng")

                                // Always compute straight-line distance
                                val dist = if (ownLat != null && ownLng != null) {
                                    distanceBetween(ownLat, ownLng, lat, lng).toDouble()
                                } else {
                                    null
                                }
                                val revealed = dist != null && dist > REVEAL_THRESHOLD_M

                                Log.d(TAG, "partner distance=${dist}m  threshold=${REVEAL_THRESHOLD_M}m  revealed=$revealed")

                                _uiState.update { current ->
                                    val wasFound = current.showFoundDialog
                                    val nowFound = dist != null && dist <= FOUND_THRESHOLD_M
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
                                        // Clear walkRoute when unrevealed
                                        walkRoute = if (revealed) current.walkRoute else null,
                                        showFoundDialog = wasFound || nowFound
                                    )
                                }
                                // Always request route calc using raw coords (pre-reveal caching)
                                // Force immediate calculation when revealed
                                Log.d(TAG, "requesting route update (revealed=$revealed)")
                                requestRouteUpdate(force = revealed)
                            }
                        }
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

    fun dismissFoundDialog() {
        _uiState.update { it.copy(showFoundDialog = false) }
    }

    fun cleanup() {
        timerJob?.cancel()
        locationJob?.cancel()
        routeJob?.cancel()
        relayClient.disconnect()
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
