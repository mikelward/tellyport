package tv.tellyport.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import tv.tellyport.data.ProfilePresets
import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.VpnBackend
import tv.tellyport.ui.components.InfoBadge
import tv.tellyport.ui.components.MockLocationSetupBanner
import tv.tellyport.ui.components.StatusPill
import tv.tellyport.ui.components.TellyportPrimaryButton
import tv.tellyport.ui.components.TellyportSecondaryButton
import tv.tellyport.ui.theme.TellyportColors
import tv.tellyport.vpn.ActivationProgress
import tv.tellyport.vpn.InferredVpnBackend
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    state: TellyportUiState,
    onActivateProfile: (RegionalProfile) -> Unit,
    onDisconnectAll: () -> Unit,
    onOpenAppConfigurator: () -> Unit,
    onCreateProfile: () -> Unit,
    onEditProfile: (RegionalProfile) -> Unit,
    onDeleteProfile: (RegionalProfile) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenTailscale: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onCopyAdbCommand: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onDismissBanner: () -> Unit
) {
    val isAnyActive = state.mockLocationStatus.isActive ||
        state.vpnState.isConnected ||
        state.activeSession != null

    val mappedAppsCount = state.profiles.flatMap { it.mappedPackages }.distinct().size

    val activeProfileName = state.activeSession?.let { session ->
        state.profiles.firstOrNull { it.id == session.profileId }?.name
    } ?: state.mockLocationStatus.activeProfileName

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 310.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(TellyportColors.Background),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Header & Live Telemetry Status Pills
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(TellyportColors.InfoContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Public,
                                contentDescription = null,
                                tint = TellyportColors.Primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Tellyport",
                                color = TellyportColors.TextPrimary,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Watch TV anywhere • Zero-Touch Regional Automation",
                                color = TellyportColors.TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!activeProfileName.isNullOrBlank()) {
                            StatusPill(
                                label = "Active: $activeProfileName",
                                active = true,
                                activeColor = TellyportColors.Secondary,
                                activeContainer = Color(0xFF1F2347)
                            )
                        }
                        StatusPill(
                            label = formatVpnPillLabel(state),
                            active = state.vpnState.isConnected,
                            activeColor = TellyportColors.Success,
                            activeContainer = TellyportColors.SuccessContainer
                        )
                        StatusPill(
                            label = if (state.mockLocationStatus.isActive) {
                                val lat = state.mockLocationStatus.lastInjectedLatitude
                                    ?: state.mockLocationStatus.configuredLatitude
                                    ?: 0.0
                                val lon = state.mockLocationStatus.lastInjectedLongitude
                                    ?: state.mockLocationStatus.configuredLongitude
                                    ?: 0.0
                                val name = state.mockLocationStatus.activeProfileName ?: "Active"
                                String.format(Locale.US, "GPS: %s (%.3f, %.3f)", name, lat, lon)
                            } else {
                                "Mock GPS Idle"
                            },
                            active = state.mockLocationStatus.isActive,
                            activeColor = TellyportColors.Primary,
                            activeContainer = TellyportColors.InfoContainer
                        )
                        StatusPill(
                            label = if (state.permissionStatus.isAccessibilityEnabled) {
                                "Auto-Intercept Ready"
                            } else {
                                "Auto-Intercept Off"
                            },
                            active = state.permissionStatus.isAccessibilityEnabled,
                            activeColor = TellyportColors.Success,
                            activeContainer = TellyportColors.SuccessContainer
                        )
                    }
                }

                // Global Toolbar Actions (D-Pad friendly row)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TellyportPrimaryButton(
                        text = if (mappedAppsCount > 0) "Configure Apps ($mappedAppsCount)" else "Configure Apps",
                        icon = Icons.Rounded.Apps,
                        onClick = onOpenAppConfigurator
                    )
                    TellyportSecondaryButton(
                        text = "New Profile",
                        icon = Icons.Rounded.Add,
                        onClick = onCreateProfile
                    )
                    TellyportSecondaryButton(
                        text = "Setup & Diagnostics",
                        icon = Icons.Rounded.Build,
                        onClick = onOpenDiagnostics
                    )
                    TellyportSecondaryButton(
                        text = "Open Tailscale App",
                        icon = Icons.Rounded.VpnKey,
                        onClick = onOpenTailscale
                    )
                    TellyportPrimaryButton(
                        text = "Stop All",
                        icon = Icons.Rounded.PowerSettingsNew,
                        onClick = onDisconnectAll,
                        containerColor = if (isAnyActive) TellyportColors.Danger else TellyportColors.SurfaceElevated,
                        contentColor = if (isAnyActive) Color.White else TellyportColors.TextSecondary
                    )
                }
            }
        }

        // Mock Location Setup Banner when not ready
        if (!state.permissionStatus.isReadyForSpoofing || state.mockLocationStatus.mockPermissionDenied) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                MockLocationSetupBanner(
                    permissionStatus = state.permissionStatus,
                    onOpenDeveloperOptions = onOpenDeveloperOptions,
                    onCopyAdbCommand = onCopyAdbCommand,
                    onRequestLocationPermission = onRequestLocationPermission
                )
            }
        }

        // Activation Progress / Status Notification Banner
        val feedbackBanner = resolveFeedbackBanner(
            state.activationProgress,
            state.bannerMessage,
            state.mockLocationStatus.errorMessage
        )
        if (feedbackBanner != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(feedbackBanner.containerColor)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = feedbackBanner.text,
                        color = TellyportColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.bannerMessage != null) {
                        Spacer(Modifier.width(12.dp))
                        TellyportSecondaryButton(
                            text = "Dismiss",
                            onClick = onDismissBanner
                        )
                    }
                }
            }
        }

        // Section Label
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REGIONAL PROFILES (${state.profiles.size})",
                    color = TellyportColors.TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                if (state.autoDisconnectSettings.enabled) {
                    Text(
                        text = "Home Auto-Disconnect: ${state.autoDisconnectSettings.gracePeriodSeconds}s",
                        color = TellyportColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Empty State
        if (state.profiles.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(TellyportColors.Surface)
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "No Regional Profiles Configured",
                        color = TellyportColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Create a profile to pair a VPN backend, spoofed coordinates, and mapped streaming apps.",
                        color = TellyportColors.TextSecondary,
                        fontSize = 14.sp
                    )
                    TellyportPrimaryButton(
                        text = "Add First Profile",
                        icon = Icons.Rounded.Add,
                        onClick = onCreateProfile
                    )
                }
            }
        } else {
            items(state.profiles, key = { it.id }) { profile ->
                val isProfileActive =
                    (state.mockLocationStatus.isActive && state.mockLocationStatus.activeProfileId == profile.id) ||
                        (state.vpnState.isConnected && state.activeSession?.profileId == profile.id)
                val isProfileLoading = state.activationProgress is ActivationProgress.InProgress &&
                    state.activationProgress.profileId == profile.id

                RegionalProfileCard(
                    profile = profile,
                    isActive = isProfileActive,
                    isLoading = isProfileLoading,
                    onActivate = { onActivateProfile(profile) },
                    onStop = onDisconnectAll,
                    onEdit = { onEditProfile(profile) },
                    onDelete = { onDeleteProfile(profile) }
                )
            }
        }
    }
}

