package tv.tellyport.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.tellyport.ui.theme.TellyportTheme

@Composable
fun TellyportApp(
    viewModel: TellyportViewModel,
    onRequestLocationPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = state.destination != TellyportDestination.Dashboard) {
        viewModel.navigateToDashboard()
    }

    TellyportTheme {
        when (val destination = state.destination) {
            TellyportDestination.Dashboard -> {
                DashboardScreen(
                    state = state,
                    onActivateProfile = viewModel::activateProfile,
                    onDisconnectAll = viewModel::disconnectAll,
                    onCreateProfile = viewModel::openCreateProfile,
                    onEditProfile = viewModel::openEditProfile,
                    onDeleteProfile = viewModel::deleteProfile,
                    onOpenDiagnostics = viewModel::openDiagnostics,
                    onOpenTailscale = viewModel::openTailscaleApp,
                    onOpenDeveloperOptions = { viewModel.openDeveloperOptions() },
                    onCopyAdbCommand = viewModel::copyAdbCommandToClipboard,
                    onRequestLocationPermission = onRequestLocationPermission,
                    onDismissBanner = viewModel::dismissBannerMessage
                )
            }

            TellyportDestination.Diagnostics -> {
                DiagnosticsScreen(
                    state = state,
                    onBack = viewModel::navigateToDashboard,
                    onRefresh = viewModel::refreshDiagnostics,
                    onOpenDeveloperOptions = { viewModel.openDeveloperOptions() },
                    onOpenAccessibilitySettings = { viewModel.openAccessibilitySettings() },
                    onCopyCommand = viewModel::copyCommandToClipboard,
                    onRequestLocationPermission = onRequestLocationPermission,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onRequestShizukuPermission = viewModel::requestShizukuPermission,
                    onOpenShizuku = viewModel::openShizukuApp,
                    onOpenTailscale = viewModel::openTailscaleApp,
                    onOpenWgTunnel = viewModel::openWgTunnelApp,
                    onSaveWgRemoteControlKey = viewModel::saveWgRemoteControlKey,
                    onUpdateAutoDisconnect = viewModel::setAutoDisconnectSettings,
                    onTestTailscaleConnect = viewModel::triggerTailscaleConnect,
                    onTestTailscaleDisconnect = viewModel::triggerTailscaleDisconnect,
                    onResetDefaultProfiles = viewModel::resetDefaultProfiles
                )
            }

            is TellyportDestination.ProfileEditor -> {
                ProfileEditorScreen(
                    existingProfile = destination.editingProfile,
                    allProfiles = state.profiles,
                    installedApps = state.installedApps,
                    currentWgRemoteControlKey = state.wgRemoteControlKey,
                    onSave = viewModel::saveProfile,
                    onCancel = viewModel::navigateToDashboard
                )
            }
        }
    }
}
