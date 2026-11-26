package com.warpmo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.warpmo.app.ui.components.StatusPill
import com.warpmo.app.ui.components.WarpmoPrimaryButton
import com.warpmo.app.ui.components.WarpmoSecondaryButton
import com.warpmo.app.ui.theme.WarpmoColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    state: WarpmoUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onCopyAdbCommand: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenTailscale: () -> Unit,
    onTestTailscaleConnect: () -> Unit,
    onTestTailscaleDisconnect: () -> Unit,
    onResetDefaultProfiles: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarpmoColors.Background)
            .verticalScroll(scrollState)
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                WarpmoSecondaryButton(
                    text = "Back",
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    onClick = onBack
                )
                Column {
                    Text(
                        text = "Setup & Diagnostics",
                        color = WarpmoColors.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Mock Location AppOps, VPN Transport, and Live Provider Telemetry",
                        color = WarpmoColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            WarpmoSecondaryButton(
                text = "Refresh",
                icon = Icons.Rounded.Refresh,
                onClick = onRefresh
            )
        }

        // Section 1: Mock Location & Runtime Permissions
        DiagnosticsCard(title = "Mock Location & Permissions") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(
                    label = if (state.permissionStatus.isMockLocationAppSelected) {
                        "Mock Location App: Granted"
                    } else {
                        "Mock Location App: Not Selected"
                    },
                    active = state.permissionStatus.isMockLocationAppSelected
                )
                StatusPill(
                    label = if (state.permissionStatus.hasFineLocationPermission) {
                        "ACCESS_FINE_LOCATION: Granted"
                    } else {
                        "ACCESS_FINE_LOCATION: Missing"
                    },
                    active = state.permissionStatus.hasFineLocationPermission
                )
                StatusPill(
                    label = if (state.permissionStatus.hasNotificationPermission) {
                        "Notifications: Granted"
                    } else {
                        "Notifications: Missing"
                    },
                    active = state.permissionStatus.hasNotificationPermission
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = "ADB One-Liner (for Google TV Streamer / Android 14):",
                color = WarpmoColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(WarpmoColors.Background)
                    .padding(12.dp)
            ) {
                Text(
                    text = state.permissionStatus.adbCommand,
                    color = WarpmoColors.Primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WarpmoPrimaryButton(
                    text = "Open Developer Options",
                    icon = Icons.Rounded.DeveloperMode,
                    onClick = onOpenDeveloperOptions
                )
                WarpmoSecondaryButton(
                    text = "Copy ADB Command",
                    icon = Icons.Rounded.ContentCopy,
                    onClick = onCopyAdbCommand
                )
                if (!state.permissionStatus.hasFineLocationPermission) {
                    WarpmoSecondaryButton(
                        text = "Request Location Permission",
                        icon = Icons.Rounded.MyLocation,
                        onClick = onRequestLocationPermission
                    )
                }
                if (!state.permissionStatus.hasNotificationPermission) {
                    WarpmoSecondaryButton(
                        text = "Request Notifications",
                        icon = Icons.Rounded.Notifications,
                        onClick = onRequestNotificationPermission
                    )
                }
            }
        }

        // Section 2: VPN Transport Monitor & Tailscale Controls
        DiagnosticsCard(title = "VPN Transport State (ConnectivityManager)") {
            DiagnosticRow(
                label = "TRANSPORT_VPN",
                value = if (state.vpnState.isConnected) "ACTIVE (Connected)" else "INACTIVE (Disconnected)",
                valueColor = if (state.vpnState.isConnected) WarpmoColors.Success else WarpmoColors.TextSecondary
            )
            DiagnosticRow(
                label = "Tunnel Interface",
                value = state.vpnState.interfaceName ?: "None"
            )
            DiagnosticRow(
                label = "NET_CAPABILITY_INTERNET",
                value = if (state.vpnState.hasInternetCapability) "Available" else "Unavailable"
            )

            Spacer(Modifier.height(4.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WarpmoPrimaryButton(
                    text = "Open Tailscale App",
                    icon = Icons.Rounded.VpnKey,
                    onClick = onOpenTailscale
                )
                WarpmoSecondaryButton(
                    text = "Send Tailscale Connect",
                    onClick = onTestTailscaleConnect
                )
                WarpmoSecondaryButton(
                    text = "Send Tailscale Disconnect",
                    onClick = onTestTailscaleDisconnect
                )
            }
        }

        // Section 3: Live Mock Location Telemetry
        DiagnosticsCard(title = "Mock Location Injection Telemetry") {
            val mock = state.mockLocationStatus
            DiagnosticRow(
                label = "Foreground Service",
                value = if (mock.isActive) "RUNNING (${mock.activeProfileName ?: "Active"})" else "STOPPED",
                valueColor = if (mock.isActive) WarpmoColors.Success else WarpmoColors.TextSecondary
            )
            DiagnosticRow(
                label = "GPS_PROVIDER Test Provider",
                value = if (mock.gpsProviderRegistered) "Registered & Enabled" else "Inactive",
                valueColor = if (mock.gpsProviderRegistered) WarpmoColors.Primary else WarpmoColors.TextSecondary
            )
            DiagnosticRow(
                label = "NETWORK_PROVIDER Test Provider",
                value = if (mock.networkProviderRegistered) "Registered & Enabled" else "Inactive",
                valueColor = if (mock.networkProviderRegistered) WarpmoColors.Primary else WarpmoColors.TextSecondary
            )
            DiagnosticRow(
                label = "Configured Coordinates",
                value = if (mock.configuredLatitude != null && mock.configuredLongitude != null) {
                    String.format(Locale.US, "%.6f, %.6f", mock.configuredLatitude, mock.configuredLongitude)
                } else {
                    "—"
                },
                monospace = true
            )
            DiagnosticRow(
                label = "Last Injected (with <2m jitter)",
                value = if (mock.lastInjectedLatitude != null && mock.lastInjectedLongitude != null) {
                    String.format(Locale.US, "%.6f, %.6f", mock.lastInjectedLatitude, mock.lastInjectedLongitude)
                } else {
                    "—"
                },
                monospace = true
            )
            DiagnosticRow(
                label = "Fixes Injected (1Hz)",
                value = mock.injectionCount.toString(),
                monospace = true
            )
            DiagnosticRow(
                label = "Last Fix Timestamp",
                value = mock.lastUpdatedEpochMs?.let {
                    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(it))
                } ?: "—",
                monospace = true
            )
            if (!mock.errorMessage.isNullOrBlank()) {
                DiagnosticRow(
                    label = "Last Error",
                    value = mock.errorMessage,
                    valueColor = WarpmoColors.Danger
                )
            }
        }

        // Section 4: Profile Maintenance
        DiagnosticsCard(title = "Profile Maintenance") {
            Text(
                text = "Restore the pre-seeded US (New York), UK (London), and AU (Sydney) regional profiles.",
                color = WarpmoColors.TextSecondary,
                fontSize = 13.sp
            )
            WarpmoSecondaryButton(
                text = "Reset Default Profiles",
                icon = Icons.Rounded.Restore,
                onClick = onResetDefaultProfiles
            )
        }
    }
}

@Composable
private fun DiagnosticsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(WarpmoColors.Surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            color = WarpmoColors.TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    valueColor: Color = WarpmoColors.TextPrimary,
    monospace: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = WarpmoColors.TextSecondary,
            fontSize = 13.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
        )
    }
}
