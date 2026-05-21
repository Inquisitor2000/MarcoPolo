package com.marcopolo.model

enum class TravelMode(val osrmProfile: String, val label: String) {
    WALKING("foot", "On foot");

    companion object {
        fun fromProfile(profile: String): TravelMode =
            entries.firstOrNull { it.osrmProfile == profile } ?: WALKING
    }
}
