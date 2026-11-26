package tv.tellyport

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.tellyport.data.ProfileRepository
import tv.tellyport.location.MockLocationPermissionChecker
import tv.tellyport.location.MockLocationState
import tv.tellyport.service.MockLocationService
import tv.tellyport.shizuku.ShizukuForceStopper
import tv.tellyport.vpn.ProfileActivationCoordinator
import tv.tellyport.vpn.VpnController
import tv.tellyport.vpn.VpnNetworkMonitor

/**
 * Application-scoped dependency container for Tellyport so both the Compose UI and
 * background components (`AppLaunchAccessibilityService`, `BootRecoveryReceiver`) share
 * a single [ProfileRepository], [VpnNetworkMonitor], and [ProfileActivationCoordinator].
 */
class TellyportApplication : Application() {

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var profileRepository: ProfileRepository
        private set

    lateinit var vpnController: VpnController
        private set

    lateinit var vpnNetworkMonitor: VpnNetworkMonitor
        private set

    lateinit var permissionChecker: MockLocationPermissionChecker
        private set

    lateinit var shizukuForceStopper: ShizukuForceStopper
        private set

    lateinit var activationCoordinator: ProfileActivationCoordinator
        private set

    private lateinit var wgRemoteControlKeyFlow: StateFlow<String>

    override fun onCreate() {
        super.onCreate()
        profileRepository = ProfileRepository.create(this, applicationScope)
        vpnController = VpnController(this)
        permissionChecker = MockLocationPermissionChecker(this)
        shizukuForceStopper = ShizukuForceStopper(this)

        wgRemoteControlKeyFlow = profileRepository.wgRemoteControlKey
            .stateIn(applicationScope, SharingStarted.Eagerly, "")

        vpnNetworkMonitor = VpnNetworkMonitor(
            context = this,
            onVpnLostCallback = {
                applicationScope.launch {
                    profileRepository.clearActiveSession()
                }
            }
        )
        vpnNetworkMonitor.start()

        activationCoordinator = ProfileActivationCoordinator(
            vpnController = vpnController,
            vpnStateFlow = vpnNetworkMonitor.state,
            refreshLiveVpnState = vpnNetworkMonitor::refreshState,
            mockLocationStatusFlow = MockLocationState.status,
            startMockLocation = { profile, sessionId ->
                MockLocationService.start(applicationContext, profile, sessionId)
            },
            stopMockLocation = {
                MockLocationService.stop(applicationContext)
            },
            wgRemoteControlKeyProvider = { wgRemoteControlKeyFlow.value },
            persistedSessionProvider = { profileRepository.getActiveSessionSnapshot() },
            persistActiveSession = { session -> profileRepository.setActiveSession(session) }
        )

        applicationScope.launch {
            profileRepository.ensureSeeded()
            // Reconcile persisted active session on cold start against live VPN identity.
            val liveVpn = vpnNetworkMonitor.refreshState()
            val persisted = profileRepository.getActiveSessionSnapshot()
            if (persisted != null &&
                !ProfileActivationCoordinator.isPersistedSessionValidForLiveVpn(liveVpn, persisted)
            ) {
                profileRepository.clearActiveSession()
            }
        }
    }
}
