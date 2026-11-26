# Tellyport

Tellyport is a Kotlin Android / Android TV application (targeting Google TV Streamer on Android 14 / API 34, `minSdk = 28`) built with Jetpack Compose and Compose for TV (`androidx.tv:tv-material`). It coordinates VPN/Tailscale connections, Android mock location injection, and automatic launching of regional streaming apps.

## Architecture

- **`tv.tellyport.data`**: `RegionalProfile`, `VpnBackend` (`TAILSCALE`, `WG_TUNNEL`, `NONE`), `ProfilePresets`, and `ProfileRepository` persisted via Jetpack DataStore with JSON serialization (`kotlinx.serialization`). Pre-seeded with editable US (New York), UK (London), and AU (Sydney) profiles.
- **`tv.tellyport.location`**: `LocationPayloadFactory` (fresh epoch + `elapsedRealtimeNanos` timestamps and subtle < 2m realistic jitter), `MockLocationPermissionChecker` (`AppOpsManager` check, Developer Options deep link, and ADB one-liner), and `MockLocationState`.
- **`tv.tellyport.service`**: `MockLocationService` (`foregroundServiceType="location"`) registering both `GPS_PROVIDER` and `NETWORK_PROVIDER` test providers (API 31+ `ProviderProperties` and API 28–30 fallback), pushing fixes every 1000ms, displaying a persistent notification with a "Stop" action, and removing providers in `onDestroy()`.
- **`tv.tellyport.vpn`**:
  - `VpnController`: explicit Tailscale broadcasts (`com.tailscale.ipn.IPNReceiver`: `CONNECT_VPN` / `DISCONNECT_VPN`), authenticated WG Tunnel broadcasts (`com.zaneschepke.wireguardautotunnel.core.broadcast.RemoteControlReceiver`: `START_TUNNEL` / `STOP_TUNNEL` with `key` and `tunnelName` extras), and fallback Tailscale / WG Tunnel launchers.
  - `VpnNetworkMonitor`: `ConnectivityManager.NetworkCallback` observing `NetworkCapabilities.TRANSPORT_VPN` along with network handle and owner UID detection (API 30+).
  - `ProfileActivationCoordinator`: coordinates teardown of conflicting VPN connections -> triggers VPN connect -> starts `MockLocationService` -> waits up to 8s for the requested VPN to become ready (or proceeds immediately if `NONE`) -> launches `targetAppPackage` if `autoLaunchApp` is enabled.
- **`tv.tellyport.ui`**: D-Pad-first dark-mode TV dashboard (`DashboardScreen`), `DiagnosticsScreen`, and `ProfileEditorScreen` (with location & streaming app presets).

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
2. Grant mock location authorization (either via **Settings → System → Developer Options → Select mock location app → Tellyport**, or via ADB):
   ```bash
   adb shell appops set tv.tellyport android:mock_location allow
   ```
3. Grant runtime location permission when prompted (or via ADB):
   ```bash
   adb shell pm grant tv.tellyport android.permission.ACCESS_FINE_LOCATION
   ```
4. *(If using WG Tunnel)* Enable **Remote App Control** in WG Tunnel → Settings, copy the generated key, and save it in Tellyport's **Setup & Diagnostics** screen (or while editing a WG Tunnel profile).
