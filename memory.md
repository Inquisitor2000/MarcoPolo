# FindMe (Marco Polo)

Real-time location-sharing app for two people to find each other in crowds. Marco creates a session, Polo joins with a 4-char code, both see each other on a map with a walking route and 15-min countdown.

---

## Tech Stack

| Layer | Tech | Version |
|-------|------|---------|
| Relay Server | Node.js + ws | 20 / ^8.17 |
| Android | Kotlin + Jetpack Compose | 2.3.21 / BOM 2026.05.01 |
| Maps | osmdroid (no Google Maps API) | 6.1.20 |
| Tiles | CartoDB Positron (free) | — |
| Routing | OSRM public API (free) | — |
| GPS | Google Play Services Location | 21.3.0 |
| WS Client | OkHttp | 4.12.0 (intentionally kept on 4.x, 5.x has breaking artifact split) |
| Compile SDK / Target | 36 (Android 16) | — |
| Min SDK | 29 (Android 10) | — |
| AGP | 8.12.0 | — |
| Gradle | 8.13 | — |
| Deploy | Render / Fly.io / Railway / Docker | — |

---

## Architecture

```
Android App A ←──WebSocket──→ Relay Server ←──WebSocket──→ Android App B
     │                              │
     ├── OSRM API (both sides)      └── In-memory rooms, 17min TTL
     └── Route cached pre-reveal
```

**Relay Server** (`server/server.js`, ~236 lines): Single-file Node.js server. REST endpoints (`POST /rooms`, `GET /join/{code}`) + WebSocket upgrade on `/ws/{code}`. In-memory room map with periodic cleanup. Forwards `location` and `route` messages between paired peers.

**Android App**: MVVM with `StateFlow<UiState>`. Entry: `MainActivity.kt` → NavGraph (4 routes). Network layer: `RelayClient.kt` (OkHttp WS + HTTP). GPS: `LocationService.kt` (foreground service, FusedLocationProvider). Map: `MarcoMap.kt` (osmdroid composable, CartoDB tiles). Routing: `RouteFinder.kt` (OSRM `foot` profile).

---

## Key Flow

1. **Marco** → POST `/rooms` → gets 4-char code → shares via Android share sheet
2. **Polo** → enters code → both connect WS to `/ws/{code}`
3. Both send GPS locations (~1-2s interval) via WS → server relays to partner
4. **Both** call OSRM independently for walking route (debounced: 10s + 30m movement). Route sent via WS is also cached pre-reveal.
5. **Privacy**: Partner location hidden until distance > 10m
6. **Timeout**: 15-min countdown, auto-cleanup on expire/disconnect

---

## Key Files

### Relay Server
- `server/server.js` — Everything: HTTP + WS server, room management, message relay
- `server/test-client.html` — Browser test harness (side-by-side Marco/Polo)
- `server/tunnel.sh` — Start relay + Cloudflare tunnel for local dev

### Android App
- `MainActivity.kt` — Entry, deep link parsing (`marcopolo://join/{code}`), nav graph
- `network/RelayClient.kt` — WS + HTTP client, `createRoom()`, `sendLocation()`, `sendRoute()`
- `network/RouteFinder.kt` — OSRM client, returns GeoJSON route
- `network/ServerConfig.kt` — Relay URL (hardcoded to `marcopolo-relay.onrender.com`)
- `service/LocationService.kt` — Foreground GPS + compass heading
- `viewmodel/MarcoViewModel.kt` — Room creation, OSRM route calculation, 10m reveal, 20s found delay, pre-reveal OSRM
- `viewmodel/PoloViewModel.kt` — Room join, OSRM route calculation, pending route cache for pre-reveal promotion
- `ui/MarcoMap.kt` — osmdroid map wrapper: tiles, markers, route polyline, follow-me
- `ui/HomeScreen.kt` — Role selection screen
- `ui/MarcoScreen.kt` — Marco's map + room code + share
- `ui/PoloConfigScreen.kt` — Code entry (4-char, auto-uppercase)
- `ui/PoloMapScreen.kt` — Polo's map view
- `model/LocationMessage.kt` — Data classes (`WsMessage`, `RoomResponse`)
- `util/CountdownTimer.kt` — `countdownFlow()`, `formatCountdown()`
- `util/DebugOverlay.kt` — Lightweight state debug panel (toggle via title tap)

---

## Design Decisions

- **No Firebase/Google Maps**: Zero cloud account needed. Free OSRM + CartoDB tiles.
- **Both calculate OSRM independently**: Both Marco and Polo call OSRM directly for walking route — avoids waiting for route relay. Route from partner is also cached for instant promotion on reveal.
- **10m reveal threshold**: Privacy feature — hides exact location until users are meaningfully apart.
- **Room codes**: 4-char alphanumeric, server-generated, single-use.
- **17-min room TTL**: 15 min session + 2 min buffer. Cleanup runs every 60s.
- **Compass heading**: Rotation-vector sensor → rotates own marker chevron on map.
- **Deep links**: `marcopolo://join/{code}` scheme for seamless Polo join.
- **OkHttp intentionally on 4.x**: v5.x has breaking artifact split (separate artifacts per platform), no benefit for this app.
- **Monochrome adaptive icon**: Android 13+ themed icons. White-only vector copy of foreground in `drawable/ic_launcher_monochrome.xml`, referenced in `ic_launcher.xml`.

---

## Compose Gotchas (BOM 2026.05.01 / Compose 1.11.x)

- **Icons no longer transitive** → must declare `material-icons-core` + `material-icons-extended` explicitly
- **`Modifier.clip()` removed** → use `Modifier.graphicsLayer(shape = …, clip = true)`
- **`LocalLifecycleOwner` moved** → from `androidx.compose.ui.platform` to `androidx.lifecycle.compose`
- **`Continuation.resume(value)` deprecated** → use `resumeWith(Result.success(value))`
- **Kotlin 2.x Compose compiler** → replace `composeOptions { kotlinCompilerExtensionVersion }` with `id("org.jetbrains.kotlin.plugin.compose")` plugin
- **`jvmTarget` DSL change** → `kotlinOptions { jvmTarget }` → `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` at top level

---

## External APIs

- **OSRM**: `https://router.project-osrm.org/route/v1/foot/{lng},{lat};{lng},{lat}?overview=full&geometries=geojson`
- **CartoDB tiles**: `https://{a|b|c}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png`
- **Cloudflare tunnel**: `cloudflared tunnel --url http://localhost:3000` (dev only)

---

## Deploy

```bash
# Local
cd server && node server.js

# With tunnel (public URL auto-captured)
cd server && bash tunnel.sh

# Docker
cd server && docker build -t marcopolo-relay . && docker run -p 3000:3000 marcopolo-relay
```

Configs: `render.yaml`, `server/fly.toml`, `server/Dockerfile`, `server/DEPLOY.md`

---

## Current Server

`wss://marcopolo-relay.onrender.com` (Render, health at `/health`)
