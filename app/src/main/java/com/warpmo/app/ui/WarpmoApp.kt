package com.warpmo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warpmo.app.ui.theme.WarpmoTheme

@Composable
fun WarpmoApp(
    viewModel: WarpmoViewModel,
    onRequestLocationPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = state.destination != WarpmoDestination.Dashboard) {
        viewModel.navigateToDashboard()
    }

    WarpmoTheme {
        when (val destination = state.destination) {
            WarpmoDestination.Dashboard -> {
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

            WarpmoDestination.Diagnostics -> {
                DiagnosticsScreen(
                    state = state,
                    onBack = viewModel::navigateToDashboard,
                    onRefresh = viewModel::refreshDiagnostics,
                    onOpenDeveloperOptions = { viewModel.openDeveloperOptions() },
                    onCopyAdbCommand = viewModel::copyAdbCommandToClipboard,
                    onRequestLocationPermission = onRequestLocationPermission,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onOpenTailscale = viewModel::openTailscaleApp,
                    onOpenWgTunnel = viewModel::openWgTunnelApp,
                    onSaveWgRemoteControlKey = viewModel::saveWgRemoteControlKey,
                    onTestTailscaleConnect = viewModel::triggerTailscaleConnect,
                    onTestTailscaleDisconnect = viewModel::triggerTailscaleDisconnect,
                    onResetDefaultProfiles = viewModel::resetDefaultProfiles
                )
            }

            is WarpmoDestination.ProfileEditor -> {
                ProfileEditorScreen(
                    existingProfile = destination.editingProfile,
                    currentWgRemoteControlKey = state.wgRemoteControlKey,
                    onSave = viewModel::saveProfile,
                    onCancel = viewModel::navigateToDashboard
                )
            }
        }
    }
}
