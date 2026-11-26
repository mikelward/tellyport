package com.warpmo.app.vpn

import android.content.Intent
import com.warpmo.app.data.RegionalProfile
import com.warpmo.app.data.VpnBackend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
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
            "com.cbs.ott",
            "uk.co.bbc.iplayer"
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
    fun wgTunnelIntents_includeTunnelNameExtraWhenProvided() {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)

        val connectIntent = controller.buildWgTunnelConnectIntent("  unifi-au-syd  ")
        assertEquals(VpnController.ACTION_WG_TUNNEL_CONNECT, connectIntent.action)
        assertEquals(VpnController.WG_TUNNEL_PACKAGE, connectIntent.`package`)
        assertEquals("unifi-au-syd", connectIntent.getStringExtra(VpnController.EXTRA_WG_TUNNEL_NAME))

        val disconnectIntent = controller.buildWgTunnelDisconnectIntent("unifi-au-syd")
        assertEquals(VpnController.ACTION_WG_TUNNEL_DISCONNECT, disconnectIntent.action)
        assertEquals(VpnController.WG_TUNNEL_PACKAGE, disconnectIntent.`package`)
        assertEquals("unifi-au-syd", disconnectIntent.getStringExtra(VpnController.EXTRA_WG_TUNNEL_NAME))

        val blankIntent = controller.buildWgTunnelConnectIntent("   ")
        assertFalse(blankIntent.hasExtra(VpnController.EXTRA_WG_TUNNEL_NAME))
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
    fun openTailscaleApp_launchesTailscalePackageWhenInstalled() {
        val dispatcher = RecordingIntentDispatcher(installedPackages = setOf(VpnController.TAILSCALE_PACKAGE))
        val controller = VpnController(dispatcher)

        assertTrue(controller.openTailscaleApp())
        assertEquals(1, dispatcher.startedActivities.size)
        assertEquals(VpnController.TAILSCALE_PACKAGE, dispatcher.startedActivities.single().`package`)
    }

    @Test
    fun activationCoordinator_waitsForVpnThenLaunchesTargetApp() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnConnected = MutableStateFlow(false)
        val startedMockProfiles = mutableListOf<RegionalProfile>()
        var stoppedMockCount = 0

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnConnectedFlow = vpnConnected,
            startMockLocation = { startedMockProfiles.add(it) },
            stopMockLocation = { stoppedMockCount++ },
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

        // Advance 2 seconds: VPN connect broadcast and mock location must already be started,
        // while target app has not launched yet because VPN is not connected.
        advanceTimeBy(2_000L)
        assertEquals(1, dispatcher.broadcasts.size)
        assertEquals(VpnController.ACTION_TAILSCALE_CONNECT, dispatcher.broadcasts.single().action)
        assertEquals(listOf(profile), startedMockProfiles)
        assertTrue(dispatcher.startedActivities.isEmpty())

        // Simulate TRANSPORT_VPN coming up at t=2s.
        vpnConnected.value = true
        val result = resultDeferred.await()

        assertTrue(result is ActivationProgress.Active)
        assertEquals(1, dispatcher.startedActivities.size)
        assertEquals("com.cbs.ott", dispatcher.startedActivities.single().`package`)

        // Verify deactivateAll cleans up mock location and sends disconnects.
        coordinator.deactivateAll()
        assertEquals(1, stoppedMockCount)
        assertEquals(3, dispatcher.broadcasts.size)
        assertEquals(VpnController.ACTION_TAILSCALE_DISCONNECT, dispatcher.broadcasts[1].action)
        assertEquals(VpnController.ACTION_WG_TUNNEL_DISCONNECT, dispatcher.broadcasts[2].action)
    }

    @Test
    fun activationCoordinator_withNoneBackend_proceedsImmediatelyWithoutWaiting() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnConnected = MutableStateFlow(false)
        var mockStarted = false

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnConnectedFlow = vpnConnected,
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
        val vpnConnected = MutableStateFlow(true)

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnConnectedFlow = vpnConnected,
            startMockLocation = {},
            stopMockLocation = {},
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

        val result = coordinator.activate(profile)
        assertNotNull(result)
        assertTrue(result is ActivationProgress.Active)
        assertEquals(1, dispatcher.broadcasts.size)
        assertEquals("unifi-au-syd", dispatcher.broadcasts.single().getStringExtra(VpnController.EXTRA_WG_TUNNEL_NAME))
        assertTrue(dispatcher.startedActivities.isEmpty())
    }

    @Test
    fun activationCoordinator_whenVpnTimesOut_reportsWarningAfter8Seconds() = runTest {
        val dispatcher = RecordingIntentDispatcher()
        val controller = VpnController(dispatcher)
        val vpnConnected = MutableStateFlow(false)

        val coordinator = ProfileActivationCoordinator(
            vpnController = controller,
            vpnConnectedFlow = vpnConnected,
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
        assertEquals(1, dispatcher.startedActivities.size)
        assertEquals("com.cbs.ott", dispatcher.startedActivities.single().`package`)
    }
}
