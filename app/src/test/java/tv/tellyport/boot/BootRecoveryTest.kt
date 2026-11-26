package tv.tellyport.boot

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.tellyport.data.BootRecoveryDiagnostics

@OptIn(ExperimentalCoroutinesApi::class)
class BootRecoveryTest {

    private class FakeBootPlatform(
        private val networkResult: NetworkReadyResult,
        private val writeSecureGranted: Boolean,
        private val enableWifiSuccess: Boolean = true,
        private val shizukuAliveStates: MutableList<Boolean> = mutableListOf(false, true),
        private val startShizukuResult: Boolean = true
    ) : BootRecoveryPlatform {
        var enableWirelessDebuggingCalled = false
        var attemptStartShizukuCalled = false

        override suspend fun awaitWifiOrEthernet(timeoutMs: Long): NetworkReadyResult = networkResult

        override fun hasWriteSecureSettingsPermission(): Boolean = writeSecureGranted

        override fun enableWirelessDebugging(): Boolean {
            enableWirelessDebuggingCalled = true
            return enableWifiSuccess
        }

        override fun isShizukuBinderAlive(): Boolean {
            return if (shizukuAliveStates.size > 1) {
                shizukuAliveStates.removeAt(0)
            } else {
                shizukuAliveStates.firstOrNull() ?: false
            }
        }

        override fun attemptStartShizuku(): Boolean {
            attemptStartShizukuCalled = true
            return startShizukuResult
        }

        override fun currentTimeEpochMs(): Long = 1_730_000_000_000L
    }

    @Test
    fun bootRecovery_enablesAdbWifiAndWakesOfflineShizukuOnceEthernetReady() = runTest {
        val platform = FakeBootPlatform(
            networkResult = NetworkReadyResult(connected = true, transportLabel = "Ethernet"),
            writeSecureGranted = true,
            shizukuAliveStates = mutableListOf(false, true)
        )
        var recorded: BootRecoveryDiagnostics? = null

        val helper = ShizukuBootHelper(
            platform = platform,
            recordDiagnostics = { recorded = it },
            shizukuRecheckDelayMs = 100L
        )

        val result = helper.runBootRecovery()

        assertTrue(platform.enableWirelessDebuggingCalled)
        assertTrue(platform.attemptStartShizukuCalled)
        assertEquals(result, recorded)
        assertTrue(result.networkConnected)
        assertEquals("Ethernet", result.networkType)
        assertTrue(result.adbWifiEnabled)
        assertTrue(result.shizukuRunning)
        assertTrue(result.shizukuStartAttempted)
    }

    @Test
    fun bootRecovery_withoutWriteSecureSettings_skipsAdbWifiToggle() = runTest {
        val platform = FakeBootPlatform(
            networkResult = NetworkReadyResult(connected = true, transportLabel = "Wi-Fi"),
            writeSecureGranted = false,
            shizukuAliveStates = mutableListOf(true)
        )

        val helper = ShizukuBootHelper(
            platform = platform,
            recordDiagnostics = {},
            shizukuRecheckDelayMs = 0L
        )

        val result = helper.runBootRecovery()

        assertFalse(platform.enableWirelessDebuggingCalled)
        assertFalse(platform.attemptStartShizukuCalled)
        assertFalse(result.writeSecureSettingsGranted)
        assertFalse(result.adbWifiEnabled)
        assertTrue(result.shizukuRunning)
    }

    @Test
    fun bootRecovery_whenNetworkTimesOut_skipsRecoveryAndRecordsOfflineDiagnostics() = runTest {
        val platform = FakeBootPlatform(
            networkResult = NetworkReadyResult(connected = false, transportLabel = "Timed out (60s)"),
            writeSecureGranted = true,
            shizukuAliveStates = mutableListOf(false)
        )
        var recorded: BootRecoveryDiagnostics? = null

        val helper = ShizukuBootHelper(
            platform = platform,
            recordDiagnostics = { recorded = it },
            shizukuRecheckDelayMs = 0L
        )

        val result = helper.runBootRecovery()

        assertFalse(platform.enableWirelessDebuggingCalled)
        assertFalse(platform.attemptStartShizukuCalled)
        assertFalse(result.networkConnected)
        assertFalse(result.adbWifiEnabled)
        assertEquals(result, recorded)
    }
}
