# OpenClaw Android App

Standalone APK for running OpenClaw on Android. Thin APK (~5MB) with WebView UI, native PTY terminal, and the Termux bootstrap runtime.

## Architecture

```
APK (~5MB)
├── Native: TerminalView (PTY terminal via libtermux.so)
├── WebView: React SPA (setup, dashboard, settings)
├── JsBridge: WebView ↔ Kotlin communication (31 methods, 7 domains)
├── EventBridge: Kotlin → WebView event dispatch
```

## Build

### Prerequisites

- JDK 21
- Android SDK (API 28+)
- NDK 28+
- Node.js 22+ (for WebView UI)

### Build APK

```bash
cd android
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Build WebView UI

```bash
cd android/www
npm install
npm run build        # Output: dist/
```

## Project Structure

```
android/
├── app/src/main/
│   ├── java/com/openclaw/android/
│   │   ├── MainActivity.kt           # WebView + TerminalView container
│   │   ├── OpenClawService.kt        # Foreground Service (START_STICKY)
│   │   ├── BootstrapManager.kt       # Bootstrap download/extract/configure
│   │   ├── JsBridge.kt               # 31 @JavascriptInterface methods
│   │   ├── EventBridge.kt            # Kotlin → WebView CustomEvent
│   │   ├── CommandRunner.kt          # Shell command execution
│   │   ├── EnvironmentBuilder.kt     # Termux environment variables
│   │   ├── BootstrapDownloader.kt    # Pinned-SHA-256 bootstrap download with mirrors
│   │   ├── BridgeGuard.kt            # Allow-list for commands from the web view
│   │   ├── ArtifactSecurity.kt       # Archive path validation
│   │   └── TerminalSessionManager.kt # Multi-session terminal management
│   ├── assets/www/                    # Bundled fallback UI (vanilla JS)
│   └── res/                           # Android resources
├── www/                               # React SPA (production WebView UI)
│   ├── src/
│   │   ├── lib/bridge.ts              # JsBridge typed wrapper
│   │   ├── lib/useNativeEvent.ts      # EventBridge React hook
│   │   ├── lib/router.tsx             # Hash-based router
│   │   └── screens/                   # All UI screens
│   └── dist/                          # Build output
├── terminal-emulator/                 # PTY emulator (from ReTerminal)
└── terminal-view/                     # Terminal rendering (from ReTerminal)
```

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| `targetSdk 28` | W^X bypass — allows exec in /data/data/ |
| `minSdk 24` | apt-android-7 bootstrap requirement |
| Hash routing | `file://` protocol doesn't support History API |
| No CSS framework | Minimal bundle size |
| System font stack | Android WebView, no custom font loading needed |

## JsBridge API Domains

| Domain | Methods | Description |
|--------|---------|-------------|
| Terminal | 7 | show/hide, create/switch/close sessions |
| Setup | 5 | bootstrap status, start setup, setup state, tool selections |
| Platform | 2 | read-only: available and active platform |
| Tools | 7 | install/uninstall/cancel CLI tools, installed state |
| Managed runs / Gateway | 6 | `startRun`, `cancelRun`, `getRunState`, `getLastRun` (`oa --update` as a child process), `getGatewayStatus`, `stopGateway` |
| Commands | 1 | allow-listed version probes (`runProbeAsync`) |
| Updates | 1 | app (APK) update check (`getApkUpdateInfoAsync`) |
| System | 8 | app info, battery, settings, storage |

## License

GPL v3
