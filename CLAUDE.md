# Marco Polo — Session Memory

## Gotchas
- **mapReady circular deadlock** (FIXED). `partnerLat` set null when dist ≤ 10m (not revealed). Old check `partnerLat != null` → map never rendered when partner close. Fix: `hasPartnerLocation` boolean tracks any received partner data, independent of reveal.
- **GPS acquisition delay** (FIXED). `ownLat` null until GPS fix → mapReady false → stuck on loading. Fix: `getLastLocation()` in LocationService for cached location + `mapReady = isActive && (ownLat != null || hasPartnerLocation)` (doesn't block on own GPS).
- **LSP false positives**: All `UNRESOLVED_REFERENCE` for Android/Kotlin SDK types are from missing Android SDK indexing, not real compile errors.

## Architecture
- **No cloud accounts/API keys** — osmdroid + CartoDB Voyager tiles (free, no key)
- **Relay**: `https://marcopolo-relay.onrender.com` (WebSocket relay for room/location sharing)
- **Routing**: OSRM walking routes (free, no key)
- **Target**: Android 10+ (Honor 20 tested)

## Key Files
- `MarcoViewModel.kt` — room creation, GPS collection, partner location handler, route calc
- `PoloViewModel.kt` — room join, GPS collection, partner location handler
- `MarcoScreen.kt` — Marco UI with three-state (waiting room / loading / map) + debug overlay
- `PoloMapScreen.kt` — Polo UI same three-state + debug overlay
- `MarcoMap.kt` — osmdroid wrapper with markers (You 36dp, partner 36dp), polylines (fg 10px, bg 22px), follow-me
- `LocationService.kt` — foreground service, FusedLocationProviderClient, compass via rotation sensor, `getLastLocation()` fallback
- `PermissionsViewModel.kt` — PENDING/GRANTED/DENIED state machine for location permission
- `HapticClick.kt` — `hapticClick()` composable wrapper for all 15 interactive buttons
- `DebugOverlay.kt` — lightweight state debug panel (toggle via title tap)
- `HomeScreen.kt` — permission gate + role selection
- `MainActivity.kt` — osmdroid init, NavHost with deep link support
- `RelayClient.kt` — OkHttp WebSocket, createRoom/connect/sendLocation/sendRoute

## UI State Machine
Three states per screen (once permissions granted):
1. **Room code** — `!isActive`: show code + share button + "Waiting for partner..."
2. **Loading** — `isActive && !mapReady`: spinner with context-aware message (GPS vs partner wait)
3. **Map** — `mapReady`: MarcoMap with markers, route, controls

`mapReady = isActive && (ownLat != null || hasPartnerLocation)`

## Reveal / Found Logic
- `REVEAL_THRESHOLD_M = 10`: partner coords hidden until dist > 10m (privacy)
- `FOUND_THRESHOLD_M = 15`: "found partner" dialog triggers when dist ≤ 15m
- `partnerLat/lng` stored as null when not revealed; `hasPartnerLocation` always set true on receive
- Reveal controls: marker visibility + route calculation (not raw coordinate storage)
