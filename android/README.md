# Privacy Warden — Android App

Kotlin Android client. Captures TLS metadata (SNI), streams to your Tank, applies signed blocking rules.

## Build

Requires Android Studio Hedgehog (2023.1) or newer with Kotlin 2.0 plugin.

```bash
# from project root
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Configure the Tank endpoint

Before building, edit `app/build.gradle.kts`:

- `TANK_WS_URL` — the WebSocket URL of your Tank (e.g. `wss://tank.example.com/ws/phone`).
  For Android emulator hitting host machine: `ws://10.0.2.2:8443/ws/phone`.
- `TANK_PUBLIC_KEY_B64` — paste the output of `python -m app.crypto.keygen` from the Tank.
  This pins the key so MITM attackers cannot forge rules.

## Notes

- Min SDK 26 (Android 8.0).
- Uses standard `VpnService` — no root required.
- Foreground service with persistent notification (Android 13+ requirement).
- Production work TODO: per-app UID attribution via `getConnectionOwnerUid`,
  TCP stream reassembly, IPv6, OEM-specific battery whitelist deep links.
