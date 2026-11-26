package tv.tellyport.ui

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
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.tellyport.ui.components.StatusPill
import tv.tellyport.ui.components.TellyportPrimaryButton
import tv.tellyport.ui.components.TellyportSecondaryButton
import tv.tellyport.ui.components.TellyportSelectableChip
import tv.tellyport.ui.theme.TellyportColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    state: TellyportUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onCopyCommand: (String, String) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenTailscale: () -> Unit,
    onOpenWgTunnel: () -> Unit,
    onSaveWgRemoteControlKey: (String) -> Unit,
    onUpdateAutoDisconnect: (Boolean, Int) -> Unit,
    onTestTailscaleConnect: () -> Unit,
    onTestTailscaleDisconnect: () -> Unit,
    onResetDefaultProfiles: () -> Unit
) {
    val scrollState = rememberScrollState()
    var wgKeyInput by remember(state.wgRemoteControlKey) { mutableStateOf(state.wgRemoteControlKey) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TellyportColors.Background)
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
                TellyportSecondaryButton(
                    text = "Back",
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    onClick = onBack
                )
                Column {
                    Text(
                        text = "Setup & Diagnostics",
                        color = TellyportColors.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Zero-Touch Permissions, Shizuku, Boot Recovery, VPN LinkProperties, and Telemetry",
                        color = TellyportColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            TellyportSecondaryButton(
                text = "Refresh",
                icon = Icons.Rounded.Refresh,
                onClick = onRefresh
            )
        }

        // Section 1: Setup & Permissions (One-time ADB commands + Settings Deep Links)
        DiagnosticsCard(title = "Setup & Permissions (tv.tellyport)") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(
                    label = if (state.permissionStatus.isMockLocationAppSelected) {
                        "Mock Location: Granted"
                    } else {
                        "Mock Location: Not Selected"
                    },
                    active = state.permissionStatus.isMockLocationAppSelected
                )
                StatusPill(
                    label = if (state.permissionStatus.hasFineLocationPermission) {
                        "Fine Location: Granted"
                    } else {
                        "Fine Location: Missing"
                    },
                    active = state.permissionStatus.hasFineLocationPermission
                )
                StatusPill(
                    label = if (state.permissionStatus.isAccessibilityEnabled) {
                        "Accessibility Interceptor: Active"
                    } else {
                        "Accessibility Interceptor: Off"
                    },
                    active = state.permissionStatus.isAccessibilityEnabled
                )
                StatusPill(
                    label = if (state.permissionStatus.hasWriteSecureSettingsPermission) {
                        "WRITE_SECURE_SETTINGS: Granted"
                    } else {
                        "WRITE_SECURE_SETTINGS: Missing"
                    },
                    active = state.permissionStatus.hasWriteSecureSettingsPermission
                )
                StatusPill(
                    label = when {
                        state.shizukuStatus.isReady -> "Shizuku: Authorized"
                        state.shizukuStatus.isBinderAlive -> "Shizuku: Binder Online (Unauth)"
                        state.shizukuStatus.isInstalled -> "Shizuku: Installed (Offline)"
                        else -> "Shizuku: Not Installed"
                    },
                    active = state.shizukuStatus.isReady
                )
            }

            AdbCommandRow(
                label = "1. Mock Location AppOps:",
                command = state.permissionStatus.adbCommand,
                onCopy = { onCopyCommand("Mock Location ADB", state.permissionStatus.adbCommand) }
            )

            AdbCommandRow(
                label = "2. Accessibility App Interceptor:",
                command = state.permissionStatus.adbAccessibilityCommand,
                onCopy = {
                    onCopyCommand("Accessibility ADB", state.permissionStatus.adbAccessibilityCommand)
                }
            )

            AdbCommandRow(
                label = "3. Boot Wireless Debugging Recovery (WRITE_SECURE_SETTINGS):",
                command = state.permissionStatus.adbWriteSecureSettingsCommand,
                onCopy = {
                    onCopyCommand(
                        "WRITE_SECURE_SETTINGS ADB",
                        state.permissionStatus.adbWriteSecureSettingsCommand
                    )
                }
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TellyportPrimaryButton(
                    text = "Developer Options",
                    icon = Icons.Rounded.DeveloperMode,
                    onClick = onOpenDeveloperOptions
                )
                TellyportSecondaryButton(
                    text = "Accessibility Settings",
                    icon = Icons.Rounded.AccessibilityNew,
                    onClick = onOpenAccessibilitySettings
                )
                TellyportSecondaryButton(
                    text = "Authorize Shizuku",
                    icon = Icons.Rounded.Security,
                    onClick = onRequestShizukuPermission
                )
                TellyportSecondaryButton(
                    text = "Open Shizuku",
                    onClick = onOpenShizuku
                )
                if (!state.permissionStatus.hasFineLocationPermission) {
                    TellyportSecondaryButton(
                        text = "Grant Location",
                        icon = Icons.Rounded.MyLocation,
                        onClick = onRequestLocationPermission
                    )
                }
                if (!state.permissionStatus.hasNotificationPermission) {
                    TellyportSecondaryButton(
                        text = "Grant Notifications",
                        icon = Icons.Rounded.Notifications,
                        onClick = onRequestNotificationPermission
                    )
                }
            }
        }

        // Section 2: Global Auto-Disconnect on Home Launcher
        DiagnosticsCard(title = "Home Launcher Auto-Disconnect") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Disconnect when returning to Home Launcher",
                        color = TellyportColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Automatically stops mock location and disconnects VPNs after staying on the Google TV Home screen.",
                        color = TellyportColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = state.autoDisconnectSettings.enabled,
                    onCheckedChange = { enabled ->
                        onUpdateAutoDisconnect(enabled, state.autoDisconnectSettings.gracePeriodSeconds)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TellyportColors.OnPrimary,
                        checkedTrackColor = TellyportColors.Primary
                    )
                )
            }

            Text(
                text = "Grace Period:",
                color = TellyportColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(15, 30, 60, 120, 300).forEach { seconds ->
                    TellyportSelectableChip(
                        label = "${seconds}s",
                        selected = state.autoDisconnectSettings.gracePeriodSeconds == seconds,
                        onClick = {
                            onUpdateAutoDisconnect(state.autoDisconnectSettings.enabled, seconds)
                        }
                    )
                }
            }
        }

        // Section 3: VPN Transport State & Persisted Session
        DiagnosticsCard(title = "VPN Transport State & Session Recovery") {
            DiagnosticRow(
                label = "TRANSPORT_VPN",
                value = if (state.vpnState.isConnected) "ACTIVE (Connected)" else "INACTIVE (Disconnected)",
                valueColor = if (state.vpnState.isConnected) TellyportColors.Success else TellyportColors.TextSecondary
            )
            DiagnosticRow(
                label = "LinkProperties Heuristic",
                value = state.vpnState.inferredBackend.displayName,
                valueColor = if (state.vpnState.isConnected) TellyportColors.Primary else TellyportColors.TextSecondary
            )
            DiagnosticRow(
                label = "VPN IPv4 Address(es)",
                value = state.vpnState.ipv4Addresses.joinToString(", ").ifBlank { "None" },
                monospace = true
            )
            DiagnosticRow(
                label = "Persisted Active Session",
                value = state.activeSession?.let { s ->
                    val name = state.profiles.firstOrNull { it.id == s.profileId }?.name ?: s.profileId
                    "$name (${s.vpnBackend.displayName})"
                } ?: "None"
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
                TellyportPrimaryButton(
                    text = "Open Tailscale App",
                    icon = Icons.Rounded.VpnKey,
                    onClick = onOpenTailscale
                )
                TellyportSecondaryButton(
                    text = "Send Tailscale Connect",
                    onClick = onTestTailscaleConnect
                )
                TellyportSecondaryButton(
                    text = "Send Tailscale Disconnect",
                    onClick = onTestTailscaleDisconnect
                )
            }
        }

        // Section 4: WG Tunnel Remote Control Authentication
        DiagnosticsCard(title = "WG Tunnel Remote App Control") {
            StatusPill(
                label = if (state.wgRemoteControlKey.isNotBlank()) {
                    "Remote Control Key: Configured"
                } else {
                    "Remote Control Key: Not Configured"
                },
                active = state.wgRemoteControlKey.isNotBlank()
            )
            Text(
                text = "WG Tunnel requires a key for START_TUNNEL / STOP_TUNNEL intents. Enable Remote App Control in WG Tunnel → Settings and paste the key below:",
                color = TellyportColors.TextSecondary,
                fontSize = 13.sp
            )
            OutlinedTextField(
                value = wgKeyInput,
                onValueChange = { wgKeyInput = it },
                label = { Text("WG Tunnel Remote Control Key", color = TellyportColors.TextSecondary, fontSize = 12.sp) },
                placeholder = { Text("Paste key from WG Tunnel settings", color = TellyportColors.TextMuted, fontSize = 13.sp) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TellyportColors.TextPrimary,
                    unfocusedTextColor = TellyportColors.TextPrimary,
                    focusedContainerColor = TellyportColors.Background,
                    unfocusedContainerColor = TellyportColors.Background,
                    focusedBorderColor = TellyportColors.FocusRing,
                    unfocusedBorderColor = TellyportColors.BorderSubtle,
                    cursorColor = TellyportColors.Primary
                )
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TellyportPrimaryButton(
                    text = "Save WG Key",
                    icon = Icons.Rounded.Save,
                    onClick = { onSaveWgRemoteControlKey(wgKeyInput) }
                )
                TellyportSecondaryButton(
                    text = "Open WG Tunnel App",
                    icon = Icons.Rounded.VpnKey,
                    onClick = onOpenWgTunnel
                )
            }
        }

        // Section 5: Boot Recovery Diagnostics
        DiagnosticsCard(title = "Last Boot Recovery Diagnostics") {
            val boot = state.bootRecoveryDiagnostics
            if (boot == null) {
                Text(
                    text = "No reboot recovery run recorded yet. Runs automatically on BOOT_COMPLETED.",
                    color = TellyportColors.TextSecondary,
                    fontSize = 13.sp
                )
            } else {
                DiagnosticRow(
                    label = "Recorded At",
                    value = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(boot.timestampEpochMs)),
                    monospace = true
                )
                DiagnosticRow(
                    label = "Network",
                    value = if (boot.networkConnected) "Connected (${boot.networkType})" else boot.networkType
                )
                DiagnosticRow(
                    label = "adb_wifi_enabled",
                    value = if (boot.adbWifiEnabled) "Enabled (1)" else "Not Set",
                    valueColor = if (boot.adbWifiEnabled) TellyportColors.Success else TellyportColors.Warning
                )
                DiagnosticRow(
                    label = "Shizuku Binder",
                    value = if (boot.shizukuRunning) "Online" else if (boot.shizukuStartAttempted) "Offline (Wake Attempted)" else "Offline",
                    valueColor = if (boot.shizukuRunning) TellyportColors.Success else TellyportColors.Warning
                )
                Text(
                    text = boot.message,
                    color = TellyportColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // Section 6: Live Mock Location Telemetry
        DiagnosticsCard(title = "Mock Location Injection Telemetry") {
            val mock = state.mockLocationStatus
            DiagnosticRow(
                label = "Foreground Service",
                value = if (mock.isActive) "RUNNING (${mock.activeProfileName ?: "Active"})" else "STOPPED",
                valueColor = if (mock.isActive) TellyportColors.Success else TellyportColors.TextSecondary
            )
            DiagnosticRow(
                label = "GPS_PROVIDER Test Provider",
                value = if (mock.gpsProviderRegistered) "Registered & Enabled" else "Inactive",
                valueColor = if (mock.gpsProviderRegistered) TellyportColors.Primary else TellyportColors.TextSecondary
            )
            DiagnosticRow(
                label = "NETWORK_PROVIDER Test Provider",
                value = if (mock.networkProviderRegistered) "Registered & Enabled" else "Inactive",
                valueColor = if (mock.networkProviderRegistered) TellyportColors.Primary else TellyportColors.TextSecondary
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
                    valueColor = TellyportColors.Danger
                )
            }
        }

        // Section 7: Profile Maintenance
        DiagnosticsCard(title = "Profile Maintenance") {
            Text(
                text = "Restore the pre-seeded New York, London, and Sydney regional profiles with default streaming app mappings.",
                color = TellyportColors.TextSecondary,
                fontSize = 13.sp
            )
            TellyportSecondaryButton(
                text = "Reset Default Profiles",
                icon = Icons.Rounded.Restore,
                onClick = onResetDefaultProfiles
            )
        }
    }
}

@Composable
private fun AdbCommandRow(
    label: String,
    command: String,
    onCopy: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = TellyportColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TellyportColors.Background)
                    .padding(10.dp)
            ) {
                Text(
                    text = command,
                    color = TellyportColors.Primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
            TellyportSecondaryButton(
                text = "Copy",
                icon = Icons.Rounded.ContentCopy,
                onClick = onCopy
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
            .background(TellyportColors.Surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            color = TellyportColors.TextPrimary,
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
    valueColor: Color = TellyportColors.TextPrimary,
    monospace: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TellyportColors.TextSecondary,
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
