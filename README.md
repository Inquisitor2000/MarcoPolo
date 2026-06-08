# FindMe (Marco Polo)

Real-time location sharing for two people to find each other in crowds. One creates a session (Marco), the other joins with a 4-character code (Polo). Both see each other on a map with a walking route and a 15-minute countdown.

## Features

- **Real-time map** — osmdroid with free CartoDB tiles (no API key)
- **Turn-by-turn nav** — OSRM walking/car routes with street names and arrows
- **Two roles** — Marco creates session → Polo joins via code
- **Routing modes** — foot (pedestrian paths) or car (streets)
- **Multi-language** — English, Romanian, Russian
- **F-Droid ready** — no Google Play Services dependency
- **15-min countdown** — auto-resets on location update

## Screenshots

*(screenshots pending)*

## Architecture

```
Android App (Kotlin/Compose)
  ├─ Marco → creates session, shares code
  ├─ Polo → joins via code
  └─ Both → WebSocket ↔ Relay Server
                        │
                   marcopolo-relay.onrender.com
                        │
                OSRM routing API (free)
```

### Stack

| Layer | Tech |
|-------|------|
| UI | Jetpack Compose + Material 3 |
| Maps | osmdroid + CartoDB tiles |
| Routing | OSRM (free, no key) |
| Relay | Node.js WebSocket server |
| Location | Android LocationManager |
| Navigation | Jetpack Navigation Compose |
| Min SDK | Android 10 (API 29) |
| Target | Android 16 (API 36) |

## Quick Start

### Android App

```bash
git clone https://github.com/Inquisitor2000/marcopolo-relay.git
cd marcopolo-relay/android
./gradlew assembleDebug
```

Install `android/app/build/outputs/apk/debug/app-debug.apk` on device.

### Relay Server (self-host)

```bash
cd server
npm ci
npm start
```

Default `ws://localhost:3000`. App defaults to `marcopolo-relay.onrender.com`.

### Render Deploy

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/Inquisitor2000/marcopolo-relay)

## Project Structure

```
├── android/          # Android app (Kotlin + Compose)
│   ├── app/src/main/java/com/marcopolo/
│   │   ├── ui/       # Screens & components
│   │   ├── viewmodel/# State & logic
│   │   ├── util/     # LocaleManager, etc.
│   │   └── ...       # RelayClient, RouteFinder, LocationService
│   └── app/src/main/res/ # Strings (EN/RO/RU), icons
├── server/           # WebSocket relay server
│   ├── server.js
│   └── DEPLOY.md
├── render.yaml       # Render.com deployment config
└── package.json
```

## License

GNU General Public License v3.0 or later. See [LICENSE](LICENSE).

The relay server uses MIT-licensed dependencies (Node.js, ws). The Android app links GPL-3.0 libraries (osmdroid) — see each project's license.
