@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.marcopolo.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * OSRM (Open Source Routing Machine) client for walking directions.
 * Only Marco calls this — results are sent over WebSocket to Polo.
 * Only walking mode is supported (no car).
 */
object RouteFinder {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetch a walking route between two GPS coordinates.
     * Returns [RouteResult] with polyline geometry, distance (m) and duration (s).
     */
    suspend fun findRoute(
        fromLat: Double, fromLng: Double,
        toLat: Double, toLng: Double
    ): RouteResult? {
        // OSRM expects lng,lat order
        val url = "https://router.project-osrm.org/route/v1/foot/" +
                "$fromLng,$fromLat;$toLng,$toLat" +
                "?overview=full&geometries=geojson&alternatives=false&steps=false"

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext null
                val osrm = json.decodeFromString<OsrmResponse>(body)
                if (osrm.code != "Ok" || osrm.routes.isEmpty()) return@withContext null

                val route = osrm.routes.first()
                // Convert OSRM's [lng, lat] to our [lat, lng] for GeoPoint
                val geometry = route.geometry.coordinates.map { coord ->
                    listOf(coord[1], coord[0]) // [lat, lng]
                }
                RouteResult(
                    geometry = geometry,
                    distance = route.distance,
                    duration = route.duration
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

// ── OSRM API response models ─────────────────────────────────────────────────

@Serializable
data class OsrmResponse(val code: String, val routes: List<OsrmRoute>)

@Serializable
data class OsrmRoute(
    val geometry: OsrmGeometry,
    val distance: Double,
    val duration: Double
)

@Serializable
data class OsrmGeometry(val coordinates: List<List<Double>>)

// ── Our result type ──────────────────────────────────────────────────────────

/**
 * Result of a route calculation.
 * @param geometry list of [lat, lng] coordinate pairs for the route polyline
 * @param distance total distance in meters
 * @param duration estimated duration in seconds
 */
data class RouteResult(
    val geometry: List<List<Double>>,
    val distance: Double,
    val duration: Double
)
