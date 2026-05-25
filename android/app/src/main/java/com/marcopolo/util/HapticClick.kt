package com.marcopolo.util

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

/**
 * Wraps an onClick lambda with haptic feedback (short vibration on button press).
 * Call at the usage site inside a composable scope.
 *
 * Usage:
 *   onClick = hapticClick { doStuff() }
 */
@Composable
fun hapticClick(onClick: () -> Unit): () -> Unit {
    val view = LocalView.current
    return {
        // CONFIRM requires API 30; silently no-ops on 29
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        onClick()
    }
}
