package com.warpmo.app

import android.app.Application
import com.warpmo.app.data.ProfileRepository
import com.warpmo.app.location.MockLocationPermissionChecker
import com.warpmo.app.vpn.VpnController
import com.warpmo.app.vpn.VpnNetworkMonitor

/**
 * Application-scoped dependency container for Warpmo.
 */
class WarpmoApplication : Application() {

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
