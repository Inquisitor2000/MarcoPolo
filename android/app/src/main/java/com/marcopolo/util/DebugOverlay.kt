package com.marcopolo.util

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight debug overlay showing key state variables.
 * Toggle via [show] boolean; background is semi-transparent dark.
 */
@Composable
fun DebugOverlay(
    show: Boolean,
    onToggle: () -> Unit,
    lines: List<Pair<String, String>>  // (label, value) pairs
) {
    if (!show) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .graphicsLayer(
                shape = RoundedCornerShape(8.dp),
                clip = true
            )
            .background(Color(0xCC1A1A2E))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "🐛 DEBUG",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFDD57),
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        lines.forEach { (label, value) ->
            Text(
                text = "$label: $value",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                lineHeight = 16.sp
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Tap to hide",
            fontSize = 9.sp,
            color = Color(0xFF888888)
        )
    }
}
