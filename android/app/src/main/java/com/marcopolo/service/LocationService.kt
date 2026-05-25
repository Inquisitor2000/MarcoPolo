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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that polls GPS via FusedLocationProviderClient
 * and exposes current location as a StateFlow.
 */
class LocationService : Service(), SensorEventListener {

    companion object {
        private val _currentLocation = MutableStateFlow<Location?>(null)
        val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

        /** Compass heading in degrees (0=north, 90=east, clockwise).
         *  Updated from the rotation-vector sensor; null if sensor unavailable or no reading yet. */
        private val _compassHeading = MutableStateFlow<Float?>(null)
        val compassHeading: StateFlow<Float?> = _compassHeading.asStateFlow()

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

        private const val CHANNEL_ID = "marcopolo_location"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // ── Compass: rotation-vector sensor ──
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Register periodic location callback (every 2s)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                _currentLocation.value = result.lastLocation
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L  // 5-second interval
        ).apply {
            setMinUpdateIntervalMillis(3000L)
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper
            )

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
        } catch (e: SecurityException) {
            // Location permission not granted
            stopSelf()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── SensorEventListener ──────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
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
        // No-op — compass works even at reduced accuracy for this use case
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        _currentLocation.value = null
        _compassHeading.value = null
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
