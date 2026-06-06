# Marco Polo — Session Memory

## Gotchas
- **mapReady circular deadlock** (FIXED). `partnerLat` set null when dist ≤ 10m (not revealed). Old check `partnerLat != null` → map never rendered when partner close. Fix: `hasPartnerLocation` boolean tracks any received partner data, independent of reveal.
- **GPS acquisition delay** (FIXED). `ownLat` null until GPS fix → mapReady false → stuck on loading. Fix: `getLastLocation()` in LocationService for cached location + `mapReady = isActive && (ownLat != null || hasPartnerLocation)` (doesn't block on own GPS).
- **Route delivered but dropped** (FIXED). Marco sends route → Polo's GPS shows <10m (not revealed) → route discarded silently. Polo then waits for next Marco recalc cycle (5-15s). Fix: `pendingWalkRoute` caches route on Polo, promotes instantly on reveal transition.
- **Polo calculates routes independently** (FIXED). Polo no longer waits for Marco to send route over WebSocket — both sides call OSRM directly. `PoloUiState` has `rawPartnerLat/Lng` always stored on receive, same `requestRouteUpdate()` with debounce as Marco.
- **bboxDone single-shot zoom** (FIXED). Map only zoomed to fit once on first render. Walking further from partner → marker off-screen permanently. Fix: dynamic re-zoom when `followMe` is on and diagonal changes > 30m (`MIN_BBOX_CHANGE`). `minZoomLevel=15.0` caps max zoom-out.
- **Route path flipping between alternatives** (FIXED). OSRM returned slightly different paths on each recalculation due to GPS coordinate jitter, causing the route polyline to visually jump between near-equivalent routes. Fix: `displayedRouteOwnLat/Lng` + `displayedRoutePartnerLat/Lng` track positions used for the displayed route. Accept new route when positions changed >30m (even if longer). Apply "strictly shorter" filter only for same-position GPS jitter. Applied to both ViewModels' OSRM acceptance + Polo's WS route handler.
- **Route not updating when positions change** (FIXED). Anti-flip filter rejected longer routes even when positions actually changed (Marco moves further → new route longer → green line frozen at old route). Fix: position-aware acceptance — `displayedRoute*` fields in both ViewModels, accept when positions moved >30m regardless of distance.
- **POST_NOTIFICATIONS on Android 10** (FIXED). Permission `POST_NOTIFICATIONS` (API 33+) requested unconditionally on Android 10 (API 29) where it doesn't exist. Fix: guarded behind `Build.VERSION.SDK_INT >= TIRAMISU` in both screens.
- **zoomToBoundingBox in update lambda freeze** (FIXED). Called during Compose layout phase before MapView has dimensions → potentially invalid calculation + main thread block on slower hardware. Fix: moved to `LaunchedEffect(bboxZoomTrigger)` that runs post-layout. Further crash fix: non-animated zoom + width/height guard + try-catch for Mali-G76 software layer.
- **Honor 20 freeze + crash** (FIXED). Three separate issues:
  - **Freeze**: Mali-G76 GPU driver hang on osmdroid Canvas tile rendering. Fix: `setLayerType(LAYER_TYPE_SOFTWARE, null)`.
  - **CPU overload**: compass orientation animation storm (~16Hz) + BitmapDrawable allocation per update frame overloaded CPU. Fix: bearing rounded to 5° (LaunchedEffect restarts ~1-2Hz), orientation animation delayed 2s (avoids startup burst), BitmapDrawable cached.
  - **Crash on route arrival**: `zoomToBoundingBox(animated=true)` crashed Mali-G76 software layer when route arrived mid-initialization. Fix: non-animated zoom + width/height guard + try-catch.
- **OSRM only after reveal** (FIXED). Marco waited for `partnerLat != null` (revealed) before calling OSRM API (1-3s latency). Fix: `rawPartnerLat/Lng` fields store raw coords always, OSRM starts pre-reveal. `force` parameter bypasses 10s debounce on reveal.
- **GPS asymmetry** (KNOWN). Marco and Polo get GPS fixes at different times. Marco may see 11m (revealed), Polo sees 9.5m (not revealed). Causes asymmetric reveal states. Polo's `pendingWalkRoute` cache bridges this window.
- **Found dialog race condition** (FIXED). `partner_disconnected` WebSocket message arriving during the found window would override the found state and show disconnect dialog instead. Fix: disconnect handler in both ViewModels checks `current.showFoundDialog` and returns unchanged if already found. Location handler clears `showDisconnectDialog` + `error` when found triggers.
- **1.5s grace delay** (FIXED). Both screens have `LaunchedEffect(Unit) { delay(1500); onFound() }` — gives both sides time to sync found state via WebSocket before navigating home and cleaning up. Without this, one side could navigate before the other processes the found message.
- **Premature found popup when starting close** (FIXED). If devices started ≤15m apart, distance was immediately ≤ FOUND_THRESHOLD_M → found dialog fired before users could walk towards each other. Fix: `foundDialogEnabledAtMs` in both UI states — set to `currentTimeMillis + 20s` when `partner_joined` activates the session. `nowFound` requires `System.currentTimeMillis() >= foundDialogEnabledAtMs`, giving GPS ~20s to settle before the found check activates.
- **Stale last-location cache on new session** (FIXED). After session end (found/disconnect), `stopService()` cleared `_currentLocation`. But next session's `fusedLocationClient.lastLocation` immediately returned the system's stale cached GPS. Fix: `LocationService.clearCache()` sets `skipLastLocation` flag — `onStartCommand` skips the stale fetch. Called from both ViewModels' `cleanup()`.
- **Green checkmark manual found** (FIXED). Added `CHECKMARK_THRESHOLD_M = 30` — green ✓ button appears at top of bottom-right control column when partner ≤30m but auto-found hasn't triggered. Tapping sends `session_complete` via WebSocket; both sides show congratulations dialog.
- **Nav instruction simplified** (CHANGED). Removed compass-relative direction ("Continue"/"Turn left"/"Turn back") — unreliable phone compass. Now uses map-aligned cardinal arrow (↑ NE ↗ → etc.) or turn-by-turn step instruction when on-route. No `ownBearing` dependency.
- **Google Maps beam marker** (ADDED). Replaced internal arrow chevron with conical blue beam (pie-slice sector). Beam width varies with compass accuracy: narrow at SENSOR_STATUS_ACCURACY_HIGH (18°), wide at UNRELIABLE (80°). GPS accuracy shown as translucent blue Polygon circle on map (48-point approximation). `createYouBitmap()` → `createYouBeamBitmap(compassAccuracy)`.
- **Distance box removed** (CHANGED). Standalone distance box at `bottom=28.dp` removed — distance already shown in nav instruction card. Nav card moved to `bottom=28.dp` with `wrapContentWidth()` (narrower).
- **Header overlay respects status bar** (FIXED). Top header overlay overlapped system status bar. Fix: `statusBarsPadding()` on header overlay and error panel Box in map-ready state. Replaced `.padding(top = 60.dp)` hack in both screens.
- **Map rotation 0/360 spin** (FIXED). `targetOrientation = -(round(smoothBearing/5)*5)` wraps from -360 to 0 when bearing crosses 359°↔1°. `animateFloatAsState` animates the full ±360 numeric difference → visible full-screen spin. Fix: continuous de-cycled target via shortest-angle diff accumulation, normalized to [0, 360) in update block. Also replaced `LaunchedEffect` with synchronous `remember` for EMA to eliminate coroutine restart overhead per sensor frame.
- **Control buttons enlarged** (CHANGED). All bottom-right control buttons (checkmark, follow-me, zoom +, zoom -) increased from 40dp→48dp. Icons scaled proportionally. Zoom text 20sp→24sp.
- **Debug overlay removed** (REMOVED). `DebugOverlay.kt` deleted. All references removed from MarcoScreen.kt and PoloMapScreen.kt (imports, state, clickable toggles, composable calls).
- **Status bar scrim** (FIXED). `window.statusBarColor` ignored on API 35+. Fix: Compose-level `Box(TopCenter, fillMaxWidth, windowInsetsTopHeight(WindowInsets.statusBars), background(0xDD000000))` in map-ready state of both screens.
- **Disconnect overlay** (CHANGED). Replaced Android `Dialog` (semi-transparent scrim) with full-screen `Box(fillMaxSize, background(theme.background), clickable/indication=null)` — fully opaque, theme-aware, no window layer.
- **Congratulations overlay** (CHANGED). Same full-screen `Box` style as disconnect overlay. Uses `surface`/`onSurface` instead of `primaryContainer`/`onPrimaryContainer` to avoid purple tint from default Material3 colors.
- **Theme simplified** (CHANGED). Single theme, no light/dark switching. `background`/`surface` = `#1C1C1C`. Removed `LightColors`, `isSystemInDarkTheme`, `lightColorScheme`.
- **Found dialog race — second guard** (ADDED). `partner_disconnected` handler now checks `partnerDistance <= FOUND_THRESHOLD_M` as fallback — if partner was within 15m when they left, treat as found instead of disconnect. Covers race where partner navigates home before the other side processes its own location update that would trigger found.
- **Polo room code input** (CHANGED). `RoundedCornerShape(12.dp)` on `OutlinedTextField` to match other UI elements.
- **Route crossfade removed** (REMOVED). Had sequential fade-out-then-fade-in on route geometry change. Caused chaotic animation because crossfade triggered on every GPS coordinate update (routeKey included ownLat/Lng). Reverted to simple instant swap. Only remaining animation is `routeAlpha` animateFloatAsState (600ms, reveal/hide only, triggered by `partnerLat != null && ownLat != null` transition).
- **LSP false positives**: All `UNRESOLVED_REFERENCE` for Android/Kotlin SDK types are from missing Android SDK indexing, not real compile errors.
- **Map not centered on own position on first render** (FIXED). `bboxZoomTrigger` → `zoomToBoundingBox()` override the center set by `followMe`. Sequence was: update lambda `setCenter(ownLat,ownLng)` → bboxZoom LaunchedEffect `zoomToBoundingBox()` shifts center to midpoint between both markers. Fix: added `mv.controller.setCenter(GeoPoint(oLat, oLng))` in the LaunchedEffect after `zoomToBoundingBox`, so zoom level fits both markers but center stays on user.
- **Follow-me always-on** (CHANGED). Removed follow-me toggle button and pan-detection touch listener. `followMe` now `val followMe = true`. Touch listener consumes all events (`return true`) to prevent map panning. Controls column only has checkmark + zoom buttons.
- **Routing mode toggle** (CHANGED). Custom pill toggle (80×32dp, rounded 16dp) with walk/car icons on dark circular backdrops (26dp, always on top of the sliding thumb). Thumb is a 40dp pill sliding with spring animation behind icons. Inverted logic: footpath = thumb left, street = thumb right. Each device persists choice via SharedPreferences (`routing_footpath`, default true). `setRoutingMode()` saves + calls `requestRouteUpdate(force=true)`.
- **Permission compliance** (CHANGED). Removed `POST_NOTIFICATIONS` entirely (no longer requested). Removed `ACCESS_COARSE_LOCATION` — only precise `ACCESS_FINE_LOCATION` is requested. Permission gate screens styled to match dark theme (full-screen `#1C1C1C` background, pill-shaped buttons).
- **Permission screen flash on cleanup** (FIXED). `cleanup()` reset `_uiState` with default `permissionsReady = false`, causing 1-2 frame flash of permission gate when showing disconnect dialog. Fix: `MarcoUiState(permissionsReady = true)` / `PoloUiState(permissionsReady = true)` on cleanup.
- **Disconnect dialog room-code flash** (FIXED). OK button in disconnect dialog called `cleanup()` which reset UI state to default (room code screen). For 1-2 frames before `popBackStack` navigated home, user saw room-code screen. Fix: OK button calls `onBack()` (nav pop) first; cleanup deferred to `ViewModel.onCleared()` which fires when nav entry is popped. Applied to both MarcoScreen and PoloMapScreen.
- **Routing mode toggle layout shift** (FIXED). Countdown timer `formatCountdown()` text changed pixel width each tick (proportional font → different digit widths). The `weight(1f)` Box holding the routing toggle in the header Row absorbed the slack, shifting the toggle every second. Fix: wrap countdown in `Box(width=80.dp)` + `FontFamily.Monospace` for constant width.
- **AndroidView ctx scope** (KNOWN). `ctx` from `factory = { ctx -> }` is NOT available in `update = { view -> }`. Use `view.context` in update block or do one-time work in factory.

## Architecture
- **No cloud accounts/API keys** — osmdroid + CartoDB Voyager tiles (free, no key)
- **Multi-language**: EN/RO/RU runtime switching. `LocaleManager` persists choice to SharedPreferences. `MainActivity.attachBaseContext()` wraps Context with saved locale. `LanguageSwitcher` (circular green border, top-right of home screen, no fill, cycles EN→RO→RU→EN) triggers `activity.recreate()` on tap. All user-facing strings in `values/strings.xml` (EN), `values-ro/` (RO), `values-ru/` (RU). Russian uses Cyrillic (Марко Поло, Марко, Поло). Nav instruction strings in resources but `computeNavInstruction()` still uses hardcoded English (needs context param).
- **Relay**: `https://marcopolo-relay.onrender.com` (WebSocket relay for room/location sharing)
- **Server thinness**: ~80 lines of logic. Pure dumb pipe — no OSRM, no persistence, no auth. In-memory Map, 17min TTL cleanup. ~0.8 MB per 15-min session (bidirectional). Free tier handles ~4000 MAU / 200 peak concurrent rooms before bandwidth becomes first limit (~100 GB/mo).
- **Routing**: Two OSRM modes toggleable per-device via header Switch. Both modes free, no key:
  - **Footpath** (default): `routing.openstreetmap.de/routed-foot` — OSRM compiled with footpath data, better pedestrian coverage.
  - **Main St**: `router.project-osrm.org` — standard OSRM foot profile, road-oriented.
  Both Marco and Polo call OSRM independently. `steps=true` returns turn-by-turn maneuvers. Step-finding via point-to-polyline projection in MarcoMap.kt; falls back to cardinal direction when off-route (>60m) or steps unavailable.
- **Found dialog**: Shared `mutableStateOf(false)` in `MarcoPoloNavGraph` (MainActivity.kt). Game screens call `onFound()` → sets flag + `popBackStack("home")`. HomeScreen renders congratulation full-screen `Box` overlay on top. "Awesome!" calls `onDismissFound()` to clear flag.
- **Distance haptics**: `VIRTUAL_KEY` haptic fires once per milestone (1000, 500, 250, 125, 75, 50 m) when crossing closer. Tracked in `remember { mutableSetOf<Int>() }`. Found dialog gets `CONFIRM` haptic.
- **Crossfade transitions**: `Crossfade(tween(400))` between room/loading/map states. Route polyline alpha animated via `animateFloatAsState(tween(600))` (reveal/hide only, no crossfade on mode switch).
- **Target**: Android 10+ (Honor 20 tested)

## Key Files
- `MarcoViewModel.kt` — room creation, GPS collection, partner location handler, route calc (pre-reveal OSRM via `rawPartnerLat/Lng`, `force` param bypasses debounce). `partner_disconnected` handler checks `showFoundDialog` first, then falls back to `partnerDistance <= FOUND_THRESHOLD_M` to bridge found race. Location handler clears `showDisconnectDialog` + `error` on found.
- `PoloViewModel.kt` — room join, GPS collection, partner location handler, route caching (`pendingWalkRoute` promoted on reveal transition). Same pre-reveal OSRM, `requestRouteUpdate()` with debounce as Marco. Same `partner_disconnected` + location fixes as MarcoViewModel.
- `MarcoScreen.kt` — Marco UI with full-height map (persistent dark header overlay) when mapReady, else Scaffold with TopAppBar for room/loading states. Crossfade three-state, distance threshold haptic, `LaunchedEffect { delay(1500); onFound() }` (no inline found dialog).
- `PoloMapScreen.kt` — Polo UI same full-height map pattern, header overlay, Crossfade + haptic + found-dialog delay as MarcoScreen.
- `MarcoMap.kt` — osmdroid wrapper with markers (You 36dp, partner 36dp), polylines (fg 10px, bg 22px, ROUND joins), follow-me always-on (no toggle, panning blocked), non-animated zoomToBoundingBox with post-zoom re-center on own position, route polyline alpha animation (600ms tween, reveal/hide only, no crossfade), maxZoomLevel 19, minZoomLevel 15, bearing rounded to 2°, orientation delayed 2s, BitmapDrawable cached. Nav instruction card: turn-by-turn steps or cardinal fallback. Control buttons: checkmark + zoom +/- only (48dp, Box+CircleShape+clickable, no FloatingActionButton). Controls column bottom=28dp aligned with nav card.
- `RouteFinder.kt` — OSRM client for both modes. `findRoute()` takes `useFootpath: Boolean` to select server. Parses routes with `steps=true`, returns `RouteResult(geometry, distance, duration, steps)` where steps are `List<RouteStep>` with instruction, distance, geometry, modifier. `generateInstruction()` builds human-readable text from OSRM maneuver type/modifier/name.
- Both ViewModels have `useFootpath: StateFlow<Boolean>` backed by SharedPreferences. `setRoutingMode(footpath)` saves to prefs and triggers `requestRouteUpdate(force=true)`.
- `LocationService.kt` — foreground service, FusedLocationProviderClient, compass via rotation sensor, `getLastLocation()` fallback, GPS 3s min interval, compass SENSOR_DELAY_NORMAL (200ms).
- `PermissionsViewModel.kt` — PENDING/GRANTED/DENIED state machine for location permission
- `HapticClick.kt` — `hapticClick()` composable wrapper for all 15 interactive buttons
- `LocaleManager.kt` — SharedPreferences-backed locale persistence. `getSavedLocale()`, `setLocale()`, `updateLocale()`. Used by `MainActivity.attachBaseContext()` and `LanguageSwitcher`.
- `LanguageSwitcher.kt` — Circular 36dp green-bordered button at top-right of home screen. Shows current lang code (EN/RO/RU). Click cycles to next, calls `LocaleManager.setLocale()` + `activity.recreate()`. Haptic on tap.
- `HomeScreen.kt` — permission gate + role selection + found dialog rendering (full-screen Box overlay)
- `PoloConfigScreen.kt` — room code input with `OutlinedTextField`, "Connect" button, `RoundedCornerShape(12.dp)`
- `MainActivity.kt` — osmdroid init (cache 25MB, threads 2), deep link support, `MarcoPoloNavGraph` with shared `foundDialogShown` state + `onFound` lambda
- `RelayClient.kt` — OkHttp WebSocket, createRoom/connect/sendLocation/sendRoute/sendSessionComplete
- `server/server.js` — `generateCode()` → numeric-only `[0-9]{4}`, deep link regex, forwards `session_complete` to partner

## UI State Machine
Three states per screen (once permissions granted):
1. **Room code** — `!isActive`: show code + share button + "Waiting for partner..."
2. **Loading** — `isActive && !mapReady`: spinner with context-aware message (GPS vs partner wait)
3. **Map** — `mapReady`: MarcoMap with markers, route, controls

`mapReady = isActive && (ownLat != null || hasPartnerLocation)`

## Reveal / Found Logic
- `REVEAL_THRESHOLD_M = 10`: partner coords hidden until dist > 10m (privacy)
- `FOUND_THRESHOLD_M = 15`: "found partner" dialog triggers when dist ≤ 15m
- `CHECKMARK_THRESHOLD_M = 30`: green ✓ button appears at bottom-right when dist ≤ 30m but auto-found hasn't triggered
- `partnerLat/lng` stored as null when not revealed; `hasPartnerLocation` always set true on receive
- Reveal controls: marker visibility + route display (not raw coordinate storage)
- `rawPartnerLat/Lng` (both sides) always stored on receive, used for pre-reveal OSRM calls
- `pendingWalkRoute` (Polo) caches received routes when partner not revealed, promoted to `walkRoute` on reveal transition
- `foundDialogEnabledAtMs` set to `currentTimeMillis + 20s` on `partner_joined`. Found dialog suppressed until time elapses.
- **pointToPolylineDistance coordinate swap** (FIXED). `pointToPolylineDistance()` extracted segment endpoints as `(lng, lat)` while user position was `(userLat, userLng)` — mixed lat with lng in `pointToSegmentDistance`. Produced garbage distances. On simulator (perfect coordinates) relative ordering stayed accidentally correct. On real GPS (noisy), amplified jitter into wrong step selection, consistently showing next street. Fix: extract as `(lat, lng)` consistent with user position.

## Firebase Configuration
- **Dependencies**: Firebase BoM 34.14.0, Analytics, Crashlytics (`com.google.gms:google-services:4.4.4`, `com.google.firebase:firebase-crashlytics-gradle:3.0.7`)
- **Project**: `marco-polo-44ee3` (project number: 992041143673), package: `com.marcopolo`
- **Config file**: `android/app/google-services.json`
- **Gating**: All Firebase calls guarded with `if (!BuildConfig.DEBUG)` — release-only
- **Auto screen tracking**: Not disabled (negligible — 3 screens). We only log 6 custom events.
- **Custom events** (6 total, same on Marco + Polo):
  - `room_created` (Marco) / `room_joined` (Polo) — session start
  - `partner_connected` — WebSocket session active
  - `navigation_started` — partner revealed (distance >10m)
  - `connection_lost` — with `reason` param: `partner_disconnected` or `connection_error`
  - `session_complete` — found dialog shown
- **Crashlytics**: `recordException()` on OSRM failures, WS createRoom failures, WebSocket onFailure callbacks
- **ProGuard**: `-keepattributes SourceFile,LineNumberTable` for readable stack traces
- **BuildConfig**: `buildFeatures.buildConfig = true` must stay enabled (used for debug gating)
- **APK impact**: +1.4MB (4.4MB → 5.8MB after R8 tree-shaking)

## Future Features
- **GraphHopper for footpath mode**: Current footpath mode uses OSRM `routed-foot` instance. GraphHopper `foot` profile has dedicated pedestrian network with better path coverage (especially Eastern Europe). Would need API key (free tier: 500 req/day). Would replace the `routing.openstreetmap.de/routed-foot` URL in RouteFinder.kt when `useFootpath=true`. GraphHopper provides pre-formatted instruction text (no more `generateInstruction()`).
- **Server-side Firebase Analytics**: Server events from `server.js` on room created (+ code hash), fully packed, and session_complete forwarded.

## TODO
- **App icon**: Current icon is the default Android icon. Replace with branded Marco Polo logo.
