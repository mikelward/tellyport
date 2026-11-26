package tv.tellyport.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.tellyport.TellyportApplication
import tv.tellyport.accessibility.AccessibilityServiceState
import tv.tellyport.data.ActiveVpnSession
import tv.tellyport.data.AutoDisconnectSettings
import tv.tellyport.data.BootRecoveryDiagnostics
import tv.tellyport.data.ProfileRepository
import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.SingleTailscaleConstraintException
import tv.tellyport.data.VpnBackend
import tv.tellyport.data.WorldCity
import tv.tellyport.location.MockLocationPermissionChecker
import tv.tellyport.location.MockLocationPermissionStatus
import tv.tellyport.location.MockLocationState
import tv.tellyport.location.MockLocationStatus
import tv.tellyport.service.MockLocationService
import tv.tellyport.shizuku.ShizukuForceStopper
import tv.tellyport.shizuku.ShizukuStatus
import tv.tellyport.vpn.ActivationProgress
import tv.tellyport.vpn.ProfileActivationCoordinator
import tv.tellyport.vpn.VpnConnectionState
import tv.tellyport.vpn.VpnController
import tv.tellyport.vpn.VpnNetworkMonitor

data class InstalledTvApp(
    val label: String,
    val packageName: String
)

sealed class TellyportDestination {
    data object Dashboard : TellyportDestination()
    data object AppConfigurator : TellyportDestination()
    data object WorldCityPicker : TellyportDestination()
    data object Diagnostics : TellyportDestination()
    data class ProfileEditor(
        val editingProfile: RegionalProfile?,
        val initialCity: WorldCity? = null,
        val returnTo: TellyportDestination = Dashboard
    ) : TellyportDestination()
}

