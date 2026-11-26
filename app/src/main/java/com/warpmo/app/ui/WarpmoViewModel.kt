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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class WarpmoDestination {
    data object Dashboard : WarpmoDestination()
    data object Diagnostics : WarpmoDestination()
    data class ProfileEditor(val editingProfile: RegionalProfile?) : WarpmoDestination()
}

data class WarpmoUiState(
    val profiles: List<RegionalProfile> = emptyList(),
    val wgRemoteControlKey: String = "",
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
    private val permissionChecker: MockLocationPermissionChecker,
    private val startMockLocation: (RegionalProfile) -> Unit = { profile ->
        MockLocationService.start(appContext, profile)
    },
    private val stopMockLocation: () -> Unit = {
        MockLocationService.stop(appContext)
    },
    private val vpnStateFlow: StateFlow<VpnConnectionState> = vpnNetworkMonitor.state,
    private val permissionStatusProvider: () -> MockLocationPermissionStatus = {
        permissionChecker.checkStatus()
    },
    private val vpnWaitTimeoutMs: Long = ProfileActivationCoordinator.DEFAULT_VPN_WAIT_TIMEOUT_MS
) : ViewModel() {

    private val wgRemoteControlKeyFlow: StateFlow<String> = profileRepository.wgRemoteControlKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val coordinator = ProfileActivationCoordinator(
        vpnController = vpnController,
        vpnStateFlow = vpnStateFlow,
        startMockLocation = startMockLocation,
        stopMockLocation = stopMockLocation,
        wgRemoteControlKeyProvider = { wgRemoteControlKeyFlow.value },
        vpnWaitTimeoutMs = vpnWaitTimeoutMs
    )

    private val _permissionStatus = MutableStateFlow(permissionStatusProvider())
    private val _destination = MutableStateFlow<WarpmoDestination>(WarpmoDestination.Dashboard)
    private val _bannerMessage = MutableStateFlow<String?>(null)

    private var activationJob: Job? = null
    private var activatingProfileId: String? = null

    private val coreFlows = combine(
        profileRepository.profiles,
        MockLocationState.status,
        vpnStateFlow,
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
        wgRemoteControlKeyFlow,
        _destination,
        _bannerMessage
    ) { base, wgKey, destination, banner ->
        base.copy(
            wgRemoteControlKey = wgKey,
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
        _permissionStatus.value = permissionStatusProvider()
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

        val previousJob = activationJob
        activatingProfileId = profile.id
        activationJob = viewModelScope.launch {
            previousJob?.cancelAndJoin()
            _bannerMessage.value = null
            try {
                coordinator.activate(profile)
            } finally {
                if (activatingProfileId == profile.id) {
                    activatingProfileId = null
                }
            }
        }
    }

    fun disconnectAll() {
        val pendingJob = activationJob
        activationJob = null
        activatingProfileId = null
        viewModelScope.launch {
            pendingJob?.cancelAndJoin()
            coordinator.deactivateAll()
            _bannerMessage.value = "Stopped mock location and sent VPN disconnect intents."
        }
    }

    fun saveProfile(profile: RegionalProfile, updatedWgRemoteControlKey: String? = null) {
        viewModelScope.launch {
            if (updatedWgRemoteControlKey != null) {
                profileRepository.setWgRemoteControlKey(updatedWgRemoteControlKey)
            }
            profileRepository.upsertProfile(profile)
            _destination.value = WarpmoDestination.Dashboard
            _bannerMessage.value = "Saved profile \"${profile.name}\"."
        }
    }

    fun saveWgRemoteControlKey(key: String) {
        viewModelScope.launch {
            profileRepository.setWgRemoteControlKey(key)
            _bannerMessage.value = if (key.isBlank()) {
                "Cleared WG Tunnel remote control key."
            } else {
                "Saved WG Tunnel remote control key."
            }
        }
    }

    fun deleteProfile(profile: RegionalProfile) {
        val isDeletingPendingActivation = activatingProfileId == profile.id ||
            (coordinator.progress.value as? ActivationProgress.InProgress)?.profileId == profile.id
        val isDeletingActiveProfile = MockLocationState.status.value.activeProfileId == profile.id
        val pendingJob = if (isDeletingPendingActivation || isDeletingActiveProfile) {
            activationJob.also {
                activationJob = null
                activatingProfileId = null
            }
        } else {
            null
        }

        viewModelScope.launch {
            if (pendingJob != null) {
                pendingJob.cancelAndJoin()
            }
            if (isDeletingPendingActivation || isDeletingActiveProfile) {
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

    fun openWgTunnelApp() {
        val opened = vpnController.openWgTunnelApp()
        _bannerMessage.value = if (opened) {
            "Opened WG Tunnel. Enable Remote App Control in Settings and copy the key."
        } else {
            "WG Tunnel (${VpnController.WG_TUNNEL_PACKAGE}) is not installed on this device."
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