private fun formatVpnPillLabel(state: TellyportUiState): String {
    if (!state.vpnState.isConnected) return "VPN Disconnected"
    val backendLabel = when {
        state.vpnState.inferredBackend == InferredVpnBackend.TAILSCALE -> "Tailscale (100.64.x)"
        state.activeSession != null -> state.activeSession.vpnBackend.displayName
        else -> state.vpnState.inferredBackend.displayName
    }
    val iface = state.vpnState.interfaceName?.let { " • $it" } ?: ""
    return "VPN: $backendLabel$iface"
}

private data class FeedbackBanner(
    val text: String,
    val containerColor: Color
)

private fun resolveFeedbackBanner(
    progress: ActivationProgress,
    bannerMessage: String?,
    serviceError: String?
): FeedbackBanner? {
    return when {
        progress is ActivationProgress.InProgress -> FeedbackBanner(
            text = progress.message,
            containerColor = TellyportColors.InfoContainer
        )
        !serviceError.isNullOrBlank() -> FeedbackBanner(
            text = serviceError,
            containerColor = TellyportColors.DangerContainer
        )
        !bannerMessage.isNullOrBlank() -> FeedbackBanner(
            text = bannerMessage,
            containerColor = TellyportColors.SurfaceElevated
        )
        progress is ActivationProgress.Warning -> FeedbackBanner(
            text = progress.message,
            containerColor = TellyportColors.WarningContainer
        )
        progress is ActivationProgress.Active -> FeedbackBanner(
            text = progress.summary,
            containerColor = TellyportColors.SuccessContainer
        )
        else -> null
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RegionalProfileCard(
    profile: RegionalProfile,
    isActive: Boolean,
    isLoading: Boolean,
    onActivate: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(
            containerColor = if (isActive) Color(0xFF102438) else TellyportColors.Surface,
            contentColor = TellyportColors.TextPrimary
        ),
        border = Border(
            border = BorderStroke(
                width = if (isActive) 2.dp else 1.dp,
                color = if (isActive) TellyportColors.Success else TellyportColors.BorderSubtle
            ),
            shape = RoundedCornerShape(16.dp)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Card Header: Region code + Name + Active badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isActive) TellyportColors.SuccessContainer else TellyportColors.InfoContainer)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = profile.regionCode.uppercase(Locale.US),
                            color = if (isActive) TellyportColors.Success else TellyportColors.Primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Text(
                        text = profile.name,
                        color = TellyportColors.TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isActive) {
                    InfoBadge(
                        text = "ACTIVE",
                        containerColor = TellyportColors.SuccessContainer,
                        contentColor = TellyportColors.Success
                    )
                }
            }

            // Coordinates
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.LocationOn,
                    contentDescription = null,
                    tint = TellyportColors.Primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "%.4f, %.4f  •  Alt %.0fm  •  ±%.0fm",
                        profile.latitude,
                        profile.longitude,
                        profile.altitude,
                        profile.accuracyMeters
                    ),
                    color = TellyportColors.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // VPN Backend Badge & Mapped Streaming Apps
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val vpnLabel = buildString {
                    append(profile.vpnBackend.displayName)
                    if (!profile.tunnelName.isNullOrBlank()) {
                        append(" • ")
                        append(profile.tunnelName)
                    }
                }
                val (vpnBg, vpnFg) = when (profile.vpnBackend) {
                    VpnBackend.UNCONFIGURED -> TellyportColors.WarningContainer to TellyportColors.Warning
                    VpnBackend.TAILSCALE -> Color(0xFF1A2347) to Color(0xFF93C5FD)
                    VpnBackend.WG_TUNNEL -> Color(0xFF2B1C3D) to Color(0xFFD8B4FE)
                    VpnBackend.SOCKS_WRAPPER -> Color(0xFF1B2E3C) to Color(0xFF67E8F9)
                    VpnBackend.NONE -> TellyportColors.SurfaceElevated to TellyportColors.TextSecondary
                }
                InfoBadge(
                    text = vpnLabel,
                    containerColor = vpnBg,
                    contentColor = vpnFg
                )

                if (profile.mappedPackages.isEmpty()) {
                    InfoBadge(
                        text = "No Mapped Apps",
                        containerColor = TellyportColors.SurfaceElevated,
                        contentColor = TellyportColors.TextMuted
                    )
                } else {
                    profile.mappedPackages.take(3).forEach { pkg ->
                        val friendly = ProfilePresets.friendlyAppName(pkg) ?: pkg
                        InfoBadge(
                            text = friendly,
                            containerColor = Color(0xFF122C2A),
                            contentColor = Color(0xFF5EEAD4)
                        )
                    }
                    if (profile.mappedPackages.size > 3) {
                        InfoBadge(
                            text = "+${profile.mappedPackages.size - 3} more",
                            containerColor = TellyportColors.SurfaceElevated,
                            contentColor = TellyportColors.TextSecondary
                        )
                    }
                }
            }

            if (profile.mappedPackages.isNotEmpty()) {
                Text(
                    text = profile.mappedPackages.joinToString(", "),
                    color = TellyportColors.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(2.dp))

            // Primary Controls Row (Focusable individually on D-Pad)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isActive) {
                    TellyportPrimaryButton(
                        text = "Stop",
                        icon = Icons.Rounded.Stop,
                        onClick = onStop,
                        containerColor = TellyportColors.Danger,
                        contentColor = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    TellyportPrimaryButton(
                        text = when {
                            isLoading -> "Activating..."
                            profile.vpnBackend == VpnBackend.UNCONFIGURED -> "Configure"
                            else -> "Activate"
                        },
                        icon = if (profile.vpnBackend == VpnBackend.UNCONFIGURED) {
                            Icons.Rounded.Edit
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        onClick = if (profile.vpnBackend == VpnBackend.UNCONFIGURED) {
                            onEdit
                        } else {
                            onActivate
                        },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    )
                }

                TellyportSecondaryButton(
                    text = "Edit",
                    icon = Icons.Rounded.Edit,
                    onClick = onEdit
                )

                TellyportSecondaryButton(
                    text = "",
                    icon = Icons.Rounded.DeleteOutline,
                    onClick = onDelete,
                    contentColor = TellyportColors.Danger
                )
            }
        }
    }
}
