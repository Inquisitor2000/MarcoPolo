@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.marcopolo.model

import kotlinx.serialization.Serializable

@Serializable
data class RoomResponse(
    val code: String,
    val wsUrl: String
)

@Serializable
data class WsMessage(
    val type: String,          // "partner_joined", "partner_disconnected", "location", "route"
    val lat: Double? = null,
    val lng: Double? = null,
    val timestamp: Long? = null,
    val accuracy: Float? = null,
    val from: String? = null,
    val role: String? = null,
    // Route fields (sent by Marco, received by Polo)
    val geometry: List<List<Double>>? = null,  // [[lat, lng], ...]
    val distance: Double? = null,              // meters
    val duration: Double? = null,              // seconds
    val profile: String? = null                // "foot" | "driving"
)
