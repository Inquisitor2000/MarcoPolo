package com.marcopolo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
 * Toggle a small floating debug panel that shows key runtime state.
 * Tap the "DBG" badge to show/hide. The panel overlays the map with
 * dark translucent background so it's readable in any lighting.
 */
@Composable
fun DebugPanel(
    info: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End
    ) {
        // Toggle badge
        Text(
            text = "DBG",
            modifier = Modifier
                .background(
                    color = Color(0xAA000000),
                    shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                )
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color(0xFF4ADE80),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )

        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xDD111111)
                )
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    info.forEach { (label, value) ->
                        Text(
                            text = "$label: $value",
                            color = Color(0xFF4ADE80),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}
