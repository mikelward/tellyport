package tv.tellyport.accessibility

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
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
import java.util.concurrent.atomic.AtomicLong

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
 * streaming apps. If the user opens a distinct mapped app while an earlier interception is still
 * waiting on VPN/mock-location, the stale interception is superseded so the latest app wins.
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
    // Monotonic: a wall-clock jump (e.g. NTP sync after boot) must not suppress interception.
    private val clockElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val relaunchDebounceMs: Long = DEFAULT_RELAUNCH_DEBOUNCE_MS,
    private val additionalLauncherPackages: Set<String> = emptySet()
) {
    private val interceptionMutex = Mutex()
    private val flightLock = Any()
    private val recentRelaunchTimestamps = mutableMapOf<String, Long>()
    private val homeTimerLock = Any()
    private var homeDisconnectJob: Job? = null
    private val eventSequence = AtomicLong(0L)
    private var latestForegroundSequence = 0L

    private var inFlightPackage: String? = null
    private var inFlightJob: Job? = null

    @Volatile
    private var lastForegroundPackage: String? = null

    private val _lastInterceptedSummary = MutableStateFlow<String?>(null)
    val lastInterceptedSummary: StateFlow<String?> = _lastInterceptedSummary.asStateFlow()

    /**
     * Allocates the ordering sequence for an event. Call it synchronously where the event is
     * received (before dispatching to a concurrent scope) so events handled out of order can
     * be recognized as stale.
     */
    fun nextEventSequence(): Long = eventSequence.incrementAndGet()

    /**
     * Entry point called from [AppLaunchAccessibilityService.onAccessibilityEvent].
     */
    suspend fun handleEvent(
        eventType: Int,
        packageName: String?,
        sequence: Long = nextEventSequence()
    ): InterceptionOutcome {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return InterceptionOutcome.Ignored
        }
        return handleWindowStateChanged(packageName, sequence)
    }

    suspend fun handleWindowStateChanged(
        rawPackageName: String?,
        sequence: Long = nextEventSequence()
    ): InterceptionOutcome {
        val packageName = rawPackageName?.trim().orEmpty()
        if (packageName.isEmpty()) return InterceptionOutcome.Ignored

        // Ignore transient system overlays (SystemUI, volume/permission dialogs, keyboards)
        // without cancelling the Home auto-disconnect timer or changing lastForegroundPackage.
        if (packageName in TRANSIENT_OVERLAY_PACKAGES) {
            return InterceptionOutcome.Ignored
        }

        // Drop window events that were overtaken by a newer foreground event, so a stale Home
        // event can't schedule an auto-disconnect while the user is already in an app.
        val isLatest = synchronized(homeTimerLock) {
            if (sequence < latestForegroundSequence) {
                false
            } else {
                latestForegroundSequence = sequence
                true
            }
        }
        if (!isLatest) return InterceptionOutcome.Ignored

        // Handle returning to the Google TV / Android TV Home Launcher.
        if (isHomeLauncher(packageName)) {
            // Suppress scheduling the Home auto-disconnect timer while an automatic interception
            // is actively running (interception itself invokes GLOBAL_ACTION_HOME).
            if (isInterceptionInFlight()) {
                cancelHomeAutoDisconnectTimer()
                return InterceptionOutcome.Ignored
            }
            lastForegroundPackage = packageName
            scheduleHomeAutoDisconnectIfEnabled(sequence)
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

        val myJob = currentCoroutineContext()[Job]
        val jobToSupersede: Job? = synchronized(flightLock) {
            val activeJob = inFlightJob?.takeIf { it.isActive }
            if (activeJob != null && inFlightPackage == packageName) {
                return InterceptionOutcome.Debounced
            }

            // Only apply relaunch debounce when we are NOT superseding a distinct in-flight app.
            val supersedingDistinctApp = activeJob != null && inFlightPackage != packageName
            if (!supersedingDistinctApp) {
                val lastRelaunch = synchronized(recentRelaunchTimestamps) {
                    recentRelaunchTimestamps[packageName]
                }
                if (isWithinRelaunchDebounce(lastRelaunch)) {
                    lastForegroundPackage = packageName
                    return InterceptionOutcome.Debounced
                }
            }

            inFlightPackage = packageName
            inFlightJob = myJob
            activeJob
        }

        // If another mapped app was being intercepted, cancel that stale activation first so it
        // does not relaunch over the user's newer app choice.
        if (jobToSupersede != null) {
            jobToSupersede.cancelAndJoin()
        }

        return try {
            interceptionMutex.withLock {
                // If an even newer distinct app superseded us while we waited, yield cleanly.
                val stillCurrent = synchronized(flightLock) {
                    inFlightJob === myJob && inFlightPackage == packageName
                }
                if (!stillCurrent) {
                    return@withLock InterceptionOutcome.Debounced
                }

                // Double-check debounce inside the lock when not superseding a distinct app.
                if (jobToSupersede == null) {
                    val lockedLastRelaunch = synchronized(recentRelaunchTimestamps) {
                        recentRelaunchTimestamps[packageName]
                    }
                    if (isWithinRelaunchDebounce(lockedLastRelaunch)) {
                        lastForegroundPackage = packageName
                        return@withLock InterceptionOutcome.Debounced
                    }
                }

                if (coordinator.isProfileCurrentlySatisfied(matchedProfile)) {
                    lastForegroundPackage = packageName
                    if (previousPackage != packageName) {
                        showToast("Already connected to ${matchedProfile.name}")
                    }
                    return@withLock InterceptionOutcome.AlreadySatisfied
                }

                cancelHomeAutoDisconnectTimer()

                // Notify user on screen before returning home & switching regions.
                showToast("Connecting to ${matchedProfile.name}...")

                // 1. Return to Home so the app does not render in the wrong region.
                performGlobalHomeAction()

                // 2. Force-stop target app (Shizuku IActivityManager.forceStopPackage -> fallback killBackgroundProcesses).
                forceStopper.forceStop(packageName)

                // 3. Record debounce timestamp BEFORE relaunch so our own relaunch event is ignored,
                //    but invalidate it if this activation is cancelled or never relaunches the app.
                synchronized(recentRelaunchTimestamps) {
                    recentRelaunchTimestamps[packageName] = clockElapsedMs()
                }

                // 4. Reconcile VPN + start/update MockLocationService + cleanly relaunch with CLEAR_TASK.
                val progress = try {
                    coordinator.activate(
                        profile = matchedProfile,
                        targetPackageOverride = packageName,
                        clearTaskOnLaunch = true
                    )
                } catch (ce: CancellationException) {
                    synchronized(recentRelaunchTimestamps) {
                        recentRelaunchTimestamps.remove(packageName)
                    }
                    throw ce
                }

                cancelHomeAutoDisconnectTimer()

                if (progress is ActivationProgress.Warning) {
                    synchronized(recentRelaunchTimestamps) {
                        recentRelaunchTimestamps.remove(packageName)
                    }
                    showToast(progress.message)
                    lastForegroundPackage = null
                } else {
                    synchronized(recentRelaunchTimestamps) {
                        recentRelaunchTimestamps[packageName] = clockElapsedMs()
                    }
                    lastForegroundPackage = packageName
                }

                _lastInterceptedSummary.value = "Intercepted $packageName → ${matchedProfile.name}"
                InterceptionOutcome.Intercepted(
                    packageName = packageName,
                    profileId = matchedProfile.id,
                    progress = progress
                )
            }
        } finally {
            synchronized(flightLock) {
                if (inFlightJob === myJob) {
                    inFlightJob = null
                    inFlightPackage = null
                }
            }
        }
    }

    /**
     * Whether our own relaunch at [lastRelaunch] happened within [relaunchDebounceMs]. A clock
     * that reads earlier than [lastRelaunch] is treated as stale rather than as a recent relaunch.
     */
    private fun isWithinRelaunchDebounce(lastRelaunch: Long?): Boolean {
        if (lastRelaunch == null) return false
        val elapsed = clockElapsedMs() - lastRelaunch
        return elapsed in 0 until relaunchDebounceMs
    }

    fun cancelHomeAutoDisconnectTimer() {
        synchronized(homeTimerLock) {
            homeDisconnectJob?.cancel()
            homeDisconnectJob = null
        }
    }

    private fun isInterceptionInFlight(): Boolean = synchronized(flightLock) {
        inFlightJob?.isActive == true
    }

    private suspend fun scheduleHomeAutoDisconnectIfEnabled(homeSequence: Long) {
        if (isInterceptionInFlight()) {
            cancelHomeAutoDisconnectTimer()
            return
        }
        val settings = autoDisconnectSettingsProvider()
        if (!settings.enabled) {
            cancelHomeAutoDisconnectTimer()
            return
        }
        if (!hasActiveSessionOrVpn()) {
            cancelHomeAutoDisconnectTimer()
            return
        }
        val delayMs = settings.gracePeriodSeconds.coerceAtLeast(1) * 1_000L
        synchronized(homeTimerLock) {
            // The user left Home (e.g. opened an app) while the settings were being read.
            if (latestForegroundSequence != homeSequence) return
            if (homeDisconnectJob?.isActive == true) {
                // Grace timer already running while user remains on the Home Launcher.
                return
            }
            homeDisconnectJob = scope.launch {
                delay(delayMs)
                if (!isInterceptionInFlight() && hasActiveSessionOrVpn()) {
                    coordinator.deactivateAll()
                    _lastInterceptedSummary.value = "Auto-disconnected after ${settings.gracePeriodSeconds}s on Home Launcher"
                }
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
            "$OWN_PACKAGE.debug",
            "com.android.settings",
            "com.android.tv.settings",
            VpnController.TAILSCALE_PACKAGE,
            VpnController.WG_TUNNEL_PACKAGE,
            VpnController.SOCKS_WRAPPER_PACKAGE,
            VpnController.SHIZUKU_PACKAGE
        )
    }
}
