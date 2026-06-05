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
        toLat: Double, toLng: Double,
        useFootpath: Boolean = true
    ): RouteResult? {
        // OSRM expects lng,lat order
        val baseUrl = if (useFootpath) {
            "https://routing.openstreetmap.de/routed-foot/route/v1/foot/"
        } else {
            "https://router.project-osrm.org/route/v1/foot/"
        }
        val url = baseUrl +
                "$fromLng,$fromLat;$toLng,$toLat" +
                "?overview=full&geometries=geojson&alternatives=false&steps=true"

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
                // Parse turn-by-turn steps from first leg
                val steps = route.legs.firstOrNull()?.steps?.map { osrmStep ->
                    val stepGeo = osrmStep.geometry.coordinates.map { coord ->
                        listOf(coord[1], coord[0])
                    }
                    RouteStep(
                        distance = osrmStep.distance,
                        instruction = generateInstruction(osrmStep.maneuver.type, osrmStep.maneuver.modifier, osrmStep.name),
                        geometry = stepGeo,
                        modifier = osrmStep.maneuver.modifier
                    )
                } ?: emptyList()
                RouteResult(
                    geometry = geometry,
                    distance = route.distance,
                    duration = route.duration,
                    steps = steps
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
    val duration: Double,
    val legs: List<OsrmLeg> = emptyList()
)

@Serializable
data class OsrmGeometry(val coordinates: List<List<Double>>)

@Serializable
data class OsrmLeg(val steps: List<OsrmStep> = emptyList())

@Serializable
data class OsrmStep(
    val distance: Double,
    val duration: Double,
    val geometry: OsrmGeometry,
    val name: String,
    val maneuver: OsrmManeuver
)

@Serializable
data class OsrmManeuver(
    val type: String,
    val modifier: String? = null
)

// ── Our domain types ─────────────────────────────────────────────────────────

/**
 * A single turn-by-turn step along the route.
 * @param distance step distance in meters
 * @param instruction human-readable instruction ("Turn left onto Main St")
 * @param geometry list of [lat, lng] pairs for this step segment
 * @param modifier maneuver modifier ("left", "right", "straight", etc.)
 */
@Serializable
data class RouteStep(
    val distance: Double,
    val instruction: String,
    val geometry: List<List<Double>>,
    val modifier: String? = null
)

/**
 * Result of a route calculation.
 * @param geometry list of [lat, lng] coordinate pairs for the route polyline
 * @param distance total distance in meters
 * @param duration estimated duration in seconds
 * @param steps turn-by-turn steps (empty if unavailable)
 */
data class RouteResult(
    val geometry: List<List<Double>>,
    val distance: Double,
    val duration: Double,
    val steps: List<RouteStep> = emptyList()
)

/** Shorten Romanian street prefixes for compact nav display. */
private fun shortenStreetName(name: String): String {
    return name
        .replaceFirst(Regex("^Strada ", RegexOption.IGNORE_CASE), "Str. ")
        .replaceFirst(Regex("^Bulevardul ", RegexOption.IGNORE_CASE), "Bd. ")
        .replaceFirst(Regex("^Aleea ", RegexOption.IGNORE_CASE), "Ale. ")
        .replaceFirst(Regex("^Soseaua ", RegexOption.IGNORE_CASE), "Șos. ")
        .replaceFirst(Regex("^Intrarea ", RegexOption.IGNORE_CASE), "Intr. ")
        .replaceFirst(Regex("^Fundătura ", RegexOption.IGNORE_CASE), "Fund. ")
}

/** Generate a human-readable instruction from OSRM maneuver fields. */
private fun generateInstruction(type: String, modifier: String?, name: String): String {
    val action = when (type) {
        "turn" -> when (modifier) {
            "left" -> "Turn left"
            "right" -> "Turn right"
            "sharp left" -> "Turn sharp left"
            "sharp right" -> "Turn sharp right"
            "slight left" -> "Bear left"
            "slight right" -> "Bear right"
            "straight" -> "Continue straight"
            "uturn" -> "Make a U-turn"
            else -> "Turn"
        }
        "continue" -> "Continue"
        "depart" -> "Head"
        "arrive" -> "Arrive"
        "end of road" -> when (modifier) {
            "left" -> "Turn left"
            "right" -> "Turn right"
            "straight" -> "Continue"
            else -> "Turn"
        }
        "fork" -> when (modifier) {
            "left" -> "Keep left"
            "right" -> "Keep right"
            else -> "Keep"
        }
        "roundabout", "roundabout turn" -> "Turn"
        "merge" -> "Merge"
        "new name" -> "Continue"
        "notification" -> "Continue"
        else -> "Continue"
    }
    val shortName = shortenStreetName(name)
    val onStreet = if (shortName.isNotBlank() && type != "depart") " onto $shortName"
        else if (shortName.isNotBlank() && type == "depart") " $shortName"
        else ""
    return "$action$onStreet"
}
