# Warpmo

Warpmo is a Kotlin Android / Android TV application (targeting Google TV Streamer on Android 14 / API 34, `minSdk = 28`) built with Jetpack Compose and Compose for TV (`androidx.tv:tv-material`). It coordinates VPN/Tailscale connections, Android mock location injection, and automatic launching of regional streaming apps.

## Architecture

- **`com.warpmo.app.data`**: `RegionalProfile`, `VpnBackend` (`TAILSCALE`, `WG_TUNNEL`, `NONE`), `ProfilePresets`, and `ProfileRepository` persisted via Jetpack DataStore with JSON serialization (`kotlinx.serialization`). Pre-seeded with editable US (New York), UK (London), and AU (Sydney) profiles.
- **`com.warpmo.app.location`**: `LocationPayloadFactory` (fresh epoch + `elapsedRealtimeNanos` timestamps and subtle < 2m realistic jitter), `MockLocationPermissionChecker` (`AppOpsManager` check, Developer Options deep link, and ADB one-liner), and `MockLocationState`.
- **`com.warpmo.app.service`**: `MockLocationService` (`foregroundServiceType="location"`) registering both `GPS_PROVIDER` and `NETWORK_PROVIDER` test providers (API 31+ `ProviderProperties` and API 28–30 fallback), pushing fixes every 1000ms, displaying a persistent notification with a "Stop" action, and removing providers in `onDestroy()`.
- **`com.warpmo.app.vpn`**:
  - `VpnController`: explicit Tailscale broadcasts (`com.tailscale.ipn.IPNReceiver`: `CONNECT_VPN` / `DISCONNECT_VPN`), WG Tunnel broadcasts (`com.zaneschepke.wireguardautotunnel.action.CONNECT` / `DISCONNECT` with `tunnel_name`), and fallback Tailscale launcher.
  - `VpnNetworkMonitor`: `ConnectivityManager.NetworkCallback` observing `NetworkCapabilities.TRANSPORT_VPN`.
  - `ProfileActivationCoordinator`: triggers VPN connect -> starts `MockLocationService` -> waits up to 8s for `TRANSPORT_VPN` (or proceeds immediately if `NONE`) -> launches `targetAppPackage` if `autoLaunchApp` is enabled.
- **`com.warpmo.app.ui`**: D-Pad-first dark-mode TV dashboard (`DashboardScreen`), `DiagnosticsScreen`, and `ProfileEditorScreen` (with location & streaming app presets).

## Build & Test

```bash
./gradlew testDebugUnitTest assembleDebug
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Google TV Streamer Setup (Android 14 / API 34)

1. Install the APK over ADB:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. Grant mock location authorization (either via **Settings → System → Developer Options → Select mock location app → Warpmo**, or via ADB):
   ```bash
   adb shell appops set com.warpmo.app android:mock_location allow
   ```
3. Grant runtime location permission when prompted (or via ADB):
   ```bash
   adb shell pm grant com.warpmo.app android.permission.ACCESS_FINE_LOCATION
   ```
