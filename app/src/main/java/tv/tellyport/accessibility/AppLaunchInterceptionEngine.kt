package tv.tellyport.accessibility

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.tellyport.data.AutoDisconnectSettings
import tv.tellyport.data.ProfileRepository
import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.VpnBackend
import tv.tellyport.shizuku.PackageForceStopper
import tv.tellyport.vpn.ActivationProgress
import tv.tellyport.vpn.ProfileActivationCoordinator
import tv.tellyport.vpn.VpnController

/**
 * Outcome returned by [AppLaunchInterceptionEngine.handleWindowStateChanged] for observability
 * and deterministic unit testing.
 */
sealed class InterceptionOutcome {
    data object Ignored : InterceptionOutcome()
    data object AlreadySatisfied : InterceptionOutcome()
    data object Debounced : InterceptionOutcome()
    data object HomeGraceTimerScheduled : InterceptionOutcome()
    data class Intercepted(
        val packageName: String,
        val profileId: String,
        val progress: ActivationProgress
    ) : InterceptionOutcome()
}

/**
 * Pure, testable interception engine driven by [AppLaunchAccessibilityService].
 *
 * Strictly handles [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED], ignores Tellyport itself,
 * SystemUI/dialogs, keyboards, VPN apps, and stock TV launchers, manages the Home Launcher
 * auto-disconnect grace timer, and performs mutex-protected debounced interception for mapped
 * streaming apps.
 */
