package com.warpmo.app.vpn

import android.content.Intent
import com.warpmo.app.data.RegionalProfile
import com.warpmo.app.data.VpnBackend
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

        // Blank tunnelName omits tunnelName extra (defaults to primary tunnel), while key is required.
        val blankTunnelIntent = controller.buildWgTunnelConnectIntent("   ", "secret-key-123")
        assertNotNull(blankTunnelIntent)
        assertFalse(blankTunnelIntent!!.hasExtra("tunnelName"))
        assertEquals("secret-key-123", blankTunnelIntent.getStringExtra("key"))

        // Missing or blank remote-control key returns null so unauthenticated requests are not sent.
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
        val startedMockProfiles = mutableListOf<RegionalProfile>()
        var stoppedMockCount = 0

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            startMockLocation = { startedMockProfiles.add(it) },
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
    fun activationCoordinator_switchingBackends_tearsDownOldVpnAndWaitsForNewConnection() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            startMockLocation = {},
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

        // Activate Tailscale first and bring up handle 100.
        val firstActivation = async { coordinator.activate(tailscaleProfile) }
        runCurrent()
        vpnState.value = VpnConnectionState(isConnected = true, networkHandle = 100L)
        assertTrue(firstActivation.await() is ActivationProgress.Active)
        assertEquals(listOf("com.cbs.ott"), dispatcher.startedActivities.map { it.`package` })

        // Now switch to WG Tunnel while Tailscale (handle 100) is STILL connected.
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

        // Must have disconnected Tailscale first and MUST NOT have launched Prime Video over Tailscale.
        assertEquals(VpnController.ACTION_TAILSCALE_DISCONNECT, dispatcher.broadcasts.last().action)
        assertEquals(1, dispatcher.startedActivities.size)

        // Simulate Tailscale tearing down.
        vpnState.value = VpnConnectionState(isConnected = false, networkHandle = null)
        runCurrent()

        // Coordinator should now send WG Tunnel START_TUNNEL with key and tunnelName.
        val wgStartBroadcast = dispatcher.broadcasts.last()
        assertEquals(VpnController.ACTION_WG_TUNNEL_CONNECT, wgStartBroadcast.action)
        assertEquals("unifi-au-syd", wgStartBroadcast.getStringExtra(VpnController.EXTRA_WG_TUNNEL_NAME))
        assertEquals("wg-key-1", wgStartBroadcast.getStringExtra(VpnController.EXTRA_WG_REMOTE_KEY))
        assertEquals(1, dispatcher.startedActivities.size)

        // Simulate WG Tunnel coming up on a new handle.
        vpnState.value = VpnConnectionState(isConnected = true, networkHandle = 200L)
        val secondResult = secondActivation.await()

        assertTrue(secondResult is ActivationProgress.Active)
        assertEquals(
            listOf("com.cbs.ott", "com.amazon.avod.thirdpartyclient"),
            dispatcher.startedActivities.map { it.`package` }
        )
    }

    @Test
    fun activationCoordinator_switchingBackends_whenOldVpnNeverTearsDown_doesNotLaunchApp() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        // Pre-existing VPN connection (e.g., Tailscale on handle 100) that refuses to disconnect.
        val vpnState = MutableStateFlow(
            VpnConnectionState(
                isConnected = true,
                networkHandle = 100L,
                ownerBackend = VpnBackend.TAILSCALE
            )
        )

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            startMockLocation = {},
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
        // Must NOT launch streaming app through the stale Tailscale connection!
        assertTrue(dispatcher.startedActivities.isEmpty())
    }

    @Test
    fun activationCoordinator_wgTunnelWithoutKey_warnsAndSkipsBroadcastAndLaunch() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            startMockLocation = {},
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
    fun activationCoordinator_withNoneBackend_proceedsImmediatelyWithoutWaiting() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        var mockStarted = false

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            startMockLocation = { mockStarted = true },
            stopMockLocation = {},
            vpnWaitTimeoutMs = 8_000L
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

        val result = coordinator.activate(profile)

        assertTrue(mockStarted)
        assertTrue(dispatcher.broadcasts.isEmpty())
        assertTrue(result is ActivationProgress.Active)
        assertEquals(1, dispatcher.startedActivities.size)
        assertEquals("uk.co.bbc.iplayer", dispatcher.startedActivities.single().`package`)
    }

    @Test
    fun activationCoordinator_whenAutoLaunchDisabled_skipsLaunchingTargetApp() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            startMockLocation = {},
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

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnStateFlow = vpnState,
            startMockLocation = {},
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
}
