# Marco Polo — Session Memory

## Gotchas
- **mapReady circular deadlock** (FIXED). `partnerLat` set null when dist ≤ 10m (not revealed). Old check `partnerLat != null` → map never rendered when partner close. Fix: `hasPartnerLocation` boolean tracks any received partner data, independent of reveal.
- **GPS acquisition delay** (FIXED). `ownLat` null until GPS fix → mapReady false → stuck on loading. Fix: `getLastLocation()` in LocationService for cached location + `mapReady = isActive && (ownLat != null || hasPartnerLocation)` (doesn't block on own GPS).
- **Route delivered but dropped** (FIXED). Marco sends route → Polo's GPS shows <10m (not revealed) → route discarded silently. Polo then waits for next Marco recalc cycle (5-15s). Fix: `pendingWalkRoute` caches route on Polo, promotes instantly on reveal transition.
- **bboxDone single-shot zoom** (FIXED). Map only zoomed to fit once on first render. Walking further from partner → marker off-screen permanently. Fix: dynamic re-zoom when `followMe` is on and diagonal changes > 30m (`MIN_BBOX_CHANGE`). `minZoomLevel=15.0` caps max zoom-out.
- **POST_NOTIFICATIONS on Android 10** (FIXED). Permission `POST_NOTIFICATIONS` (API 33+) requested unconditionally on Android 10 (API 29) where it doesn't exist. Fix: guarded behind `Build.VERSION.SDK_INT >= TIRAMISU` in both screens.
- **zoomToBoundingBox in update lambda freeze** (FIXED). Called during Compose layout phase before MapView has dimensions → potentially invalid calculation + main thread block on slower hardware. Fix: moved to `LaunchedEffect(bboxZoomTrigger)` that runs post-layout. Further crash fix: non-animated zoom + width/height guard + try-catch for Mali-G76 software layer.
- **Honor 20 freeze + crash** (FIXED). Two separate issues:
  - **Freeze**: Mali-G76 GPU driver hang on osmdroid Canvas tile rendering. Fix: `setLayerType(LAYER_TYPE_SOFTWARE, null)`.
  - **CPU overload**: compass orientation animation storm (~16Hz) + BitmapDrawable allocation per update frame overloaded CPU. Fix: bearing rounded to 5° (LaunchedEffect restarts ~1-2Hz), orientation animation delayed 2s (avoids startup burst), BitmapDrawable cached.
  - **Crash on route arrival**: `zoomToBoundingBox(animated=true)` crashed Mali-G76 software layer when route arrived mid-initialization. Fix: non-animated zoom + width/height guard + try-catch.
- **OSRM only after reveal** (FIXED). Marco waited for `partnerLat != null` (revealed) before calling OSRM API (1-3s latency). Fix: `rawPartnerLat/Lng` fields store raw coords always, OSRM starts pre-reveal. `force` parameter bypasses 10s debounce on reveal.
- **GPS asymmetry** (KNOWN). Marco and Polo get GPS fixes at different times. Marco may see 11m (revealed), Polo sees 9.5m (not revealed). Causes asymmetric reveal states. Polo's `pendingWalkRoute` cache bridges this window.
- **LSP false positives**: All `UNRESOLVED_REFERENCE` for Android/Kotlin SDK types are from missing Android SDK indexing, not real compile errors.

## Architecture
- **No cloud accounts/API keys** — osmdroid + CartoDB Voyager tiles (free, no key)
- **Relay**: `https://marcopolo-relay.onrender.com` (WebSocket relay for room/location sharing)
- **Routing**: OSRM walking routes (free, no key)
- **Target**: Android 10+ (Honor 20 tested)

## Key Files
- `MarcoViewModel.kt` — room creation, GPS collection, partner location handler, route calc (pre-reveal OSRM via `rawPartnerLat/Lng`, `force` param bypasses debounce)
- `PoloViewModel.kt` — room join, GPS collection, partner location handler, route caching (`pendingWalkRoute` promoted on reveal transition)
- `MarcoScreen.kt` — Marco UI with three-state (waiting room / loading / map) + debug overlay
- `PoloMapScreen.kt` — Polo UI same three-state + debug overlay
- `MarcoMap.kt` — osmdroid wrapper with markers (You 36dp, partner 36dp), polylines (fg 10px, bg 22px), follow-me, single-call zoomToBoundingBox with conditional padding
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
- Reveal controls: marker visibility + route display (not raw coordinate storage)
- `rawPartnerLat/Lng` (Marco) always stored on receive, used for pre-reveal OSRM calls
- `pendingWalkRoute` (Polo) caches received routes when partner not revealed, promoted to `walkRoute` on reveal transition
