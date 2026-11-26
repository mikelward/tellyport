package com.warpmo.app.ui

import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.warpmo.app.data.ProfileRepository
import com.warpmo.app.data.ProfileStoreSerializer
import com.warpmo.app.data.RegionalProfile
import com.warpmo.app.data.VpnBackend
import com.warpmo.app.location.MockLocationPermissionChecker
import com.warpmo.app.location.MockLocationPermissionStatus
import com.warpmo.app.location.MockLocationState
import com.warpmo.app.vpn.ActivationProgress
import com.warpmo.app.vpn.IntentDispatcher
import com.warpmo.app.vpn.VpnConnectionState
import com.warpmo.app.vpn.VpnController
import com.warpmo.app.vpn.VpnNetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WarpmoViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        MockLocationState.markInactive()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        MockLocationState.markInactive()
    }

    private class FakeIntentDispatcher : IntentDispatcher {
        val broadcasts = mutableListOf<Intent>()
        val startedActivities = mutableListOf<Intent>()

        override fun sendBroadcast(intent: Intent) {
            broadcasts.add(intent)
        }

        override fun startActivity(intent: Intent): Boolean {
            startedActivities.add(intent)
            return true
        }

        override fun resolveLaunchIntent(packageName: String): Intent {
            return Intent(Intent.ACTION_MAIN).apply { `package` = packageName }
        }
    }

    @Test
    fun deletingProfileDuringPendingActivation_cancelsActivationAndNeverLaunchesDeletedApp() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(storeJob + testDispatcher)
        val dataStore = DataStoreFactory.create(
            serializer = ProfileStoreSerializer,
            scope = storeScope,
            produceFile = { tempFolder.root.resolve("vm_delete_pending.json") }
        )
        val repository = ProfileRepository(dataStore)
        val dispatcher = FakeIntentDispatcher()
        val vpnController = VpnController(dispatcher)
        val vpnState = MutableStateFlow(VpnConnectionState(isConnected = false))
        var stopMockCount = 0

        val readyPermissions = MockLocationPermissionStatus(
            isMockLocationAppSelected = true,
            hasFineLocationPermission = true,
            hasNotificationPermission = true,
            adbCommand = MockLocationPermissionChecker.buildAdbMockLocationCommand("com.warpmo.app")
        )

        val viewModel = WarpmoViewModel(
            appContext = context,
            profileRepository = repository,
            vpnController = vpnController,
            vpnNetworkMonitor = VpnNetworkMonitor(context),
            permissionChecker = MockLocationPermissionChecker(context),
            startMockLocation = {},
            stopMockLocation = { stopMockCount++ },
            vpnStateFlow = vpnState,
            permissionStatusProvider = { readyPermissions },
            vpnWaitTimeoutMs = 8_000L
        )

        try {
            runCurrent()

            val targetProfile = RegionalProfile(
                id = "pending-us",
                name = "Pending US",
                regionCode = "US",
                latitude = 40.7128,
                longitude = -74.0060,
                vpnBackend = VpnBackend.TAILSCALE,
                targetAppPackage = "com.cbs.ott",
                autoLaunchApp = true
            )
            repository.upsertProfile(targetProfile)
            runCurrent()

            // Start activation (now waiting for VPN up to 8s).
            viewModel.activateProfile(targetProfile)
            advanceTimeBy(1_000L)
            runCurrent()

            // Delete the profile while activation is still waiting for the VPN.
            viewModel.deleteProfile(targetProfile)
            runCurrent()

            // Even if VPN subsequently connects and the full timeout elapses, the deleted
            // profile must stay cancelled: no app launch, progress remains Idle, mock stopped.
            vpnState.value = VpnConnectionState(isConnected = true, networkHandle = 500L)
            advanceTimeBy(10_000L)
            runCurrent()

            assertTrue(dispatcher.startedActivities.isEmpty())
            assertTrue(stopMockCount >= 1)
            assertEquals(ActivationProgress.Idle, viewModel.uiState.first().activationProgress)
            assertTrue(repository.profiles.first().none { it.id == targetProfile.id })
        } finally {
            storeJob.cancelAndJoin()
        }
    }
}
