package tv.tellyport.accessibility

import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.tellyport.data.ActiveVpnSession
import tv.tellyport.data.AutoDisconnectSettings
import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.VpnBackend
import tv.tellyport.location.MockLocationStatus
import tv.tellyport.shizuku.ForceStopResult
import tv.tellyport.shizuku.PackageForceStopper
import tv.tellyport.vpn.ActivationProgress
import tv.tellyport.vpn.InferredVpnBackend
import tv.tellyport.vpn.IntentDispatcher
import tv.tellyport.vpn.ProfileActivationCoordinator
import tv.tellyport.vpn.VpnConnectionState
import tv.tellyport.vpn.VpnController

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLaunchInterceptionEngineTest {

    private class FakeDispatcher : IntentDispatcher {
        val broadcasts = mutableListOf<Intent>()
        val startedActivities = mutableListOf<Intent>()
        override fun sendBroadcast(intent: Intent) { broadcasts.add(intent) }
        override fun startActivity(intent: Intent): Boolean {
            startedActivities.add(intent)
            return true
        }
        override fun resolveLaunchIntent(packageName: String): Intent {
            return Intent(Intent.ACTION_MAIN).apply { `package` = packageName }
        }
    }

    private class FakeForceStopper : PackageForceStopper {
        val stoppedPackages = mutableListOf<String>()
        override fun forceStop(packageName: String): ForceStopResult {
            stoppedPackages.add(packageName)
            return ForceStopResult.SHIZUKU_FORCE_STOPPED
        }
    }

    @Test
    fun ignoresNonWindowStateEventsAndSystemOwnKeyboardPackages() = runTest {
        val dispatcher = FakeDispatcher()
        val vpnState = MutableStateFlow(VpnConnectionState())
        val mockStatus = MutableStateFlow(MockLocationStatus())
        val forceStopper = FakeForceStopper()
        val coordinator = ProfileActivationCoordinator(
            vpnController = VpnController(dispatcher),
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = { _, _ -> },
            stopMockLocation = {}
        )

        val engine = AppLaunchInterceptionEngine(
            scope = this,
            profilesProvider = {
                listOf(
                    RegionalProfile(
                        id = "ny",
                        name = "New York",
                        regionCode = "US",
                        latitude = 40.7128,
                        longitude = -74.0060,
                        vpnBackend = VpnBackend.TAILSCALE,
                        mappedPackages = listOf("com.cbs.ott")
                    )
                )
            },
            autoDisconnectSettingsProvider = { AutoDisconnectSettings(enabled = false) },
            hasActiveSessionOrVpn = { false },
            coordinator = coordinator,
            forceStopper = forceStopper,
            performGlobalHomeAction = { true }
        )

        assertEquals(
            InterceptionOutcome.Ignored,
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.cbs.ott")
        )
        assertEquals(
            InterceptionOutcome.Ignored,
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "tv.tellyport")
        )
        assertEquals(
            InterceptionOutcome.Ignored,
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.android.systemui")
        )
        assertEquals(
            InterceptionOutcome.Ignored,
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.google.android.inputmethod.latin")
        )
        assertEquals(
            InterceptionOutcome.Ignored,
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.unmapped.app")
        )
        assertTrue(forceStopper.stoppedPackages.isEmpty())
    }

    @Test
    fun interceptsMappedApp_sendsHomeForceStopsSwitchesVpnAndRelaunchesAndDebounces() = runTest {
        val dispatcher = FakeDispatcher()
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())
        val forceStopper = FakeForceStopper()
        var homePressCount = 0
        var fakeClockMs = 10_000L
        var persistedSession: ActiveVpnSession? = null

        val nyProfile = RegionalProfile(
            id = "preset-ny",
            name = "New York",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            vpnBackend = VpnBackend.TAILSCALE,
            mappedPackages = listOf("com.cbs.ott")
        )

        val coordinator = ProfileActivationCoordinator(
            vpnController = VpnController(dispatcher),
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = { profile, sid ->
                mockStatus.value = MockLocationStatus(
                    isActive = true,
                    sessionId = sid,
                    activeProfileId = profile.id,
                    activeProfileName = profile.name,
                    configuredLatitude = profile.latitude,
                    configuredLongitude = profile.longitude,
                    configuredAltitude = profile.altitude,
                    configuredAccuracyMeters = profile.accuracyMeters,
                    injectionCount = 1L
                )
            },
            stopMockLocation = {},
            persistedSessionProvider = { persistedSession },
            persistActiveSession = { persistedSession = it },
            stabilizationDelayMs = 500L
        )

        val toasts = mutableListOf<String>()
        val engine = AppLaunchInterceptionEngine(
            scope = this,
            profilesProvider = { listOf(nyProfile) },
            autoDisconnectSettingsProvider = { AutoDisconnectSettings(enabled = false) },
            hasActiveSessionOrVpn = { vpnState.value.isConnected },
            coordinator = coordinator,
            forceStopper = forceStopper,
            performGlobalHomeAction = {
                homePressCount++
                true
            },
            showToast = { toasts.add(it) },
            clockElapsedMs = { fakeClockMs },
            relaunchDebounceMs = 4_000L
        )

        val outcomeDeferred = async {
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.cbs.ott")
        }
        runCurrent()

        // Toast shown, Home pressed & force-stop executed immediately before VPN finishes connecting.
        assertEquals(listOf("Connecting to New York..."), toasts)
        assertEquals(1, homePressCount)
        assertEquals(listOf("com.cbs.ott"), forceStopper.stoppedPackages)

        // Duplicate window event while activation is still in flight must be debounced.
        assertEquals(
            InterceptionOutcome.Debounced,
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.cbs.ott")
        )

        // Tailscale connects + stabilizes.
        vpnState.value = VpnConnectionState(
            isConnected = true,
            networkHandle = 101L,
            inferredBackend = InferredVpnBackend.TAILSCALE
        )
        advanceTimeBy(600L)

        val outcome = outcomeDeferred.await()
        assertTrue(outcome is InterceptionOutcome.Intercepted)
        assertTrue((outcome as InterceptionOutcome.Intercepted).progress is ActivationProgress.Active)
        assertEquals(1, dispatcher.startedActivities.size)
        assertEquals("com.cbs.ott", dispatcher.startedActivities.single().`package`)
        assertTrue((dispatcher.startedActivities.single().flags and Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0)

        // Immediate window event from our own relaunch is debounced.
        fakeClockMs += 500L
        assertEquals(
            InterceptionOutcome.Debounced,
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.cbs.ott")
        )

        // Intra-app window transition after debounce sees AlreadySatisfied without spamming a toast.
        fakeClockMs += 5_000L
        assertEquals(
            InterceptionOutcome.AlreadySatisfied,
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.cbs.ott")
        )
        assertEquals(listOf("Connecting to New York..."), toasts)

        // Returning to Home Launcher and reopening com.cbs.ott shows "Already connected to New York".
        engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.google.android.apps.tv.launcherx")
        assertEquals(
            InterceptionOutcome.AlreadySatisfied,
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.cbs.ott")
        )
        assertEquals(
            listOf("Connecting to New York...", "Already connected to New York"),
            toasts
        )
        assertEquals(1, forceStopper.stoppedPackages.size)
    }

    @Test
    fun homeLauncherAutoDisconnect_triggersAfterGracePeriodAndCancelsWhenAppOpened() = runTest {
        val dispatcher = FakeDispatcher()
        val vpnState = MutableStateFlow(
            VpnConnectionState(isConnected = true, inferredBackend = InferredVpnBackend.TAILSCALE)
        )
        val mockStatus = MutableStateFlow(MockLocationStatus(isActive = true, activeProfileId = "ny", injectionCount = 1L))
        var mockStoppedCount = 0

        val coordinator = ProfileActivationCoordinator(
            vpnController = VpnController(dispatcher),
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = { _, _ -> },
            stopMockLocation = {
                mockStoppedCount++
                mockStatus.value = MockLocationStatus(isActive = false)
            }
        )

        val engine = AppLaunchInterceptionEngine(
            scope = this,
            profilesProvider = { emptyList() },
            autoDisconnectSettingsProvider = { AutoDisconnectSettings(enabled = true, gracePeriodSeconds = 60) },
            hasActiveSessionOrVpn = { vpnState.value.isConnected || mockStatus.value.isActive },
            coordinator = coordinator,
            forceStopper = FakeForceStopper(),
            performGlobalHomeAction = { true }
        )

        // Return to Google TV Launcher -> starts 60s timer.
        assertEquals(
            InterceptionOutcome.HomeGraceTimerScheduled,
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.google.android.apps.tv.launcherx")
        )
        advanceTimeBy(30_000L)
        assertEquals(0, mockStoppedCount)

        // User opens Tellyport at 30s -> Home auto-disconnect timer MUST be cancelled!
        assertEquals(
            InterceptionOutcome.Ignored,
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "tv.tellyport")
        )
        advanceTimeBy(60_000L)
        assertEquals(0, mockStoppedCount)

        // Return to launcher, then open Tailscale -> timer is also cancelled!
        engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.google.android.apps.tv.launcherx")
        advanceTimeBy(30_000L)
        assertEquals(
            InterceptionOutcome.Ignored,
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, VpnController.TAILSCALE_PACKAGE)
        )
        advanceTimeBy(60_000L)
        assertEquals(0, mockStoppedCount)

        // Return to launcher; a transient SystemUI overlay (e.g. volume bar) does NOT cancel the timer -> fires at 60s!
        engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.google.android.apps.tv.launcherx")
        advanceTimeBy(20_000L)
        assertEquals(
            InterceptionOutcome.Ignored,
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.android.systemui")
        )
        advanceTimeBy(41_000L)
        assertEquals(1, mockStoppedCount)
        assertTrue(dispatcher.broadcasts.any { it.action == VpnController.ACTION_TAILSCALE_DISCONNECT })
    }

    @Test
    fun openingDistinctMappedAppDuringActiveInterception_supersedesStaleAppAndLaunchesNewApp() = runTest {
        val dispatcher = FakeDispatcher()
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())
        val forceStopper = FakeForceStopper()
        val toasts = mutableListOf<String>()

        val nyProfile = RegionalProfile(
            id = "preset-ny",
            name = "New York",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            vpnBackend = VpnBackend.TAILSCALE,
            mappedPackages = listOf("com.cbs.ott")
        )
        val sydneyProfile = RegionalProfile(
            id = "preset-sydney",
            name = "Sydney",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            vpnBackend = VpnBackend.WG_TUNNEL,
            tunnelName = "unifi-au-syd",
            mappedPackages = listOf("au.com.stan.and")
        )

        val coordinator = ProfileActivationCoordinator(
            vpnController = VpnController(dispatcher),
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = { profile, sid ->
                mockStatus.value = MockLocationStatus(
                    isActive = true,
                    sessionId = sid,
                    activeProfileId = profile.id,
                    activeProfileName = profile.name,
                    configuredLatitude = profile.latitude,
                    configuredLongitude = profile.longitude,
                    configuredAltitude = profile.altitude,
                    configuredAccuracyMeters = profile.accuracyMeters,
                    injectionCount = 1L
                )
            },
            stopMockLocation = {},
            wgRemoteControlKeyProvider = { "wg-secret" },
            stabilizationDelayMs = 100L
        )

        val engine = AppLaunchInterceptionEngine(
            scope = this,
            profilesProvider = { listOf(nyProfile, sydneyProfile) },
            autoDisconnectSettingsProvider = { AutoDisconnectSettings(enabled = false) },
            hasActiveSessionOrVpn = { vpnState.value.isConnected },
            coordinator = coordinator,
            forceStopper = forceStopper,
            performGlobalHomeAction = { true },
            showToast = { toasts.add(it) }
        )

        // App A (Paramount+ / New York) starts intercepting and is waiting for Tailscale.
        val jobA = async {
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.cbs.ott")
        }
        runCurrent()
        assertEquals(listOf("Connecting to New York..."), toasts)

        // User opens distinct mapped App B (Stan / Sydney) while App A is still waiting!
        val jobB = async {
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "au.com.stan.and")
        }
        runCurrent()

        // App A must have been cancelled and App B is now connecting Sydney WG Tunnel.
        assertTrue(jobA.isCancelled)
        assertEquals(listOf("Connecting to New York...", "Connecting to Sydney..."), toasts)
        assertEquals(listOf("com.cbs.ott", "au.com.stan.and"), forceStopper.stoppedPackages)

        // Sydney WG Tunnel connects -> ONLY App B (Stan) is launched, NEVER stale App A!
        vpnState.value = VpnConnectionState(
            isConnected = true,
            networkHandle = 888L,
            inferredBackend = InferredVpnBackend.UNKNOWN_EXTERNAL,
            ipv4Addresses = listOf("10.20.0.2")
        )
        advanceTimeBy(200L)

        val outcomeB = jobB.await()
        assertTrue(outcomeB is InterceptionOutcome.Intercepted)
        assertTrue((outcomeB as InterceptionOutcome.Intercepted).progress is ActivationProgress.Active)
        assertEquals(listOf("au.com.stan.and"), dispatcher.startedActivities.map { it.`package` })
    }

    @Test
    fun openingA_thenB_thenA_withinDebounceWindow_supersedesBAndLaunchesA() = runTest {
        val dispatcher = FakeDispatcher()
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())
        val forceStopper = FakeForceStopper()
        var fakeClockMs = 20_000L

        val nyProfile = RegionalProfile(
            id = "preset-ny",
            name = "New York",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            vpnBackend = VpnBackend.TAILSCALE,
            mappedPackages = listOf("com.cbs.ott")
        )
        val sydneyProfile = RegionalProfile(
            id = "preset-sydney",
            name = "Sydney",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            vpnBackend = VpnBackend.WG_TUNNEL,
            tunnelName = "unifi-au-syd",
            mappedPackages = listOf("au.com.stan.and")
        )

        val coordinator = ProfileActivationCoordinator(
            vpnController = VpnController(dispatcher),
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = { profile, sid ->
                mockStatus.value = MockLocationStatus(
                    isActive = true,
                    sessionId = sid,
                    activeProfileId = profile.id,
                    activeProfileName = profile.name,
                    configuredLatitude = profile.latitude,
                    configuredLongitude = profile.longitude,
                    configuredAltitude = profile.altitude,
                    configuredAccuracyMeters = profile.accuracyMeters,
                    injectionCount = 1L
                )
            },
            stopMockLocation = {},
            wgRemoteControlKeyProvider = { "wg-secret" },
            stabilizationDelayMs = 100L
        )

        val engine = AppLaunchInterceptionEngine(
            scope = this,
            profilesProvider = { listOf(nyProfile, sydneyProfile) },
            autoDisconnectSettingsProvider = { AutoDisconnectSettings(enabled = false) },
            hasActiveSessionOrVpn = { vpnState.value.isConnected },
            coordinator = coordinator,
            forceStopper = forceStopper,
            performGlobalHomeAction = { true },
            clockElapsedMs = { fakeClockMs },
            relaunchDebounceMs = 4_000L
        )

        // A starts, then B supersedes A at +500ms, then user switches back to A at +1000ms (still < 4s).
        val jobA1 = async { engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.cbs.ott") }
        runCurrent()

        fakeClockMs += 500L
        val jobB = async { engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "au.com.stan.and") }
        runCurrent()
        assertTrue(jobA1.isCancelled)

        fakeClockMs += 500L
        val jobA2 = async { engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.cbs.ott") }
        runCurrent()
        assertTrue(jobB.isCancelled)

        vpnState.value = VpnConnectionState(
            isConnected = true,
            networkHandle = 1234L,
            inferredBackend = InferredVpnBackend.TAILSCALE
        )
        advanceTimeBy(200L)

        val outcomeA2 = jobA2.await()
        assertTrue(outcomeA2 is InterceptionOutcome.Intercepted)
        assertEquals(listOf("com.cbs.ott"), dispatcher.startedActivities.map { it.`package` })
    }

    @Test
    fun homeEventDuringInterception_doesNotScheduleAutoDisconnectWhileConnecting() = runTest {
        val dispatcher = FakeDispatcher()
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())
        var mockStopCount = 0

        val nyProfile = RegionalProfile(
            id = "preset-ny",
            name = "New York",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            vpnBackend = VpnBackend.TAILSCALE,
            mappedPackages = listOf("com.cbs.ott")
        )

        val coordinator = ProfileActivationCoordinator(
            vpnController = VpnController(dispatcher),
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = { profile, sid ->
                mockStatus.value = MockLocationStatus(
                    isActive = true,
                    sessionId = sid,
                    activeProfileId = profile.id,
                    activeProfileName = profile.name,
                    configuredLatitude = profile.latitude,
                    configuredLongitude = profile.longitude,
                    configuredAltitude = profile.altitude,
                    configuredAccuracyMeters = profile.accuracyMeters,
                    injectionCount = 1L
                )
            },
            stopMockLocation = {
                mockStopCount++
                mockStatus.value = MockLocationStatus(isActive = false)
            },
            stabilizationDelayMs = 500L
        )

        val engine = AppLaunchInterceptionEngine(
            scope = this,
            profilesProvider = { listOf(nyProfile) },
            // Grace period (5s) is shorter than the 6s VPN connection time below.
            autoDisconnectSettingsProvider = { AutoDisconnectSettings(enabled = true, gracePeriodSeconds = 5) },
            hasActiveSessionOrVpn = { vpnState.value.isConnected || mockStatus.value.isActive },
            coordinator = coordinator,
            forceStopper = FakeForceStopper(),
            performGlobalHomeAction = { true }
        )

        val job = async {
            engine.handleEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.cbs.ott")
        }
        runCurrent()

        // Interception's own GLOBAL_ACTION_HOME produces a launcher window event during activation.
        val homeOutcome = engine.handleEvent(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            "com.google.android.apps.tv.launcherx"
        )
        assertEquals(InterceptionOutcome.Ignored, homeOutcome)

        // Advance 6 seconds (> 5s grace period), then VPN connects.
        advanceTimeBy(6_000L)
        vpnState.value = VpnConnectionState(
            isConnected = true,
            networkHandle = 555L,
            inferredBackend = InferredVpnBackend.TAILSCALE
        )
        advanceTimeBy(600L)

        val outcome = job.await()
        assertTrue(outcome is InterceptionOutcome.Intercepted)
        assertEquals(0, mockStopCount)
        assertTrue(mockStatus.value.isActive)
        assertEquals(listOf("com.cbs.ott"), dispatcher.startedActivities.map { it.`package` })
    }
}
