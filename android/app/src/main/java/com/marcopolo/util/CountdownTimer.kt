package com.marcopolo.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Emits remaining seconds from [totalSeconds] down to 0.
 * Used for the 15-minute session countdown.
 */
fun countdownFlow(totalSeconds: Int = 15 * 60): Flow<Int> = flow {
    for (i in totalSeconds downTo 0) {
        emit(i)
        delay(1000L)
    }
}

fun formatCountdown(seconds: Int): String {
    val min = seconds / 60
    val sec = seconds % 60
    return "%02d:%02d".format(min, sec)
}

/**
 * Formats a distance in meters to a human-readable string.
 * Shows "X m" for < 1 km, "X.X km" for >= 1 km.
 */
fun formatDistance(meters: Double): String {
    return if (meters < 1000) {
        "${meters.toInt()} m to target"
    } else {
        "%.1f km to target".format(meters / 1000)
    }
}
