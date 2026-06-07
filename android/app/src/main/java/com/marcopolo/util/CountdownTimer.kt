// SPDX-FileCopyrightText: 2026 Marco Polo Authors
// SPDX-License-Identifier: GPL-3.0-or-later

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


