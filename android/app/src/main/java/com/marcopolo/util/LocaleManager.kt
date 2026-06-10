// SPDX-FileCopyrightText: 2026 Marco Polo Authors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.marcopolo.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleManager {
    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LOCALE = "app_locale"

    val supportedLocales = listOf("en", "ro", "ru")

    fun getSavedLocale(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LOCALE, "en") ?: "en"
    }

    fun setLocale(context: Context, languageCode: String): Context {
        saveLocale(context, languageCode)
        return updateLocale(context, languageCode)
    }

    fun updateLocale(context: Context, languageCode: String): Context {
        val locale = Locale.forLanguageTag(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    private fun saveLocale(context: Context, languageCode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCALE, languageCode)
            .apply()
    }
}
