// SPDX-FileCopyrightText: 2026 Marco Polo Authors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.marcopolo.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Trigger to refresh all composable strings after language switch.
 *  Call this instead of Activity.recreate() — it updates LocalContext
 *  with a locale-wrapped context, causing every stringResource() call
 *  to recompose with the new language. The MapView stays alive. */
val LocalLanguageRefresh = staticCompositionLocalOf<() -> Unit> { {} }
