package tv.tellyport

import android.app.Application
import tv.tellyport.data.ProfileRepository
import tv.tellyport.location.MockLocationPermissionChecker
import tv.tellyport.vpn.VpnController
import tv.tellyport.vpn.VpnNetworkMonitor

/**
 * Application-scoped dependency container for Tellyport.
 */
class TellyportApplication : Application() {

    lateinit var profileRepository: ProfileRepository
        private set

    lateinit var vpnController: VpnController
        private set

    lateinit var vpnNetworkMonitor: VpnNetworkMonitor
        private set

    lateinit var permissionChecker: MockLocationPermissionChecker
        private set

    override fun onCreate() {
        super.onCreate()
        profileRepository = ProfileRepository.create(this)
        vpnController = VpnController(this)
        vpnNetworkMonitor = VpnNetworkMonitor(this)
        permissionChecker = MockLocationPermissionChecker(this)
    }
}
