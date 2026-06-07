// SPDX-FileCopyrightText: 2026 Marco Polo Authors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.marcopolo.network

/**
 * Relay server URL config.
 *
 * Default is the cloud relay deployed on Render.
 * Change DEFAULT_URL when you deploy your own relay server.
 */
object ServerConfig {
    /** Public relay server — deployed on Render, works over cellular */
    const val DEFAULT_URL = "https://marcopolo-relay.onrender.com"

    /** Resolved base URL used by the relay client */
    val baseUrl: String get() = DEFAULT_URL
}
