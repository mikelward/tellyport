package tv.tellyport.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import tv.tellyport.TellyportApplication
import tv.tellyport.data.ProfileRepository
import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.VpnBackend
import tv.tellyport.location.MockLocationPermissionChecker
import tv.tellyport.location.MockLocationPermissionStatus
import tv.tellyport.location.MockLocationState
import tv.tellyport.location.MockLocationStatus
import tv.tellyport.service.MockLocationService
import tv.tellyport.vpn.ActivationProgress
import tv.tellyport.vpn.ProfileActivationCoordinator
import tv.tellyport.vpn.VpnConnectionState
import tv.tellyport.vpn.VpnController
import tv.tellyport.vpn.VpnNetworkMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class TellyportDestination {
    data object Dashboard : TellyportDestination()
    data object Diagnostics : TellyportDestination()
    data class ProfileEditor(val editingProfile: RegionalProfile?) : TellyportDestination()
}

data class TellyportUiState(
    val profiles: List<RegionalProfile> = emptyList(),
    val wgRemoteControlKey: String = "",
    val mockLocationStatus: MockLocationStatus = MockLocationStatus(),
    val vpnState: VpnConnectionState = VpnConnectionState(),
    val permissionStatus: MockLocationPermissionStatus = MockLocationPermissionStatus(
        isMockLocationAppSelected = false,
        hasFineLocationPermission = false,
        hasNotificationPermission = true,
        adbCommand = MockLocationPermissionChecker.buildAdbMockLocationCommand("tv.tellyport")
    ),
    val activationProgress: ActivationProgress = ActivationProgress.Idle,
    val destination: TellyportDestination = TellyportDestination.Dashboard,
    val bannerMessage: String? = null
)

class TellyportViewModel(
    private val appContext: Context,
    private val profileRepository: ProfileRepository,
    private val vpnController: VpnController,
    private val vpnNetworkMonitor: VpnNetworkMonitor,
    private val permissionChecker: MockLocationPermissionChecker,
    private val startMockLocation: (RegionalProfile, Long) -> Unit = { profile, sessionId ->
        MockLocationService.start(appContext, profile, sessionId)
    },
    private val stopMockLocation: () -> Unit = {
        MockLocationService.stop(appContext)
    },
    private val vpnStateFlow: StateFlow<VpnConnectionState> = vpnNetworkMonitor.state,
    private val mockLocationStatusFlow: StateFlow<MockLocationStatus> = MockLocationState.status,
    private val permissionStatusProvider: () -> MockLocationPermissionStatus = {
        permissionChecker.checkStatus()
    },
    private val vpnWaitTimeoutMs: Long = ProfileActivationCoordinator.DEFAULT_VPN_WAIT_TIMEOUT_MS,
    private val mockLocationWaitTimeoutMs: Long = ProfileActivationCoordinator.DEFAULT_MOCK_LOCATION_WAIT_TIMEOUT_MS
) : ViewModel() {

    private val wgRemoteControlKeyFlow: StateFlow<String> = profileRepository.wgRemoteControlKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val coordinator = ProfileActivationCoordinator(
        vpnController = vpnController,
        vpnStateFlow = vpnStateFlow,
        mockLocationStatusFlow = mockLocationStatusFlow,
        startMockLocation = startMockLocation,
        stopMockLocation = stopMockLocation,
        wgRemoteControlKeyProvider = { wgRemoteControlKeyFlow.value },
        vpnWaitTimeoutMs = vpnWaitTimeoutMs,
        mockLocationWaitTimeoutMs = mockLocationWaitTimeoutMs
    )

    private val _permissionStatus = MutableStateFlow(permissionStatusProvider())
    private val _destination = MutableStateFlow<TellyportDestination>(TellyportDestination.Dashboard)
    private val _bannerMessage = MutableStateFlow<String?>(null)

    private var activationJob: Job? = null
    private var activatingProfileId: String? = null

    private val coreFlows = combine(
        profileRepository.profiles,
        mockLocationStatusFlow,
        vpnStateFlow,
        _permissionStatus,
        coordinator.progress
    ) { profiles, mockStatus, vpnState, perms, progress ->
        TellyportUiState(
            profiles = profiles,
            mockLocationStatus = mockStatus,
            vpnState = vpnState,
            permissionStatus = perms,
            activationProgress = progress
        )
    }

    val uiState: StateFlow<TellyportUiState> = combine(
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
        initialValue = TellyportUiState(permissionStatus = _permissionStatus.value)
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
        _destination.value = TellyportDestination.Diagnostics
    }

    fun openCreateProfile() {
        _destination.value = TellyportDestination.ProfileEditor(editingProfile = null)
    }

    fun openEditProfile(profile: RegionalProfile) {
        _destination.value = TellyportDestination.ProfileEditor(editingProfile = profile)
    }

    fun navigateToDashboard() {
        refreshDiagnostics()
        _destination.value = TellyportDestination.Dashboard
    }

    fun dismissBannerMessage() {
        _bannerMessage.value = null
    }

    fun activateProfile(profile: RegionalProfile) {
        refreshDiagnostics()
        val perms = _permissionStatus.value
        if (!perms.hasFineLocationPermission) {
            _bannerMessage.value = "Grant Location permission first so Tellyport can run its foreground mock location service."
            return
        }
        if (!perms.isMockLocationAppSelected) {
            _bannerMessage.value = "Select Tellyport as the mock location app in Developer Options (or run the ADB command in Setup)."
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
            _destination.value = TellyportDestination.Dashboard
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
        val isDeletingActiveProfile = mockLocationStatusFlow.value.activeProfileId == profile.id
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
        clipboard?.setPrimaryClip(ClipData.newPlainText("Tellyport ADB Mock Location", command))
        _bannerMessage.value = "Copied ADB command to clipboard."
    }

    override fun onCleared() {
        vpnNetworkMonitor.stop()
        super.onCleared()
    }

    class Factory(private val app: TellyportApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TellyportViewModel(
                appContext = app.applicationContext,
                profileRepository = app.profileRepository,
                vpnController = app.vpnController,
                vpnNetworkMonitor = app.vpnNetworkMonitor,
                permissionChecker = app.permissionChecker
            ) as T
        }
    }
}