data class TellyportUiState(
    val profiles: List<RegionalProfile> = emptyList(),
    val wgRemoteControlKey: String = "",
    val activeSession: ActiveVpnSession? = null,
    val autoDisconnectSettings: AutoDisconnectSettings = AutoDisconnectSettings(),
    val bootRecoveryDiagnostics: BootRecoveryDiagnostics? = null,
    val mockLocationStatus: MockLocationStatus = MockLocationStatus(),
    val vpnState: VpnConnectionState = VpnConnectionState(),
    val permissionStatus: MockLocationPermissionStatus = MockLocationPermissionStatus(
        isMockLocationAppSelected = false,
        hasFineLocationPermission = false,
        hasNotificationPermission = true,
        adbCommand = MockLocationPermissionChecker.buildAdbMockLocationCommand("tv.tellyport")
    ),
    val shizukuStatus: ShizukuStatus = ShizukuStatus(
        isInstalled = false,
        isBinderAlive = false,
        isPermissionGranted = false
    ),
    val installedApps: List<InstalledTvApp> = emptyList(),
    val isLoadingInstalledApps: Boolean = false,
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
    private val shizukuForceStopper: ShizukuForceStopper,
    private val coordinator: ProfileActivationCoordinator,
    private val vpnStateFlow: StateFlow<VpnConnectionState> = vpnNetworkMonitor.state,
    private val mockLocationStatusFlow: StateFlow<MockLocationStatus> = MockLocationState.status,
    private val permissionStatusProvider: () -> MockLocationPermissionStatus = {
        permissionChecker.checkStatus()
    },
    private val shizukuStatusProvider: () -> ShizukuStatus = {
        shizukuForceStopper.checkStatus()
    },
    private val loadInstalledApps: suspend () -> List<InstalledTvApp> = {
        queryInstalledLaunchableApps(appContext)
    },
    private val startQuickMockLocation: (WorldCity, Long) -> Unit = { city, sessionId ->
        MockLocationService.start(
            context = appContext,
            profile = RegionalProfile(
                id = "city-${city.id}",
                name = city.displayLabel,
                regionCode = city.regionCode,
                latitude = city.latitude,
                longitude = city.longitude,
                altitude = city.altitudeMeters,
                accuracyMeters = 5.0f,
                vpnBackend = VpnBackend.NONE,
                autoLaunchApp = false
            ),
            sessionId = sessionId
        )
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _permissionStatus = MutableStateFlow(permissionStatusProvider())
    private val _shizukuStatus = MutableStateFlow(shizukuStatusProvider())
    private val _installedApps = MutableStateFlow<List<InstalledTvApp>>(emptyList())
    private val _isLoadingInstalledApps = MutableStateFlow(false)
    private val _destination = MutableStateFlow<TellyportDestination>(TellyportDestination.Dashboard)
    private val _bannerMessage = MutableStateFlow<String?>(null)

    private var activationJob: Job? = null
    private var activatingProfileId: String? = null
    private var installedAppsLoadJob: Job? = null
    private var installedAppsLoadSeq: Long = 0L

    private val runtimeFlows = combine(
        profileRepository.store,
        mockLocationStatusFlow,
        vpnStateFlow,
        _permissionStatus,
        coordinator.progress
    ) { store, mockStatus, vpnState, perms, progress ->
        TellyportUiState(
            profiles = store.profiles,
            wgRemoteControlKey = store.wgRemoteControlKey,
            activeSession = store.activeSession,
            autoDisconnectSettings = store.autoDisconnectSettings,
            bootRecoveryDiagnostics = store.bootRecoveryDiagnostics,
            mockLocationStatus = mockStatus,
            vpnState = vpnState,
            permissionStatus = perms,
            activationProgress = progress
        )
    }

    val uiState: StateFlow<TellyportUiState> = combine(
        runtimeFlows,
        _shizukuStatus,
        combine(_installedApps, _isLoadingInstalledApps) { apps, loading -> apps to loading },
        AccessibilityServiceState.isConnected,
        combine(_destination, _bannerMessage) { dest, banner -> dest to banner }
    ) { base, shizuku, appsAndLoading, accessibilityBound, navAndBanner ->
        val updatedPerms = if (accessibilityBound && !base.permissionStatus.isAccessibilityEnabled) {
            base.permissionStatus.copy(isAccessibilityEnabled = true)
        } else {
            base.permissionStatus
        }
        base.copy(
            permissionStatus = updatedPerms,
            shizukuStatus = shizuku,
            installedApps = appsAndLoading.first,
            isLoadingInstalledApps = appsAndLoading.second,
            destination = navAndBanner.first,
            bannerMessage = navAndBanner.second
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TellyportUiState(
            permissionStatus = _permissionStatus.value,
            shizukuStatus = _shizukuStatus.value
        )
    )

    init {
        vpnNetworkMonitor.start()
        viewModelScope.launch {
            profileRepository.ensureSeeded()
        }
    }

    fun refreshDiagnostics() {
        _permissionStatus.value = permissionStatusProvider()
        _shizukuStatus.value = shizukuStatusProvider()
        vpnNetworkMonitor.refreshState()
    }

    fun openAppConfigurator() {
        _bannerMessage.value = null
        refreshInstalledApps()
        _destination.value = TellyportDestination.AppConfigurator
    }

    fun openWorldCityPicker() {
        _bannerMessage.value = null
        refreshDiagnostics()
        _destination.value = TellyportDestination.WorldCityPicker
    }

    fun refreshInstalledApps() {
        refreshInstalledAppsAsync()
    }

    fun openDiagnostics() {
        refreshDiagnostics()
        _destination.value = TellyportDestination.Diagnostics
    }

    fun openCreateProfile(
        returnTo: TellyportDestination = TellyportDestination.Dashboard,
        initialCity: WorldCity? = null
    ) {
        refreshInstalledAppsAsync()
        _destination.value = TellyportDestination.ProfileEditor(
            editingProfile = null,
            initialCity = initialCity,
            returnTo = returnTo
        )
    }

    fun openCreateProfileForCity(city: WorldCity) {
        openCreateProfile(
            returnTo = TellyportDestination.Dashboard,
            initialCity = city
        )
    }

    fun openEditProfile(
        profile: RegionalProfile,
        returnTo: TellyportDestination = TellyportDestination.Dashboard
    ) {
        refreshInstalledAppsAsync()
        _destination.value = TellyportDestination.ProfileEditor(
            editingProfile = profile,
            returnTo = returnTo
        )
    }

    fun navigateBack() {
        when (val current = _destination.value) {
            is TellyportDestination.ProfileEditor -> {
                if (current.returnTo != TellyportDestination.Dashboard) {
                    _destination.value = current.returnTo
                } else {
                    navigateToDashboard()
                }
            }
            TellyportDestination.Dashboard -> Unit
            else -> navigateToDashboard()
        }
    }

    fun navigateToDashboard() {
        refreshDiagnostics()
        _destination.value = TellyportDestination.Dashboard
    }

    fun dismissBannerMessage() {
        _bannerMessage.value = null
    }

    fun startQuickMockCity(city: WorldCity) {
        refreshDiagnostics()
        val perms = _permissionStatus.value
        if (!perms.hasFineLocationPermission) {
            _bannerMessage.value = "Grant Location permission first so Tellyport can mock ${city.displayLabel}."
            return
        }
        if (!perms.isMockLocationAppSelected) {
            _bannerMessage.value = "Select Tellyport as the mock location app in Developer Options (or run the ADB command in Setup)."
            return
        }

        val pendingJob = activationJob
        activationJob = null
        activatingProfileId = null
        viewModelScope.launch {
            pendingJob?.cancelAndJoin()
            coordinator.cancelActiveActivation()
            val sessionId = MockLocationState.nextSessionId()
            startQuickMockLocation(city, sessionId)
            _destination.value = TellyportDestination.Dashboard
            _bannerMessage.value = "Mock location set to ${city.displayLabel} (${city.formattedPopulation} pop.)."
        }
    }

    fun assignAppToProfile(
        packageName: String,
        appLabel: String,
        targetProfileId: String?
    ) {
        viewModelScope.launch {
            val updated = profileRepository.assignPackageToProfile(packageName, targetProfileId)
            if (!updated) {
                _bannerMessage.value = "Could not update routing for $appLabel."
                return@launch
            }
            val displayLabel = appLabel.trim().ifEmpty { packageName }
            if (targetProfileId == null) {
                _bannerMessage.value = "$displayLabel set to Direct (No VPN)."
            } else {
                val profile = profileRepository.getProfilesSnapshot().firstOrNull { it.id == targetProfileId }
                _bannerMessage.value = if (profile != null) {
                    "$displayLabel → ${profile.name} (${profile.regionCode} • ${profile.vpnBackend.displayName})"
                } else {
                    "Updated routing for $displayLabel."
                }
            }
        }
    }

    fun activateProfile(profile: RegionalProfile) {
        refreshDiagnostics()
        if (profile.vpnBackend == VpnBackend.UNCONFIGURED) {
            _bannerMessage.value = "Assign a VPN backend to \"${profile.name}\" in Edit before activating."
            return
        }
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
        val returnDestination = (_destination.value as? TellyportDestination.ProfileEditor)?.returnTo
            ?: TellyportDestination.Dashboard
        viewModelScope.launch {
            try {
                if (updatedWgRemoteControlKey != null) {
                    profileRepository.setWgRemoteControlKey(updatedWgRemoteControlKey)
                }
                profileRepository.upsertProfile(profile)
                _destination.value = returnDestination
                _bannerMessage.value = "Saved profile \"${profile.name}\"."
            } catch (ex: SingleTailscaleConstraintException) {
                _bannerMessage.value = ProfileRepository.SINGLE_TAILSCALE_MESSAGE
            }
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

    fun setAutoDisconnectSettings(enabled: Boolean, gracePeriodSeconds: Int) {
        viewModelScope.launch {
            profileRepository.setAutoDisconnectSettings(enabled, gracePeriodSeconds)
        }
    }

    fun deleteProfile(profile: RegionalProfile) {
        val isDeletingPendingActivation = activatingProfileId == profile.id ||
            coordinator.isActivatingProfile(profile.id) ||
            (coordinator.progress.value as? ActivationProgress.InProgress)?.profileId == profile.id
        val isDeletingActiveProfile = mockLocationStatusFlow.value.activeProfileId == profile.id ||
            uiState.value.activeSession?.profileId == profile.id
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
        val pendingJob = activationJob
        activationJob = null
        activatingProfileId = null
        viewModelScope.launch {
            pendingJob?.cancelAndJoin()
            // Tear down any active/pending named tunnel BEFORE clearing persisted session metadata.
            coordinator.deactivateAll()
            profileRepository.resetDefaults()
            _bannerMessage.value = "Restored default New York, London, and Sydney profiles."
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

    fun openShizukuApp() {
        val opened = vpnController.openShizukuApp()
        _bannerMessage.value = if (opened) {
            "Opened Shizuku. Start the Shizuku service via Wireless Debugging."
        } else {
            "Shizuku (${VpnController.SHIZUKU_PACKAGE}) is not installed on this device."
        }
    }

    fun requestShizukuPermission() {
        val requested = shizukuForceStopper.requestPermission()
        _bannerMessage.value = if (requested) {
            "Requested Shizuku authorization."
        } else {
            "Shizuku binder is offline. Start Shizuku first via Wireless Debugging."
        }
        refreshDiagnostics()
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

    fun openAccessibilitySettings(): Boolean {
        return try {
            val intent: Intent = permissionChecker.buildAccessibilitySettingsIntent()
            appContext.startActivity(intent)
            true
        } catch (_: Exception) {
            _bannerMessage.value = "Unable to open Accessibility Settings directly on this device."
            false
        }
    }

    fun copyCommandToClipboard(label: String, command: String) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, command))
        _bannerMessage.value = "Copied $label command to clipboard."
    }

    fun copyAdbCommandToClipboard() {
        copyCommandToClipboard("Mock Location ADB", permissionChecker.getAdbMockLocationCommand())
    }

    private fun refreshInstalledAppsAsync() {
        val seq = ++installedAppsLoadSeq
        installedAppsLoadJob?.cancel()
        installedAppsLoadJob = viewModelScope.launch {
            _isLoadingInstalledApps.value = true
            try {
                val apps = withContext(ioDispatcher) { loadInstalledApps() }
                if (installedAppsLoadSeq == seq) {
                    _installedApps.value = apps
                }
            } finally {
                if (installedAppsLoadSeq == seq) {
                    _isLoadingInstalledApps.value = false
                }
            }
        }
    }

    class Factory(private val app: TellyportApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TellyportViewModel(
                appContext = app.applicationContext,
                profileRepository = app.profileRepository,
                vpnController = app.vpnController,
                vpnNetworkMonitor = app.vpnNetworkMonitor,
                permissionChecker = app.permissionChecker,
                shizukuForceStopper = app.shizukuForceStopper,
                coordinator = app.activationCoordinator
            ) as T
        }
    }

    companion object {
        private fun queryInstalledLaunchableApps(context: Context): List<InstalledTvApp> {
            val pm = context.packageManager
            val discovered = linkedMapOf<String, InstalledTvApp>()

            val queries = listOf(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            )
            for (intent in queries) {
                @Suppress("DEPRECATION")
                val resolved = try {
                    pm.queryIntentActivities(intent, 0)
                } catch (_: Exception) {
                    emptyList()
                }
                for (info in resolved) {
                    val pkg = info.activityInfo?.packageName ?: continue
                    if (pkg == context.packageName) continue
                    if (pkg !in discovered) {
                        val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                        discovered[pkg] = InstalledTvApp(label = label, packageName = pkg)
                    }
                }
            }
            return discovered.values.sortedBy { it.label.lowercase() }
        }
    }
}
