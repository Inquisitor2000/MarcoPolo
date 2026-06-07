// SPDX-FileCopyrightText: 2026 Marco Polo Authors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.marcopolo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcopolo.util.LocaleManager
import com.marcopolo.util.hapticClick

@Composable
fun LanguageSwitcher(
    modifier: Modifier = Modifier,
    onLanguageChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    var currentLang by remember { mutableStateOf(LocaleManager.getSavedLocale(context)) }

    val languages = listOf("en", "ro", "ru")
    val labels = mapOf("en" to "EN", "ro" to "RO", "ru" to "RU")
    val currentLabel = labels[currentLang] ?: "EN"

    val nextLang = remember(currentLang) {
        val idx = languages.indexOf(currentLang)
        languages[(idx + 1) % languages.size]
    }

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .border(BorderStroke(2.dp, Color(0xFF88FF88)), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = hapticClick {
                    LocaleManager.setLocale(context, nextLang)
                    currentLang = nextLang
                    onLanguageChanged()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = currentLabel,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF88FF88)
        )
    }
}
