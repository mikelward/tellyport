package tv.tellyport.vpn

import android.content.Intent
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
import tv.tellyport.data.ActiveVpnSession
import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.VpnBackend
import tv.tellyport.location.MockLocationStatus
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VpnControllerTest {

    private class RecordingIntentDispatcher(
        private val installedPackages: Set<String> = setOf(
            VpnController.TAILSCALE_PACKAGE,
            VpnController.WG_TUNNEL_PACKAGE,
            VpnController.SHIZUKU_PACKAGE,
            "com.cbs.ott",
            "uk.co.bbc.iplayer",
            "com.amazon.avod.thirdpartyclient",
            "au.com.stan.and"
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
    fun vpnBackendHeuristic_identifiesTailscaleCgnatAndMagicDnsWithoutOwnerUid() {
        assertEquals(
            InferredVpnBackend.TAILSCALE,
            VpnBackendHeuristic.inferFromAddresses(
                linkAddresses = listOf(InetAddress.getByName("100.64.0.1"))
            )
        )
        assertEquals(
            InferredVpnBackend.TAILSCALE,
            VpnBackendHeuristic.inferFromAddresses(
                linkAddresses = listOf(InetAddress.getByName("100.127.255.254"))
            )
        )
        assertEquals(
            InferredVpnBackend.TAILSCALE,
            VpnBackendHeuristic.inferFromAddresses(
                linkAddresses = listOf(InetAddress.getByName("10.0.0.2")),
                dnsServers = listOf(InetAddress.getByName("100.100.100.100"))
            )
        )
        // Outside 100.64.0.0/10 -> UNKNOWN_EXTERNAL
        assertEquals(
            InferredVpnBackend.UNKNOWN_EXTERNAL,
            VpnBackendHeuristic.inferFromAddresses(
                linkAddresses = listOf(InetAddress.getByName("100.63.255.255"))
            )
        )
        assertEquals(
            InferredVpnBackend.UNKNOWN_EXTERNAL,
            VpnBackendHeuristic.inferFromAddresses(
                linkAddresses = listOf(InetAddress.getByName("100.128.0.1"))
            )
        )
        assertEquals(
            InferredVpnBackend.UNKNOWN_EXTERNAL,
            VpnBackendHeuristic.inferFromAddresses(
                linkAddresses = listOf(InetAddress.getByName("10.10.0.5")),
                dnsServers = listOf(InetAddress.getByName("1.1.1.1"))
            )
        )
    }

    @Test
    fun tailscaleAndWgTunnelIntents_matchExpectedContracts() {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)

        val tsConnect = controller.buildTailscaleConnectIntent()
        assertEquals(VpnController.ACTION_TAILSCALE_CONNECT, tsConnect.action)
        assertEquals(VpnController.TAILSCALE_PACKAGE, tsConnect.`package`)
        assertEquals(VpnController.TAILSCALE_RECEIVER, tsConnect.component?.className)

        val wgConnect = controller.buildWgTunnelConnectIntent("  unifi-au-syd ", " key-123 ")
        assertNotNull(wgConnect)
        assertEquals(VpnController.ACTION_WG_TUNNEL_CONNECT, wgConnect!!.action)
        assertEquals(VpnController.WG_TUNNEL_RECEIVER, wgConnect.component?.className)
        assertEquals("unifi-au-syd", wgConnect.getStringExtra(VpnController.EXTRA_WG_TUNNEL_NAME))
        assertEquals("key-123", wgConnect.getStringExtra(VpnController.EXTRA_WG_REMOTE_KEY))

        assertNull(controller.buildWgTunnelConnectIntent("unifi-au-syd", ""))
    }

    @Test
    fun coldStartRedactedOwnerUid_tailscaleAlreadyConnectedVia100_64_takesFastPathWithoutDisconnectOrTimeout() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        // Cold start: persistedSession is null, ownerUid is unavailable/redacted,
        // but LinkProperties heuristic detects Tailscale 100.64.0.0/10!
        val vpnState = MutableStateFlow(
            VpnConnectionState(
                isConnected = true,
                networkHandle = 100L,
                inferredBackend = InferredVpnBackend.TAILSCALE,
                ipv4Addresses = listOf("100.101.42.7")
            )
        )
        val mockStatus = MutableStateFlow(MockLocationStatus())
        var persistedSession: ActiveVpnSession? = null

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus),
            stopMockLocation = {},
            wgRemoteControlKeyProvider = { "wg-key-1" },
            persistedSessionProvider = { persistedSession },
            persistActiveSession = { persistedSession = it }
        )

        val nyTailscale = RegionalProfile(
            id = "preset-ny",
            name = "New York",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            vpnBackend = VpnBackend.TAILSCALE,
            mappedPackages = listOf("com.cbs.ott"),
            autoLaunchApp = true
        )

        val result = coordinator.activate(nyTailscale)

        // Must take Already Satisfied Fast-Path: ZERO disconnect/connect broadcasts, no timeout,
        // mock location started, session persisted, and app launched immediately!
        assertTrue(result is ActivationProgress.Active)
        assertTrue("Expected zero VPN broadcasts on Tailscale fast-path", dispatcher.broadcasts.isEmpty())
        assertEquals("preset-ny", persistedSession?.profileId)
        assertEquals(VpnBackend.TAILSCALE, persistedSession?.vpnBackend)
        assertEquals(listOf("com.cbs.ott"), dispatcher.startedActivities.map { it.`package` })
    }

    @Test
    fun unattributedExternalVpnConflict_broadcastsDisconnectToBothBackendsWaits4sThenConnectsAndStabilizes() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        // Unattributed external VPN (e.g., 10.0.0.2 -> UNKNOWN_EXTERNAL, no persisted session).
        val vpnState = MutableStateFlow(
            VpnConnectionState(
                isConnected = true,
                networkHandle = 500L,
                inferredBackend = InferredVpnBackend.UNKNOWN_EXTERNAL,
                ipv4Addresses = listOf("10.0.0.2")
            )
        )
        val mockStatus = MutableStateFlow(MockLocationStatus())
        var persistedSession: ActiveVpnSession? = null

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus),
            stopMockLocation = {},
            wgRemoteControlKeyProvider = { "wg-secret-key" },
            persistedSessionProvider = { persistedSession },
            persistActiveSession = { persistedSession = it }
        )

        val sydneyWg = RegionalProfile(
            id = "preset-sydney",
            name = "Sydney",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            vpnBackend = VpnBackend.WG_TUNNEL,
            tunnelName = "unifi-au-syd",
            mappedPackages = listOf("au.com.stan.and"),
            autoLaunchApp = true
        )

        val deferred = async {
            coordinator.activate(
                profile = sydneyWg,
                targetPackageOverride = "au.com.stan.and",
                clearTaskOnLaunch = true
            )
        }
        advanceTimeBy(500L)

        // Must have broadcast disconnect to BOTH Tailscale and WG Tunnel before connecting.
        assertEquals(2, dispatcher.broadcasts.size)
        assertEquals(VpnController.ACTION_TAILSCALE_DISCONNECT, dispatcher.broadcasts[0].action)
        assertEquals(VpnController.ACTION_WG_TUNNEL_DISCONNECT, dispatcher.broadcasts[1].action)
        assertTrue(dispatcher.startedActivities.isEmpty())

        // Simulate TRANSPORT_VPN dropping (onLost).
        vpnState.value = VpnConnectionState(isConnected = false, inferredBackend = InferredVpnBackend.NONE)
        runCurrent()

        // Coordinator now sends WG Tunnel START_TUNNEL for unifi-au-syd.
        assertEquals(3, dispatcher.broadcasts.size)
        assertEquals(VpnController.ACTION_WG_TUNNEL_CONNECT, dispatcher.broadcasts[2].action)
        assertEquals("unifi-au-syd", dispatcher.broadcasts[2].getStringExtra(VpnController.EXTRA_WG_TUNNEL_NAME))
        assertEquals("wg-secret-key", dispatcher.broadcasts[2].getStringExtra(VpnController.EXTRA_WG_REMOTE_KEY))

        // Simulate new WG Tunnel coming up on handle 600.
        vpnState.value = VpnConnectionState(
            isConnected = true,
            networkHandle = 600L,
            inferredBackend = InferredVpnBackend.UNKNOWN_EXTERNAL
        )

        // Advance 250ms: still inside 500ms stabilization delay -> app must not launch yet.
        advanceTimeBy(250L)
        assertTrue(dispatcher.startedActivities.isEmpty())

        // Advance past 500ms stabilization delay -> app launches with CLEAR_TASK and session persisted!
        advanceTimeBy(300L)
        val result = deferred.await()

        assertTrue(result is ActivationProgress.Active)
        assertEquals("preset-sydney", persistedSession?.profileId)
        assertEquals(VpnBackend.WG_TUNNEL, persistedSession?.vpnBackend)
        assertEquals("unifi-au-syd", persistedSession?.tunnelName)
        assertEquals(1, dispatcher.startedActivities.size)
        val launchedIntent = dispatcher.startedActivities.single()
        assertEquals("au.com.stan.and", launchedIntent.`package`)
        assertTrue((launchedIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0)
        assertTrue((launchedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun unconfiguredProfile_returnsWarningWithoutTouchingVpnOrLaunching() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState())
        val mockStatus = MutableStateFlow(MockLocationStatus())

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus),
            stopMockLocation = {}
        )

        val unconfigured = RegionalProfile(
            id = "preset-london",
            name = "London",
            regionCode = "UK",
            latitude = 51.5074,
            longitude = -0.1278,
            vpnBackend = VpnBackend.UNCONFIGURED,
            mappedPackages = listOf("uk.co.bbc.iplayer")
        )

        val result = coordinator.activate(unconfigured)
        assertTrue(result is ActivationProgress.Warning)
        assertTrue(dispatcher.broadcasts.isEmpty())
        assertTrue(dispatcher.startedActivities.isEmpty())
    }

    @Test
    fun cancellationDuringPendingNamedWgTunnel_stopsThatExactTunnelAndClearsSession() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())
        var persistedSession: ActiveVpnSession? = null

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus),
            stopMockLocation = {},
            wgRemoteControlKeyProvider = { "wg-key-99" },
            persistedSessionProvider = { persistedSession },
            persistActiveSession = { persistedSession = it }
        )

        val wgProfile = RegionalProfile(
            id = "au-wg-named",
            name = "Sydney",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            vpnBackend = VpnBackend.WG_TUNNEL,
            tunnelName = "unifi-au-syd",
            mappedPackages = listOf("au.com.stan.and")
        )

        val pendingJob = async { coordinator.activate(wgProfile) }
        advanceTimeBy(1_000L)
        assertEquals(VpnController.ACTION_WG_TUNNEL_CONNECT, dispatcher.broadcasts.last().action)

        pendingJob.cancel()
        coordinator.deactivateAll()

        val wgStops = dispatcher.broadcasts.filter { it.action == VpnController.ACTION_WG_TUNNEL_DISCONNECT }
        assertEquals(1, wgStops.size)
        assertEquals("unifi-au-syd", wgStops.single().getStringExtra(VpnController.EXTRA_WG_TUNNEL_NAME))
        assertNull(persistedSession)
        assertTrue(dispatcher.startedActivities.isEmpty())
    }

    @Test
    fun whenVpnDropsDuringMockLocationWait_skipsAppLaunchAndWarns() = runTest {
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
            stabilizationDelayMs = 100L
        )

        val profile = RegionalProfile(
            id = "us-drop",
            name = "New York",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            vpnBackend = VpnBackend.TAILSCALE,
            mappedPackages = listOf("com.cbs.ott"),
            autoLaunchApp = true
        )

        val deferred = async { coordinator.activate(profile) }
        runCurrent()

        vpnState.value = VpnConnectionState(
            isConnected = true,
            networkHandle = 777L,
            inferredBackend = InferredVpnBackend.TAILSCALE
        )
        advanceTimeBy(300L)

        // VPN drops before first mock fix arrives.
        vpnState.value = VpnConnectionState(isConnected = false, inferredBackend = InferredVpnBackend.NONE)
        advanceTimeBy(100L)

        mockStatus.value = MockLocationStatus(
            isActive = true,
            sessionId = capturedSessionId,
            activeProfileId = profile.id,
            activeProfileName = profile.name,
            configuredLatitude = profile.latitude,
            configuredLongitude = profile.longitude,
            injectionCount = 1L
        )

        val result = deferred.await()
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
            name = "London",
            regionCode = "UK",
            latitude = 51.5074,
            longitude = -0.1278,
            vpnBackend = VpnBackend.NONE,
            mappedPackages = listOf("uk.co.bbc.iplayer"),
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
                    configuredLatitude = profile.latitude,
                    configuredLongitude = profile.longitude,
                    injectionCount = 1L
                )
            },
            stopMockLocation = {}
        )
        assertTrue(coordinator1.activate(profileV1) is ActivationProgress.Active)
        assertEquals(1, dispatcher.startedActivities.size)

        // Recreate coordinator while service is still active on firstSessionId; edit coordinates.
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
        assertTrue(secondSessionId > firstSessionId)
        assertEquals(1, dispatcher.startedActivities.size)

        mockStatus.value = MockLocationStatus(
            isActive = true,
            sessionId = secondSessionId,
            activeProfileId = profileV2.id,
            activeProfileName = profileV2.name,
            configuredLatitude = profileV2.latitude,
            configuredLongitude = profileV2.longitude,
            injectionCount = 1L
        )
        assertTrue(deferred.await() is ActivationProgress.Active)
        assertEquals(2, dispatcher.startedActivities.size)
    }

    @Test
    fun openHelperApps_launchInstalledPackages() {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)

        assertTrue(controller.openTailscaleApp())
        assertTrue(controller.openWgTunnelApp())
        assertTrue(controller.openShizukuApp())
        assertFalse(controller.launchApp("com.not.installed"))
    }

    @Test
    fun stalePersistedWgSession_whenLiveVpnIsTailscale_rejectsFastPathTearsDownAndConnectsWg() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        // User switched externally to Tailscale (handle 700, 100.64.x) while Tellyport was dead,
        // leaving behind a stale persisted WG Tunnel session (handle 600).
        val vpnState = MutableStateFlow(
            VpnConnectionState(
                isConnected = true,
                networkHandle = 700L,
                inferredBackend = InferredVpnBackend.TAILSCALE,
                ipv4Addresses = listOf("100.88.1.5")
            )
        )
        val mockStatus = MutableStateFlow(MockLocationStatus())
        var persistedSession: ActiveVpnSession? = ActiveVpnSession(
            profileId = "preset-sydney",
            vpnBackend = VpnBackend.WG_TUNNEL,
            tunnelName = "unifi-au-syd",
            networkHandle = 600L,
            activatedAtEpochMs = 1_000L
        )

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus),
            stopMockLocation = {},
            wgRemoteControlKeyProvider = { "wg-secret" },
            persistedSessionProvider = { persistedSession },
            persistActiveSession = { persistedSession = it },
            stabilizationDelayMs = 100L
        )

        val sydneyWg = RegionalProfile(
            id = "preset-sydney",
            name = "Sydney",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            vpnBackend = VpnBackend.WG_TUNNEL,
            tunnelName = "unifi-au-syd",
            mappedPackages = listOf("au.com.stan.and"),
            autoLaunchApp = true
        )

        val deferred = async { coordinator.activate(sydneyWg) }
        advanceTimeBy(200L)

        // Must NOT take fast-path: stale session cleared, Tailscale & WG disconnects dispatched, app NOT launched yet.
        assertNull(persistedSession)
        assertTrue(dispatcher.broadcasts.any { it.action == VpnController.ACTION_TAILSCALE_DISCONNECT })
        assertTrue(dispatcher.startedActivities.isEmpty())

        // Tailscale drops, then WG Tunnel connects on handle 800.
        vpnState.value = VpnConnectionState(isConnected = false, inferredBackend = InferredVpnBackend.NONE)
        runCurrent()
        assertEquals(VpnController.ACTION_WG_TUNNEL_CONNECT, dispatcher.broadcasts.last().action)

        vpnState.value = VpnConnectionState(
            isConnected = true,
            networkHandle = 800L,
            inferredBackend = InferredVpnBackend.UNKNOWN_EXTERNAL,
            ipv4Addresses = listOf("10.20.0.2")
        )
        advanceTimeBy(200L)

        val result = deferred.await()
        assertTrue(result is ActivationProgress.Active)
        assertEquals(800L, persistedSession?.networkHandle)
        assertEquals(listOf("au.com.stan.and"), dispatcher.startedActivities.map { it.`package` })
    }

    @Test
    fun validPersistedWgSession_withMatchingNetworkHandle_takesFastPathWithoutDisconnect() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(
            VpnConnectionState(
                isConnected = true,
                networkHandle = 600L,
                inferredBackend = InferredVpnBackend.UNKNOWN_EXTERNAL,
                ipv4Addresses = listOf("10.20.0.2")
            )
        )
        val mockStatus = MutableStateFlow(MockLocationStatus())
        var persistedSession: ActiveVpnSession? = ActiveVpnSession(
            profileId = "preset-sydney",
            vpnBackend = VpnBackend.WG_TUNNEL,
            tunnelName = "unifi-au-syd",
            networkHandle = 600L,
            activatedAtEpochMs = 1_000L
        )

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus),
            stopMockLocation = {},
            wgRemoteControlKeyProvider = { "wg-secret" },
            persistedSessionProvider = { persistedSession },
            persistActiveSession = { persistedSession = it }
        )

        val sydneyWg = RegionalProfile(
            id = "preset-sydney",
            name = "Sydney",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            vpnBackend = VpnBackend.WG_TUNNEL,
            tunnelName = "unifi-au-syd",
            mappedPackages = listOf("au.com.stan.and"),
            autoLaunchApp = true
        )

        val result = coordinator.activate(sydneyWg)
        assertTrue(result is ActivationProgress.Active)
        assertTrue(dispatcher.broadcasts.isEmpty())
        assertEquals(600L, persistedSession?.networkHandle)
        assertEquals(listOf("au.com.stan.and"), dispatcher.startedActivities.map { it.`package` })
    }

    @Test
    fun whenVpnReplacedByDifferentNetworkDuringStabilization_rejectsAndSkipsLaunch() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        val mockStatus = MutableStateFlow(MockLocationStatus())
        var persistedSession: ActiveVpnSession? = null

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = autoInjectingMockStarter(mockStatus),
            stopMockLocation = {},
            wgRemoteControlKeyProvider = { "wg-secret" },
            persistedSessionProvider = { persistedSession },
            persistActiveSession = { persistedSession = it },
            stabilizationDelayMs = 500L
        )

        val sydneyWg = RegionalProfile(
            id = "preset-sydney",
            name = "Sydney",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            vpnBackend = VpnBackend.WG_TUNNEL,
            tunnelName = "unifi-au-syd",
            mappedPackages = listOf("au.com.stan.and"),
            autoLaunchApp = true
        )

        val deferred = async { coordinator.activate(sydneyWg) }
        runCurrent()

        // Requested WG Tunnel connects on handle 800.
        vpnState.value = VpnConnectionState(
            isConnected = true,
            networkHandle = 800L,
            inferredBackend = InferredVpnBackend.UNKNOWN_EXTERNAL,
            ipv4Addresses = listOf("10.20.0.2")
        )
        advanceTimeBy(200L)

        // During stabilization, another VPN (Tailscale on handle 900) replaces it!
        vpnState.value = VpnConnectionState(
            isConnected = true,
            networkHandle = 900L,
            inferredBackend = InferredVpnBackend.TAILSCALE,
            ipv4Addresses = listOf("100.64.5.5")
        )
        advanceTimeBy(400L)

        val result = deferred.await()
        assertTrue(result is ActivationProgress.Warning)
        assertNull(persistedSession)
        assertTrue(dispatcher.startedActivities.isEmpty())
    }
}
