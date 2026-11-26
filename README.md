# Tellyport (`tv.tellyport`)

**Watch TV anywhere.** Tellyport is a zero-touch Kotlin Android & Android TV application (targeting Google TV Streamer on Android 14 / API 34, `minSdk = 28`) built with Jetpack Compose and Compose for TV (`androidx.tv:tv-material`).

When anyone in the household opens a mapped streaming app from the standard Google TV home screen, Tellyport silently intercepts the launch, force-stops the app (via Shizuku), switches VPN + mock GPS to the matching regional profile (**New York**, **London**, or **Sydney**), and relaunches the app cleanly—plus recovers Wireless Debugging & Shizuku automatically after device reboots.

## Architecture

- **`tv.tellyport.data`**: `RegionalProfile`, `VpnBackend` (`UNCONFIGURED`, `TAILSCALE`, `WG_TUNNEL`, `SOCKS_WRAPPER`, `NONE`), agnostic pre-seeded profiles (**New York**, **London**, **Sydney**) with default app mappings, single-Tailscale repository constraint, and Jetpack DataStore persistence for profiles, `ActiveVpnSession`, Home Auto-Disconnect settings, WG Tunnel key, and boot recovery diagnostics.
- **`tv.tellyport.vpn`**:
  - `VpnBackendHeuristic` & `VpnNetworkMonitor`: observes `ConnectivityManager.NetworkCallback` (`TRANSPORT_VPN`) and inspects `LinkProperties` (`100.64.0.0/10` IPv4 or `100.100.100.100` MagicDNS) to infer Tailscale without relying on redacted Android 14 `ownerUid`.
  - `VpnController`: explicit Tailscale intents (`com.tailscale.ipn.IPNReceiver`), authenticated WG Tunnel intents (`RemoteControlReceiver`: `START_TUNNEL` / `STOP_TUNNEL` with `key` and `tunnelName`), and `SOCKS_WRAPPER` intents.
  - `ProfileActivationCoordinator`: combines live `ConnectivityManager` state, persisted `ActiveVpnSession`, and `LinkProperties` heuristic for fast-path acceptance, dual-backend conflict teardown (4s `onLost` wait), and 8s connect + 500ms stabilization + clean relaunch (`FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK`).
- **`tv.tellyport.accessibility`**: `AppLaunchAccessibilityService` (`TYPE_WINDOW_STATE_CHANGED` only, `canRetrieveWindowContent="false"`) and `AppLaunchInterceptionEngine` (filtering, mutex/debounce, Home Launcher auto-disconnect grace timer, `GLOBAL_ACTION_HOME` -> force-stop -> reconcile -> relaunch).
- **`tv.tellyport.shizuku` & `tv.tellyport.boot`**: `ShizukuForceStopper` (`IActivityManager.forceStopPackage` via Shizuku binder with `ActivityManager.killBackgroundProcesses` fallback), `BootRecoveryReceiver` (`BOOT_COMPLETED`), and `ShizukuBootHelper` (waits for Wi-Fi/Ethernet up to 60s, sets `adb_wifi_enabled=1`, checks/wakes Shizuku, records diagnostics).
- **`tv.tellyport.location` & `tv.tellyport.service`**: `MockLocationService` (`foregroundServiceType="location"`) pushing 1Hz `GPS_PROVIDER` + `NETWORK_PROVIDER` fixes with fresh timestamps and <2m jitter, process-scoped session IDs, and synchronized cleanup on `onDestroy()`.
- **`tv.tellyport.ui`**: D-Pad-first dark Google TV `DashboardScreen`, `DiagnosticsScreen`, and `ProfileEditorScreen` (with single-Tailscale UX guardrail and multi-app mapping picker).

See [ROADMAP.md](ROADMAP.md) for planned future enhancements including native `libtailscale` (`gomobile` `.aar`) evaluation.

## Build & Test

```bash
./gradlew testDebugUnitTest assembleDebug
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`

## One-Time Google TV Streamer Setup (Android 14 / API 34)

```bash
# 1. Install Tellyport
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Grant Mock Location AppOps
adb shell appops set tv.tellyport android:mock_location allow

# 3. Grant Runtime Location Permission
adb shell pm grant tv.tellyport android.permission.ACCESS_FINE_LOCATION

# 4. Enable Accessibility App Launch Interceptor
adb shell settings put secure enabled_accessibility_services tv.tellyport/tv.tellyport.accessibility.AppLaunchAccessibilityService
adb shell settings put secure accessibility_enabled 1

# 5. Grant WRITE_SECURE_SETTINGS for Automatic Post-Reboot Wireless Debugging Recovery
adb shell pm grant tv.tellyport android.permission.WRITE_SECURE_SETTINGS
```

6. Open **Tellyport → Setup & Diagnostics** to authorize **Shizuku**, configure your **WG Tunnel Remote Control Key** (if using WG Tunnel), and assign a VPN backend to each city profile.
