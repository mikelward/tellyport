package com.warpmo.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.warpmo.app.WarpmoApplication
import com.warpmo.app.data.ProfileRepository
import com.warpmo.app.data.RegionalProfile
import com.warpmo.app.data.VpnBackend
import com.warpmo.app.location.MockLocationPermissionChecker
import com.warpmo.app.location.MockLocationPermissionStatus
import com.warpmo.app.location.MockLocationState
import com.warpmo.app.location.MockLocationStatus
import com.warpmo.app.service.MockLocationService
import com.warpmo.app.vpn.ActivationProgress
import com.warpmo.app.vpn.ProfileActivationCoordinator
import com.warpmo.app.vpn.VpnConnectionState
import com.warpmo.app.vpn.VpnController
import com.warpmo.app.vpn.VpnNetworkMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class WarpmoDestination {
    data object Dashboard : WarpmoDestination()
    data object Diagnostics : WarpmoDestination()
    data class ProfileEditor(val editingProfile: RegionalProfile?) : WarpmoDestination()
}

data class WarpmoUiState(
    val profiles: List<RegionalProfile> = emptyList(),
    val mockLocationStatus: MockLocationStatus = MockLocationStatus(),
    val vpnState: VpnConnectionState = VpnConnectionState(),
    val permissionStatus: MockLocationPermissionStatus = MockLocationPermissionStatus(
        isMockLocationAppSelected = false,
        hasFineLocationPermission = false,
        hasNotificationPermission = true,
        adbCommand = MockLocationPermissionChecker.buildAdbMockLocationCommand("com.warpmo.app")
    ),
    val activationProgress: ActivationProgress = ActivationProgress.Idle,
    val destination: WarpmoDestination = WarpmoDestination.Dashboard,
    val bannerMessage: String? = null
)

