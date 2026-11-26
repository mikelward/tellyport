package tv.tellyport.ui

import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
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
import tv.tellyport.data.ProfileRepository
import tv.tellyport.data.ProfileStoreSerializer
import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.VpnBackend
import tv.tellyport.location.MockLocationPermissionChecker
import tv.tellyport.location.MockLocationPermissionStatus
import tv.tellyport.location.MockLocationState
import tv.tellyport.location.MockLocationStatus
import tv.tellyport.shizuku.ShizukuForceStopper
import tv.tellyport.shizuku.ShizukuStatus
import tv.tellyport.vpn.ActivationProgress
import tv.tellyport.vpn.IntentDispatcher
import tv.tellyport.vpn.ProfileActivationCoordinator
import tv.tellyport.vpn.VpnConnectionState
import tv.tellyport.vpn.VpnController
import tv.tellyport.vpn.VpnNetworkMonitor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TellyportViewModelTest {

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
    fun deletingProfileDuringPendingActivation_cancelsActivationStopsNamedWgTunnelAndNeverLaunchesDeletedApp() = runTest(testDispatcher) {
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
        val mockStatus = MutableStateFlow(MockLocationStatus())
        var stopMockCount = 0

        val coordinator = ProfileActivationCoordinator(
            vpnController = vpnController,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            startMockLocation = { profile, sessionId ->
                mockStatus.value = MockLocationStatus(
                    isActive = true,
                    sessionId = sessionId,
                    activeProfileId = profile.id,
                    activeProfileName = profile.name,
                    configuredLatitude = profile.latitude,
                    configuredLongitude = profile.longitude,
                    injectionCount = 1L
                )
            },
            stopMockLocation = { stopMockCount++ },
            wgRemoteControlKeyProvider = { "wg-key-delete-test" },
            persistedSessionProvider = { repository.getActiveSessionSnapshot() },
            persistActiveSession = { repository.setActiveSession(it) },
            vpnWaitTimeoutMs = 8_000L
        )

        val readyPermissions = MockLocationPermissionStatus(
            isMockLocationAppSelected = true,
            hasFineLocationPermission = true,
            hasNotificationPermission = true,
            adbCommand = MockLocationPermissionChecker.buildAdbMockLocationCommand("tv.tellyport")
        )

        val viewModel = TellyportViewModel(
            appContext = context,
            profileRepository = repository,
            vpnController = vpnController,
            vpnNetworkMonitor = VpnNetworkMonitor(context),
            permissionChecker = MockLocationPermissionChecker(context),
            shizukuForceStopper = ShizukuForceStopper(context),
            coordinator = coordinator,
            vpnStateFlow = vpnState,
            mockLocationStatusFlow = mockStatus,
            permissionStatusProvider = { readyPermissions },
            shizukuStatusProvider = { ShizukuStatus(false, false, false) },
            loadInstalledApps = { emptyList() }
        )

        try {
            repository.setWgRemoteControlKey("wg-key-delete-test")
            runCurrent()

            val targetProfile = RegionalProfile(
                id = "pending-au",
                name = "Pending Sydney",
                regionCode = "AU",
                latitude = -33.8688,
                longitude = 151.2093,
                vpnBackend = VpnBackend.WG_TUNNEL,
                tunnelName = "unifi-au-syd",
                mappedPackages = listOf("au.com.stan.and"),
                autoLaunchApp = true
            )
            repository.upsertProfile(targetProfile)
            runCurrent()

            viewModel.activateProfile(targetProfile)
            advanceTimeBy(1_000L)
            runCurrent()

            viewModel.deleteProfile(targetProfile)
            runCurrent()

            val wgStop = dispatcher.broadcasts.last { it.action == VpnController.ACTION_WG_TUNNEL_DISCONNECT }
            assertEquals("unifi-au-syd", wgStop.getStringExtra(VpnController.EXTRA_WG_TUNNEL_NAME))
            assertEquals("wg-key-delete-test", wgStop.getStringExtra(VpnController.EXTRA_WG_REMOTE_KEY))

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
