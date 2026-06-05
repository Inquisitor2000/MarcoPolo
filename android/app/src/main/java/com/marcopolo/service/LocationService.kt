package com.marcopolo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that polls GPS via FusedLocationProviderClient
 * and exposes current location as a StateFlow.
 *
 * Lifecycle-aware: reduces GPS + sensor when the app is backgrounded > 60s.
 * Call [onAppForeground] / [onAppBackground] from the Activity lifecycle hooks.
 */
class LocationService : Service(), SensorEventListener {

    companion object {
        private val _currentLocation = MutableStateFlow<Location?>(null)
        val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

        /** Compass heading in degrees (0=north, 90=east, clockwise).
         *  Updated from the rotation-vector sensor; null if sensor unavailable or no reading yet.
         *  Throttled to max ~500ms between updates. */
        private val _compassHeading = MutableStateFlow<Float?>(null)
        val compassHeading: StateFlow<Float?> = _compassHeading.asStateFlow()

        /** Compass accuracy from rotation-vector sensor.
         *  Maps to SensorManager.SENSOR_STATUS_* constants:
         *  0 = UNRELIABLE, 1 = ACCURACY_LOW, 2 = ACCURACY_MEDIUM, 3 = ACCURACY_HIGH.
         *  Defaults to MEDIUM if sensor is available but hasn't reported accuracy yet. */
        private val _compassAccuracy = MutableStateFlow(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)
        val compassAccuracy: StateFlow<Int> = _compassAccuracy.asStateFlow()

        /** When true, the next onStartCommand will skip fusedLocationClient.lastLocation
         *  to avoid seeding a fresh session with stale GPS from a previous session. */
        private var skipLastLocation = false

        /** Clears the cached location and sets a flag to skip the system last-location
         *  fetch on the next service start. Call after a session ends (found or disconnect)
         *  so the next session builds location fresh from GPS. */
        fun clearCache() {
            _currentLocation.value = null
            skipLastLocation = true
        }

        /** Service instance reference — null when destroyed. Used by [onAppForeground] /
         *  [onAppBackground] which are called from Activity lifecycle (no binder needed). */
        @Volatile
        private var instance: LocationService? = null

        /** Called from Activity.onStart() — app came to foreground.
         *  Restores GPS + sensor at full rate. Cancels the 60s background reduction timer. */
        fun onAppForeground() {
            instance?.enterForeground()
        }

        /** Called from Activity.onStop() — app went to background.
         *  Starts a 60s timer. If app stays backgrounded, GPS + sensor are released.
         *  Quick foreground/back transitions (e.g. rotation) cancel harmlessly. */
        fun onAppBackground() {
            instance?.enterBackground()
        }

        /** Maximum interval (ms) between compass heading updates, even if sensor
         *  fires more frequently. Reduces CPU from sensor event processing. */
        private const val COMPASS_THROTTLE_MS = 500L

        /** Grace period (ms) after backgrounding before GPS + sensor are released. */
        private const val BACKGROUND_GRACE_MS = 60_000L

        private const val CHANNEL_ID = "marcopolo_location"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "MarcoPolo.LocSvc"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null

    // ── Foreground/background tracking ──

    /** Handler on the main looper for the 60s background reduction task. */
    private val bgHandler = Handler(Looper.getMainLooper())
    private val bgReduceTask = Runnable { reduceToBackground() }

    /** True when GPS location updates are currently registered. Guards against
     *  double-register / double-unregister. */
    private var gpsActive = false

    /** True when rotation-vector sensor listener is currently registered. */
    private var sensorActive = false

    /** System time (ms) of the last compass heading emission. Used to throttle
     *  onSensorChanged to [COMPASS_THROTTLE_MS] max rate. */
    private var lastCompassEmitMs = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        registerSensor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        registerGps()

        // Fetch last known location so the UI doesn't block waiting for first GPS fix.
        // Skip this after a session end (clearCache was called) to avoid stale data.
        if (!skipLastLocation) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null && _currentLocation.value == null) {
                    _currentLocation.value = location
                }
            }
        }
        skipLastLocation = false

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Foreground / Background lifecycle ────────────────────────

    /** Called when app becomes visible (Activity.onStart).
     *  Restores GPS + sensor immediately. Cancels the 60s reduction timer. */
    fun enterForeground() {
        bgHandler.removeCallbacks(bgReduceTask)
        if (!gpsActive) registerGps()
        if (!sensorActive) registerSensor()
    }

    /** Called when app goes to background (Activity.onStop).
     *  Starts 60s grace timer. If app returns before expiry, timer is cancelled
     *  in [enterForeground] and nothing changes. */
    fun enterBackground() {
        bgHandler.removeCallbacks(bgReduceTask)
        bgHandler.postDelayed(bgReduceTask, BACKGROUND_GRACE_MS)
    }

    /** Reduces the service to minimal resource usage — releases GPS + sensor.
     *  The foreground notification stays so Android doesn't kill the service;
     *  GPS and sensor are stopped until [enterForeground] restores them. */
    private fun reduceToBackground() {
        if (gpsActive) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            gpsActive = false
        }
        if (sensorActive) {
            sensorManager.unregisterListener(this)
            sensorActive = false
        }
    }

    // ── GPS registration ─────────────────────────────────────────

    /** Register GPS location updates via FusedLocationProviderClient.
     *  Idempotent — safe to call multiple times. */
    private fun registerGps() {
        if (gpsActive) return
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                _currentLocation.value = result.lastLocation
            }
        }
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L
        ).apply {
            setMinUpdateIntervalMillis(3000L)
        }.build()
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest, locationCallback, mainLooper
            )
            gpsActive = true
        } catch (_: SecurityException) {
            // Permission lost between grant and service start
            stopSelf()
        }
    }

    // ── Sensor registration ──────────────────────────────────────

    /** Register rotation-vector sensor listener. Idempotent. */
    private fun registerSensor() {
        if (sensorActive) return
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            sensorActive = true
        }
    }

    // ── SensorEventListener ──────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            // Throttle to COMPASS_THROTTLE_MS — sensor fires at ~200ms but views
            // only need heading updates at ~500ms for smooth rotation.
            val now = System.currentTimeMillis()
            if (now - lastCompassEmitMs < COMPASS_THROTTLE_MS) return
            lastCompassEmitMs = now

            // Convert rotation vector → azimuth (heading relative to magnetic north)
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            // orientation[0] = azimuth in radians, -π to +π, positive clockwise from north
            val heading = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
            _compassHeading.value = heading
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            _compassAccuracy.value = accuracy
        }
    }

    override fun onDestroy() {
        instance = null
        bgHandler.removeCallbacks(bgReduceTask)
        if (sensorActive) sensorManager.unregisterListener(this)
        if (gpsActive) fusedLocationClient.removeLocationUpdates(locationCallback)
        // Reset state flows so fresh session starts clean
        _currentLocation.value = null
        _compassHeading.value = null
        _compassAccuracy.value = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Marco Polo Location",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Marco Polo")
            .setContentText("Sharing your location")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

}