class WarpmoViewModel(
    private val appContext: Context,
    private val profileRepository: ProfileRepository,
    private val vpnController: VpnController,
    private val vpnNetworkMonitor: VpnNetworkMonitor,
    private val permissionChecker: MockLocationPermissionChecker
) : ViewModel() {

    private val vpnConnectedFlow: StateFlow<Boolean> = vpnNetworkMonitor.state
        .map { it.isConnected }
        .stateIn(viewModelScope, SharingStarted.Eagerly, vpnNetworkMonitor.state.value.isConnected)

    private val coordinator = ProfileActivationCoordinator(
        vpnController = vpnController,
        vpnConnectedFlow = vpnConnectedFlow,
        startMockLocation = { profile -> MockLocationService.start(appContext, profile) },
        stopMockLocation = { MockLocationService.stop(appContext) }
    )

    private val _permissionStatus = MutableStateFlow(permissionChecker.checkStatus())
    private val _destination = MutableStateFlow<WarpmoDestination>(WarpmoDestination.Dashboard)
    private val _bannerMessage = MutableStateFlow<String?>(null)

    private var activationJob: Job? = null

    private val coreFlows = combine(
        profileRepository.profiles,
        MockLocationState.status,
        vpnNetworkMonitor.state,
        _permissionStatus,
        coordinator.progress
    ) { profiles, mockStatus, vpnState, perms, progress ->
        WarpmoUiState(
            profiles = profiles,
            mockLocationStatus = mockStatus,
            vpnState = vpnState,
            permissionStatus = perms,
            activationProgress = progress
        )
    }

    val uiState: StateFlow<WarpmoUiState> = combine(
        coreFlows,
        _destination,
        _bannerMessage
    ) { base, destination, banner ->
        base.copy(
            destination = destination,
            bannerMessage = banner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = WarpmoUiState(permissionStatus = _permissionStatus.value)
    )

    init {
        vpnNetworkMonitor.start()
        viewModelScope.launch {
            profileRepository.ensureSeeded()
        }
    }

    fun refreshDiagnostics() {
        _permissionStatus.value = permissionChecker.checkStatus()
        vpnNetworkMonitor.refreshState()
    }

    fun openDiagnostics() {
        refreshDiagnostics()
        _destination.value = WarpmoDestination.Diagnostics
    }

    fun openCreateProfile() {
        _destination.value = WarpmoDestination.ProfileEditor(editingProfile = null)
    }

    fun openEditProfile(profile: RegionalProfile) {
        _destination.value = WarpmoDestination.ProfileEditor(editingProfile = profile)
    }

    fun navigateToDashboard() {
        refreshDiagnostics()
        _destination.value = WarpmoDestination.Dashboard
    }

    fun dismissBannerMessage() {
        _bannerMessage.value = null
    }

    fun activateProfile(profile: RegionalProfile) {
        refreshDiagnostics()
        val perms = _permissionStatus.value
        if (!perms.hasFineLocationPermission) {
            _bannerMessage.value = "Grant Location permission first so Warpmo can run its foreground mock location service."
            return
        }
        if (!perms.isMockLocationAppSelected) {
            _bannerMessage.value = "Select Warpmo as the mock location app in Developer Options (or run the ADB command in Setup)."
            return
        }

        activationJob?.cancel()
        activationJob = viewModelScope.launch {
            _bannerMessage.value = null
            coordinator.activate(profile)
        }
    }

    fun disconnectAll() {
        activationJob?.cancel()
        activationJob = null
        coordinator.deactivateAll()
        _bannerMessage.value = "Stopped mock location and sent VPN disconnect intents."
    }

    fun saveProfile(profile: RegionalProfile) {
        viewModelScope.launch {
            profileRepository.upsertProfile(profile)
            _destination.value = WarpmoDestination.Dashboard
            _bannerMessage.value = "Saved profile \"${profile.name}\"."
        }
    }

    fun deleteProfile(profile: RegionalProfile) {
        viewModelScope.launch {
            if (MockLocationState.status.value.activeProfileId == profile.id) {
                coordinator.deactivateAll()
            }
            profileRepository.deleteProfile(profile.id)
            _bannerMessage.value = "Deleted profile \"${profile.name}\"."
        }
    }

    fun resetDefaultProfiles() {
        viewModelScope.launch {
            profileRepository.resetDefaults()
            _bannerMessage.value = "Restored default US, UK, and AU profiles."
        }
    }

    fun openTailscaleApp() {
        val opened = vpnController.openTailscaleApp()
        _bannerMessage.value = if (opened) {
            "Opened Tailscale. Pick or verify your Exit Node."
        } else {
            "Tailscale (${VpnController.TAILSCALE_PACKAGE}) is not installed on this device."
        }
    }

    fun triggerTailscaleConnect() {
        vpnController.connect(
            RegionalProfile(
                name = "Tailscale",
                regionCode = "VPN",
                latitude = 0.0,
                longitude = 0.0,
                vpnBackend = VpnBackend.TAILSCALE
            )
        )
        _bannerMessage.value = "Sent Tailscale CONNECT_VPN broadcast."
    }

    fun triggerTailscaleDisconnect() {
        vpnController.disconnect(VpnBackend.TAILSCALE)
        _bannerMessage.value = "Sent Tailscale DISCONNECT_VPN broadcast."
    }

    fun openDeveloperOptions(): Boolean {
        return try {
            val intent: Intent = permissionChecker.buildDeveloperOptionsIntent()
            appContext.startActivity(intent)
            true
        } catch (_: Exception) {
            _bannerMessage.value = "Developer Options unavailable. Enable it under Settings -> About -> Build number (tap 7x)."
            false
        }
    }

    fun copyAdbCommandToClipboard() {
        val command = permissionChecker.getAdbMockLocationCommand()
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Warpmo ADB Mock Location", command))
        _bannerMessage.value = "Copied ADB command to clipboard."
    }

    override fun onCleared() {
        vpnNetworkMonitor.stop()
        super.onCleared()
    }

    class Factory(private val app: WarpmoApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return WarpmoViewModel(
                appContext = app.applicationContext,
                profileRepository = app.profileRepository,
                vpnController = app.vpnController,
                vpnNetworkMonitor = app.vpnNetworkMonitor,
                permissionChecker = app.permissionChecker
            ) as T
        }
    }
}