class AppLaunchInterceptionEngine(
    private val scope: CoroutineScope,
    private val profilesProvider: suspend () -> List<RegionalProfile>,
    private val autoDisconnectSettingsProvider: suspend () -> AutoDisconnectSettings,
    private val hasActiveSessionOrVpn: suspend () -> Boolean,
    private val coordinator: ProfileActivationCoordinator,
    private val forceStopper: PackageForceStopper,
    private val performGlobalHomeAction: () -> Boolean,
    private val showToast: (String) -> Unit = {},
    private val clockElapsedMs: () -> Long = { System.currentTimeMillis() },
    private val relaunchDebounceMs: Long = DEFAULT_RELAUNCH_DEBOUNCE_MS,
    private val additionalLauncherPackages: Set<String> = emptySet()
) {
    private val interceptionMutex = Mutex()
    private val recentRelaunchTimestamps = mutableMapOf<String, Long>()
    private var homeDisconnectJob: Job? = null

    @Volatile
    private var lastForegroundPackage: String? = null

    private val _lastInterceptedSummary = MutableStateFlow<String?>(null)
    val lastInterceptedSummary: StateFlow<String?> = _lastInterceptedSummary.asStateFlow()

    /**
     * Entry point called from [AppLaunchAccessibilityService.onAccessibilityEvent].
     */
    suspend fun handleEvent(eventType: Int, packageName: String?): InterceptionOutcome {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return InterceptionOutcome.Ignored
        }
        return handleWindowStateChanged(packageName)
    }

    suspend fun handleWindowStateChanged(rawPackageName: String?): InterceptionOutcome {
        val packageName = rawPackageName?.trim().orEmpty()
        if (packageName.isEmpty()) return InterceptionOutcome.Ignored

        // Ignore transient system overlays (SystemUI, volume/permission dialogs, keyboards)
        // without cancelling the Home auto-disconnect timer or changing lastForegroundPackage.
        if (packageName in TRANSIENT_OVERLAY_PACKAGES) {
            return InterceptionOutcome.Ignored
        }

        // Handle returning to the Google TV / Android TV Home Launcher.
        if (isHomeLauncher(packageName)) {
            lastForegroundPackage = packageName
            scheduleHomeAutoDisconnectIfEnabled()
            return InterceptionOutcome.HomeGraceTimerScheduled
        }

        // User entered a real foreground app; cancel any pending Home auto-disconnect timer.
        cancelHomeAutoDisconnectTimer()

        // Exclude Tellyport itself, VPN utilities, Shizuku, and Settings from interception
        // after cancelling the Home timer so editing profiles or managing VPNs isn't interrupted.
        if (packageName in NON_INTERCEPTED_FOREGROUND_APPS) {
            lastForegroundPackage = packageName
            return InterceptionOutcome.Ignored
        }

        val previousPackage = lastForegroundPackage
        val profiles = profilesProvider()
        val matchedProfile = ProfileRepository.findProfileForPackage(profiles, packageName)
        if (matchedProfile == null) {
            lastForegroundPackage = packageName
            return InterceptionOutcome.Ignored
        }

        // Unconfigured profiles cannot be auto-switched; allow normal launch rather than blocking.
        if (matchedProfile.vpnBackend == VpnBackend.UNCONFIGURED) {
            lastForegroundPackage = packageName
            return InterceptionOutcome.Ignored
        }

        // Debounce re-interception right after Tellyport itself relaunched this package.
        val now = clockElapsedMs()
        val lastRelaunch = synchronized(recentRelaunchTimestamps) {
            recentRelaunchTimestamps[packageName]
        }
        if (lastRelaunch != null && (now - lastRelaunch) < relaunchDebounceMs) {
            lastForegroundPackage = packageName
            return InterceptionOutcome.Debounced
        }

        // Serialize concurrent window-state events with a mutex.
        if (!interceptionMutex.tryLock()) {
            return InterceptionOutcome.Debounced
        }

        return try {
            // Double-check debounce & satisfaction inside the lock.
            val lockedNow = clockElapsedMs()
            val lockedLastRelaunch = synchronized(recentRelaunchTimestamps) {
                recentRelaunchTimestamps[packageName]
            }
            if (lockedLastRelaunch != null && (lockedNow - lockedLastRelaunch) < relaunchDebounceMs) {
                lastForegroundPackage = packageName
                return InterceptionOutcome.Debounced
            }

            if (coordinator.isProfileCurrentlySatisfied(matchedProfile)) {
                lastForegroundPackage = packageName
                if (previousPackage != packageName) {
                    showToast("Already connected to ${matchedProfile.name}")
                }
                return InterceptionOutcome.AlreadySatisfied
            }

            // Notify user on screen before returning home & switching regions.
            showToast("Connecting to ${matchedProfile.name}...")

            // 1. Return to Home so the app does not render in the wrong region.
            performGlobalHomeAction()

            // 2. Force-stop target app (Shizuku IActivityManager.forceStopPackage -> fallback killBackgroundProcesses).
            forceStopper.forceStop(packageName)

            // 3. Record debounce timestamp BEFORE relaunch so our own relaunch event is ignored.
            synchronized(recentRelaunchTimestamps) {
                recentRelaunchTimestamps[packageName] = clockElapsedMs()
            }

            // 4. Reconcile VPN + start/update MockLocationService + cleanly relaunch with CLEAR_TASK.
            val progress = coordinator.activate(
                profile = matchedProfile,
                targetPackageOverride = packageName,
                clearTaskOnLaunch = true
            )

            // Refresh debounce timestamp after activation finishes so immediate window transitions are debounced.
            synchronized(recentRelaunchTimestamps) {
                recentRelaunchTimestamps[packageName] = clockElapsedMs()
            }

            if (progress is ActivationProgress.Warning) {
                showToast(progress.message)
                lastForegroundPackage = null
            } else {
                lastForegroundPackage = packageName
            }

            _lastInterceptedSummary.value = "Intercepted $packageName → ${matchedProfile.name}"
            InterceptionOutcome.Intercepted(
                packageName = packageName,
                profileId = matchedProfile.id,
                progress = progress
            )
        } finally {
            interceptionMutex.unlock()
        }
    }

    fun cancelHomeAutoDisconnectTimer() {
        homeDisconnectJob?.cancel()
        homeDisconnectJob = null
    }

    private suspend fun scheduleHomeAutoDisconnectIfEnabled() {
        val settings = autoDisconnectSettingsProvider()
        if (!settings.enabled) {
            cancelHomeAutoDisconnectTimer()
            return
        }
        if (!hasActiveSessionOrVpn()) {
            cancelHomeAutoDisconnectTimer()
            return
        }
        if (homeDisconnectJob?.isActive == true) {
            // Grace timer already running while user remains on the Home Launcher.
            return
        }
        val delayMs = settings.gracePeriodSeconds.coerceAtLeast(1) * 1_000L
        homeDisconnectJob = scope.launch {
            delay(delayMs)
            if (hasActiveSessionOrVpn()) {
                coordinator.deactivateAll()
                _lastInterceptedSummary.value = "Auto-disconnected after ${settings.gracePeriodSeconds}s on Home Launcher"
            }
        }
    }

    private fun isHomeLauncher(packageName: String): Boolean {
        return packageName in DEFAULT_LAUNCHER_PACKAGES || packageName in additionalLauncherPackages
    }

    companion object {
        const val OWN_PACKAGE = "tv.tellyport"
        const val DEFAULT_RELAUNCH_DEBOUNCE_MS = 4_000L

        val DEFAULT_LAUNCHER_PACKAGES: Set<String> = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"
        )

        val TRANSIENT_OVERLAY_PACKAGES: Set<String> = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.google.android.inputmethod.latin",
            "com.google.android.inputmethod.leanback",
            "com.android.inputmethod.latin"
        )

        val NON_INTERCEPTED_FOREGROUND_APPS: Set<String> = setOf(
            OWN_PACKAGE,
            "com.android.settings",
            "com.android.tv.settings",
            VpnController.TAILSCALE_PACKAGE,
            VpnController.WG_TUNNEL_PACKAGE,
            VpnController.SOCKS_WRAPPER_PACKAGE,
            VpnController.SHIZUKU_PACKAGE
        )
    }
}
