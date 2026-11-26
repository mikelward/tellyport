package tv.tellyport.vpn

import android.content.Intent
import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.VpnBackend
import tv.tellyport.location.MockLocationStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VpnControllerTest {

    private class RecordingIntentDispatcher(
        private val installedPackages: Set<String> = setOf(
            VpnController.TAILSCALE_PACKAGE,
            VpnController.WG_TUNNEL_PACKAGE,
            "com.cbs.ott",
            "uk.co.bbc.iplayer",
            "com.amazon.avod.thirdpartyclient"
        )
    ) : IntentDispatcher {
        val broadcasts = mutableListOf<Intent>()
        val startedActivities = mutableListOf<Intent>()

        override fun sendBroadcast(intent: Intent) {
            broadcasts.add(intent)
        }

        override fun startActivity(intent: Intent): Boolean {
            startedActivities.add(intent)
            return true
        }

        override fun resolveLaunchIntent(packageName: String): Intent? {
            return if (packageName in installedPackages) {
                Intent(Intent.ACTION_MAIN).apply { `package` = packageName }
            } else {
                null
            }
        }
    }

    private fun autoInjectingMockStarter(
        mockStatus: MutableStateFlow<MockLocationStatus>,
        recorded: MutableList<RegionalProfile> = mutableListOf()
    ): (RegionalProfile, Long) -> Unit = { profile, sessionId ->
        recorded.add(profile)
        mockStatus.value = MockLocationStatus(
            isActive = true,
            sessionId = sessionId,
            activeProfileId = profile.id,
            activeProfileName = profile.name,
            configuredLatitude = profile.latitude,
            configuredLongitude = profile.longitude,
            lastInjectedLatitude = profile.latitude,
            lastInjectedLongitude = profile.longitude,
            injectionCount = 1L
        )
    }

    @Test
    fun tailscaleConnectAndDisconnectIntents_targetExplicitIpnReceiver() {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)

        val connectIntent = controller.buildTailscaleConnectIntent()
        assertEquals(VpnController.ACTION_TAILSCALE_CONNECT, connectIntent.action)
        assertEquals(VpnController.TAILSCALE_PACKAGE, connectIntent.`package`)
        assertEquals(VpnController.TAILSCALE_PACKAGE, connectIntent.component?.packageName)
        assertEquals(VpnController.TAILSCALE_RECEIVER, connectIntent.component?.className)

        val disconnectIntent = controller.buildTailscaleDisconnectIntent()
        assertEquals(VpnController.ACTION_TAILSCALE_DISCONNECT, disconnectIntent.action)
        assertEquals(VpnController.TAILSCALE_PACKAGE, disconnectIntent.`package`)
        assertEquals(VpnController.TAILSCALE_RECEIVER, disconnectIntent.component?.className)
    }

    @Test
    fun wgTunnelIntents_useOfficialRemoteControlReceiverContractWithKeyAndTunnelName() {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)

        val connectIntent = controller.buildWgTunnelConnectIntent("  unifi-au-syd  ", "  secret-key-123 ")
        assertNotNull(connectIntent)
        assertEquals("com.zaneschepke.wireguardautotunnel.START_TUNNEL", connectIntent!!.action)
        assertEquals("com.zaneschepke.wireguardautotunnel", connectIntent.`package`)
        assertEquals(
            "com.zaneschepke.wireguardautotunnel.core.broadcast.RemoteControlReceiver",
            connectIntent.component?.className
        )
        assertEquals("unifi-au-syd", connectIntent.getStringExtra("tunnelName"))
        assertEquals("secret-key-123", connectIntent.getStringExtra("key"))

        val disconnectIntent = controller.buildWgTunnelDisconnectIntent("unifi-au-syd", "secret-key-123")
        assertNotNull(disconnectIntent)
        assertEquals("com.zaneschepke.wireguardautotunnel.STOP_TUNNEL", disconnectIntent!!.action)
        assertEquals("com.zaneschepke.wireguardautotunnel", disconnectIntent.`package`)
        assertEquals(
            "com.zaneschepke.wireguardautotunnel.core.broadcast.RemoteControlReceiver",
            disconnectIntent.component?.className
        )
        assertEquals("unifi-au-syd", disconnectIntent.getStringExtra("tunnelName"))
        assertEquals("secret-key-123", disconnectIntent.getStringExtra("key"))

        val blankTunnelIntent = controller.buildWgTunnelConnectIntent("   ", "secret-key-123")
        assertNotNull(blankTunnelIntent)
        assertFalse(blankTunnelIntent!!.hasExtra("tunnelName"))
        assertEquals("secret-key-123", blankTunnelIntent.getStringExtra("key"))

        assertNull(controller.buildWgTunnelConnectIntent("unifi-au-syd", ""))
        assertNull(controller.buildWgTunnelConnectIntent("unifi-au-syd", "   "))
        assertNull(controller.buildWgTunnelDisconnectIntent("unifi-au-syd", null))
    }

    @Test
    fun noneBackend_sendsNoBroadcast() {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val profile = RegionalProfile(
            id = "none-1",
            name = "Local Only",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            vpnBackend = VpnBackend.NONE
        )

        val dispatched = controller.connect(profile)
        assertNull(dispatched)
        assertTrue(dispatcher.broadcasts.isEmpty())
    }

    @Test
    fun openTailscaleAndWgTunnelApps_launchPackagesWhenInstalled() {
        val dispatcher = RecordingIntentDispatcher(
            installedPackages = setOf(VpnController.TAILSCALE_PACKAGE, VpnController.WG_TUNNEL_PACKAGE)
        )
        val controller = VpnController(dispatcher)

        assertTrue(controller.openTailscaleApp())
        assertTrue(controller.openWgTunnelApp())
        assertEquals(
            listOf(VpnController.TAILSCALE_PACKAGE, VpnController.WG_TUNNEL_PACKAGE),
            dispatcher.startedActivities.map { it.`package` }
        )
    }

    @Test
    fun activationCoordinator_waitsForVpnThenLaunchesTargetApp() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())
        val startedMockProfiles = mutableListOf<RegionalProfile>()
        var stoppedMockCount = 0

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus, startedMockProfiles),
            stopMockLocation = { stoppedMockCount++ },
            wgRemoteControlKeyProvider = { "wg-key-abc" },
            vpnWaitTimeoutMs = 8_000L
        )

        val profile = RegionalProfile(
            id = "us-1",
            name = "US",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            vpnBackend = VpnBackend.TAILSCALE,
            targetAppPackage = "com.cbs.ott",
            autoLaunchApp = true
        )

        val resultDeferred = async { coordinator.activate(profile) }

        advanceTimeBy(2_000L)
        assertEquals(1, dispatcher.broadcasts.size)
        assertEquals(VpnController.ACTION_TAILSCALE_CONNECT, dispatcher.broadcasts.single().action)
        assertEquals(listOf(profile), startedMockProfiles)
        assertTrue(dispatcher.startedActivities.isEmpty())

        vpnState.value = VpnConnectionState(isConnected = true, networkHandle = 101L)
        val result = resultDeferred.await()

        assertTrue(result is ActivationProgress.Active)
        assertEquals(1, dispatcher.startedActivities.size)
        assertEquals("com.cbs.ott", dispatcher.startedActivities.single().`package`)

        coordinator.deactivateAll()
        assertEquals(1, stoppedMockCount)
        assertEquals(3, dispatcher.broadcasts.size)
        assertEquals(VpnController.ACTION_TAILSCALE_DISCONNECT, dispatcher.broadcasts[1].action)
        assertEquals(VpnController.ACTION_WG_TUNNEL_DISCONNECT, dispatcher.broadcasts[2].action)
        assertEquals("wg-key-abc", dispatcher.broadcasts[2].getStringExtra(VpnController.EXTRA_WG_REMOTE_KEY))
    }

    @Test
    fun activationCoordinator_alreadyConnectedExternalTailscaleWithRedactedOwner_reconnectsCleanly() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        // Fresh coordinator: Tailscale is already connected externally and Android redacts ownerUid.
        val vpnState = MutableStateFlow(
            VpnConnectionState(isConnected = true, networkHandle = 100L, ownerBackend = null)
        )
        val mockStatus = MutableStateFlow(MockLocationStatus())

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus),
            stopMockLocation = {},
            wgRemoteControlKeyProvider = { "" },
            vpnWaitTimeoutMs = 8_000L
        )

        val tailscaleProfile = RegionalProfile(
            id = "us-ts",
            name = "US Tailscale",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            vpnBackend = VpnBackend.TAILSCALE,
            targetAppPackage = "com.cbs.ott",
            autoLaunchApp = true
        )

        val deferred = async { coordinator.activate(tailscaleProfile) }
        runCurrent()

        // Coordinator explicitly coordinates teardown by sending Tailscale DISCONNECT_VPN.
        assertEquals(VpnController.ACTION_TAILSCALE_DISCONNECT, dispatcher.broadcasts.last().action)
        assertTrue(dispatcher.startedActivities.isEmpty())

        // Tailscale disconnects cleanly, then reconnects on handle 101 (or even same handle after disconnect).
        vpnState.value = VpnConnectionState(isConnected = false, networkHandle = null, ownerBackend = null)
        runCurrent()
        assertEquals(VpnController.ACTION_TAILSCALE_CONNECT, dispatcher.broadcasts.last().action)

        vpnState.value = VpnConnectionState(isConnected = true, networkHandle = 100L, ownerBackend = null)
        val result = deferred.await()

        assertTrue(result is ActivationProgress.Active)
        assertEquals(listOf("com.cbs.ott"), dispatcher.startedActivities.map { it.`package` })
    }

    @Test
    fun activationCoordinator_switchingBackends_tearsDownOldVpnAndWaitsForNewConnection() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus),
            stopMockLocation = {},
            wgRemoteControlKeyProvider = { "wg-key-1" },
            vpnWaitTimeoutMs = 8_000L
        )

        val tailscaleProfile = RegionalProfile(
            id = "us-ts",
            name = "US Tailscale",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            vpnBackend = VpnBackend.TAILSCALE,
            targetAppPackage = "com.cbs.ott",
            autoLaunchApp = true
        )

        val firstActivation = async { coordinator.activate(tailscaleProfile) }
        runCurrent()
        vpnState.value = VpnConnectionState(isConnected = true, networkHandle = 100L)
        assertTrue(firstActivation.await() is ActivationProgress.Active)
        assertEquals(listOf("com.cbs.ott"), dispatcher.startedActivities.map { it.`package` })

        val wgProfile = RegionalProfile(
            id = "au-wg",
            name = "AU WG",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            vpnBackend = VpnBackend.WG_TUNNEL,
            vpnTunnelOrNodeName = "unifi-au-syd",
            targetAppPackage = "com.amazon.avod.thirdpartyclient",
            autoLaunchApp = true
        )

        val secondActivation = async { coordinator.activate(wgProfile) }
        advanceTimeBy(1_000L)

        assertEquals(VpnController.ACTION_TAILSCALE_DISCONNECT, dispatcher.broadcasts.last().action)
        assertEquals(1, dispatcher.startedActivities.size)

        vpnState.value = VpnConnectionState(isConnected = false, networkHandle = null)
        runCurrent()

        val wgStartBroadcast = dispatcher.broadcasts.last()
        assertEquals(VpnController.ACTION_WG_TUNNEL_CONNECT, wgStartBroadcast.action)
        assertEquals("unifi-au-syd", wgStartBroadcast.getStringExtra(VpnController.EXTRA_WG_TUNNEL_NAME))
        assertEquals("wg-key-1", wgStartBroadcast.getStringExtra(VpnController.EXTRA_WG_REMOTE_KEY))
        assertEquals(1, dispatcher.startedActivities.size)

        vpnState.value = VpnConnectionState(isConnected = true, networkHandle = 200L)
        val secondResult = secondActivation.await()

        assertTrue(secondResult is ActivationProgress.Active)
        assertEquals(
            listOf("com.cbs.ott", "com.amazon.avod.thirdpartyclient"),
            dispatcher.startedActivities.map { it.`package` }
        )
    }

    @Test
    fun activationCoordinator_cancellationDuringPendingNamedWgTunnel_stopsThatExactTunnel() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus),
            stopMockLocation = {},
            wgRemoteControlKeyProvider = { "wg-key-99" },
            vpnWaitTimeoutMs = 8_000L
        )

        val wgProfile = RegionalProfile(
            id = "au-wg-named",
            name = "AU Named Tunnel",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            vpnBackend = VpnBackend.WG_TUNNEL,
            vpnTunnelOrNodeName = "unifi-au-syd",
            targetAppPackage = "com.amazon.avod.thirdpartyclient",
            autoLaunchApp = true
        )

        // Start activation; START_TUNNEL for "unifi-au-syd" is dispatched, now waiting for VPN.
        val pendingJob = async { coordinator.activate(wgProfile) }
        advanceTimeBy(1_000L)
        assertEquals(VpnController.ACTION_WG_TUNNEL_CONNECT, dispatcher.broadcasts.last().action)
        assertEquals("unifi-au-syd", dispatcher.broadcasts.last().getStringExtra(VpnController.EXTRA_WG_TUNNEL_NAME))

        // Cancel activation mid-wait and invoke deactivateAll().
        pendingJob.cancel()
        coordinator.deactivateAll()

        val wgStopIntents = dispatcher.broadcasts.filter { it.action == VpnController.ACTION_WG_TUNNEL_DISCONNECT }
        assertEquals(1, wgStopIntents.size)
        assertEquals("unifi-au-syd", wgStopIntents.single().getStringExtra(VpnController.EXTRA_WG_TUNNEL_NAME))
        assertEquals("wg-key-99", wgStopIntents.single().getStringExtra(VpnController.EXTRA_WG_REMOTE_KEY))
        assertTrue(dispatcher.startedActivities.isEmpty())
    }

    @Test
    fun activationCoordinator_switchingBackends_whenOldVpnNeverTearsDown_doesNotLaunchApp() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(
            VpnConnectionState(
                isConnected = true,
                networkHandle = 100L,
                ownerBackend = VpnBackend.TAILSCALE
            )
        )
        val mockStatus = MutableStateFlow(MockLocationStatus())

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus),
            stopMockLocation = {},
            wgRemoteControlKeyProvider = { "wg-key-1" },
            vpnWaitTimeoutMs = 8_000L
        )

        val wgProfile = RegionalProfile(
            id = "au-wg",
            name = "AU WG",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            vpnBackend = VpnBackend.WG_TUNNEL,
            vpnTunnelOrNodeName = "unifi-au-syd",
            targetAppPackage = "com.amazon.avod.thirdpartyclient",
            autoLaunchApp = true
        )

        val deferred = async { coordinator.activate(wgProfile) }
        advanceTimeBy(9_000L)
        val result = deferred.await()

        assertTrue(result is ActivationProgress.Warning)
        assertTrue(dispatcher.startedActivities.isEmpty())
    }

    @Test
    fun activationCoordinator_wgTunnelWithoutKey_warnsAndSkipsBroadcastAndLaunch() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus),
            stopMockLocation = {},
            wgRemoteControlKeyProvider = { "" },
            vpnWaitTimeoutMs = 8_000L
        )

        val wgProfile = RegionalProfile(
            id = "au-wg",
            name = "AU WG",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            vpnBackend = VpnBackend.WG_TUNNEL,
            vpnTunnelOrNodeName = "unifi-au-syd",
            targetAppPackage = "com.amazon.avod.thirdpartyclient",
            autoLaunchApp = true
        )

        val result = coordinator.activate(wgProfile)
        assertTrue(result is ActivationProgress.Warning)
        assertTrue(dispatcher.broadcasts.isEmpty())
        assertTrue(dispatcher.startedActivities.isEmpty())
    }

    @Test
    fun activationCoordinator_noneBackend_waitsForFirstMockFixBeforeLaunchingApp() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())
        var capturedSessionId = -1L

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = { _, sessionId -> capturedSessionId = sessionId },
            stopMockLocation = {},
            vpnWaitTimeoutMs = 8_000L,
            mockLocationWaitTimeoutMs = 5_000L
        )

        val profile = RegionalProfile(
            id = "uk-none",
            name = "UK Direct",
            regionCode = "UK",
            latitude = 51.5074,
            longitude = -0.1278,
            vpnBackend = VpnBackend.NONE,
            targetAppPackage = "uk.co.bbc.iplayer",
            autoLaunchApp = true
        )

        val deferred = async { coordinator.activate(profile) }
        advanceTimeBy(500L)

        // Mock service hasn't injected fix #1 yet -> target app MUST NOT launch yet!
        assertTrue(capturedSessionId > 0L)
        assertTrue(dispatcher.broadcasts.isEmpty())
        assertTrue(dispatcher.startedActivities.isEmpty())

        // Service injects fix #1 for this session.
        mockStatus.value = MockLocationStatus(
            isActive = true,
            sessionId = capturedSessionId,
            activeProfileId = profile.id,
            activeProfileName = profile.name,
            injectionCount = 1L
        )
        val result = deferred.await()

        assertTrue(result is ActivationProgress.Active)
        assertEquals(listOf("uk.co.bbc.iplayer"), dispatcher.startedActivities.map { it.`package` })
    }

    @Test
    fun activationCoordinator_whenMockServiceStartupFails_skipsAppLaunchImmediately() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = { _, sessionId ->
                mockStatus.value = MockLocationStatus(
                    isActive = false,
                    failedSessionId = sessionId,
                    mockPermissionDenied = true,
                    errorMessage = "Tellyport is not selected as the Mock Location app."
                )
            },
            stopMockLocation = {},
            vpnWaitTimeoutMs = 8_000L,
            mockLocationWaitTimeoutMs = 5_000L
        )

        val profile = RegionalProfile(
            id = "uk-none-fail",
            name = "UK Direct",
            regionCode = "UK",
            latitude = 51.5074,
            longitude = -0.1278,
            vpnBackend = VpnBackend.NONE,
            targetAppPackage = "uk.co.bbc.iplayer",
            autoLaunchApp = true
        )

        val result = coordinator.activate(profile)

        assertTrue(result is ActivationProgress.Warning)
        assertTrue(dispatcher.startedActivities.isEmpty())
    }

    @Test
    fun activationCoordinator_whenAutoLaunchDisabled_skipsLaunchingTargetApp() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus),
            stopMockLocation = {},
            wgRemoteControlKeyProvider = { "wg-secret" },
            vpnWaitTimeoutMs = 8_000L
        )

        val profile = RegionalProfile(
            id = "wg-no-launch",
            name = "AU Manual",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            vpnBackend = VpnBackend.WG_TUNNEL,
            vpnTunnelOrNodeName = "unifi-au-syd",
            targetAppPackage = "com.cbs.ott",
            autoLaunchApp = false
        )

        val deferred = async { coordinator.activate(profile) }
        runCurrent()
        vpnState.value = VpnConnectionState(isConnected = true, networkHandle = 303L)
        val result = deferred.await()

        assertTrue(result is ActivationProgress.Active)
        assertEquals(1, dispatcher.broadcasts.size)
        assertEquals("unifi-au-syd", dispatcher.broadcasts.single().getStringExtra(VpnController.EXTRA_WG_TUNNEL_NAME))
        assertEquals("wg-secret", dispatcher.broadcasts.single().getStringExtra(VpnController.EXTRA_WG_REMOTE_KEY))
        assertTrue(dispatcher.startedActivities.isEmpty())
    }

    @Test
    fun activationCoordinator_whenVpnTimesOut_reportsWarningAndSkipsAppLaunch() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus),
            stopMockLocation = {},
            vpnWaitTimeoutMs = 8_000L
        )

        val profile = RegionalProfile(
            id = "timeout-1",
            name = "US Timeout",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            vpnBackend = VpnBackend.TAILSCALE,
            targetAppPackage = "com.cbs.ott",
            autoLaunchApp = true
        )

        val resultDeferred = async { coordinator.activate(profile) }
        advanceTimeBy(8_500L)
        val result = resultDeferred.await()

        assertTrue(result is ActivationProgress.Warning)
        assertTrue(dispatcher.startedActivities.isEmpty())
    }

    @Test
    fun newCoordinatorInstance_generatesUniqueSessionIdAndWaitsForFreshFix() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())

        val profileV1 = RegionalProfile(
            id = "uk-none",
            name = "UK Direct",
            regionCode = "UK",
            latitude = 51.5074,
            longitude = -0.1278,
            vpnBackend = VpnBackend.NONE,
            targetAppPackage = "uk.co.bbc.iplayer",
            autoLaunchApp = true
        )

        var firstSessionId = -1L
        val coordinator1 = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = { profile, sid ->
                firstSessionId = sid
                mockStatus.value = MockLocationStatus(
                    isActive = true,
                    sessionId = sid,
                    activeProfileId = profile.id,
                    activeProfileName = profile.name,
                    lastInjectedLatitude = profile.latitude,
                    lastInjectedLongitude = profile.longitude,
                    injectionCount = 1L
                )
            },
            stopMockLocation = {}
        )
        assertTrue(coordinator1.activate(profileV1) is ActivationProgress.Active)
        assertEquals(1, dispatcher.startedActivities.size)

        // Simulate Activity/ViewModel recreation while MockLocationService is still running
        // with firstSessionId and the user re-activates the edited profile.
        val profileV2 = profileV1.copy(latitude = 53.4808, longitude = -2.2426)
        var secondSessionId = -1L
        val coordinator2 = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = { _, sid -> secondSessionId = sid },
            stopMockLocation = {}
        )

        val deferred = async { coordinator2.activate(profileV2) }
        advanceTimeBy(500L)

        // New coordinator must have a distinct session ID and must NOT accept coordinator1's fix.
        assertTrue(secondSessionId > firstSessionId)
        assertEquals(1, dispatcher.startedActivities.size)

        // Once the service injects fix #1 for secondSessionId, launch proceeds.
        mockStatus.value = MockLocationStatus(
            isActive = true,
            sessionId = secondSessionId,
            activeProfileId = profileV2.id,
            activeProfileName = profileV2.name,
            lastInjectedLatitude = profileV2.latitude,
            lastInjectedLongitude = profileV2.longitude,
            injectionCount = 1L
        )
        assertTrue(deferred.await() is ActivationProgress.Active)
        assertEquals(2, dispatcher.startedActivities.size)
    }

    @Test
    fun activationCoordinator_whenVpnDropsDuringMockLocationWait_skipsAppLaunchAndWarns() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())
        var capturedSessionId = -1L

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = { _, sid -> capturedSessionId = sid },
            stopMockLocation = {},
            vpnWaitTimeoutMs = 8_000L,
            mockLocationWaitTimeoutMs = 5_000L
        )

        val profile = RegionalProfile(
            id = "us-drop",
            name = "US Drop",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            vpnBackend = VpnBackend.TAILSCALE,
            targetAppPackage = "com.cbs.ott",
            autoLaunchApp = true
        )

        val deferred = async { coordinator.activate(profile) }
        runCurrent()

        // VPN connects first while mock location is still starting up.
        vpnState.value = VpnConnectionState(isConnected = true, networkHandle = 777L)
        advanceTimeBy(300L)

        // VPN drops BEFORE the first mock fix arrives!
        vpnState.value = VpnConnectionState(isConnected = false, networkHandle = null)
        advanceTimeBy(100L)

        // Now the first mock fix arrives.
        mockStatus.value = MockLocationStatus(
            isActive = true,
            sessionId = capturedSessionId,
            activeProfileId = profile.id,
            activeProfileName = profile.name,
            injectionCount = 1L
        )

        val result = deferred.await()
        assertTrue(result is ActivationProgress.Warning)
        assertTrue(dispatcher.startedActivities.isEmpty())
    }
}
