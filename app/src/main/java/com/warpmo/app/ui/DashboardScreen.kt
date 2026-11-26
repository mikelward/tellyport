package com.warpmo.app.ui

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
import com.warpmo.app.data.ProfilePresets
import com.warpmo.app.data.RegionalProfile
import com.warpmo.app.data.VpnBackend
import com.warpmo.app.ui.components.InfoBadge
import com.warpmo.app.ui.components.MockLocationSetupBanner
import com.warpmo.app.ui.components.StatusPill
import com.warpmo.app.ui.components.WarpmoPrimaryButton
import com.warpmo.app.ui.components.WarpmoSecondaryButton
import com.warpmo.app.ui.theme.WarpmoColors
import com.warpmo.app.vpn.ActivationProgress
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    state: WarpmoUiState,
    onActivateProfile: (RegionalProfile) -> Unit,
    onDisconnectAll: () -> Unit,
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
    val isAnyActive = state.mockLocationStatus.isActive || state.vpnState.isConnected

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 310.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(WarpmoColors.Background),
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
                                .background(WarpmoColors.InfoContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Public,
                                contentDescription = null,
                                tint = WarpmoColors.Primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Warpmo",
                                color = WarpmoColors.TextPrimary,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Regional VPN • Mock Location • Streaming Launcher",
                                color = WarpmoColors.TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusPill(
                            label = if (state.vpnState.isConnected) {
                                val iface = state.vpnState.interfaceName?.let { " ($it)" } ?: ""
                                "VPN Connected$iface"
                            } else {
                                "VPN Disconnected"
                            },
                            active = state.vpnState.isConnected,
                            activeColor = WarpmoColors.Success,
                            activeContainer = WarpmoColors.SuccessContainer
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
                            activeColor = WarpmoColors.Primary,
                            activeContainer = WarpmoColors.InfoContainer
                        )
                    }
                }

                // Global Toolbar Actions (D-Pad friendly row)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WarpmoPrimaryButton(
                        text = "New Profile",
                        icon = Icons.Rounded.Add,
                        onClick = onCreateProfile
                    )
                    WarpmoSecondaryButton(
                        text = "Setup & Diagnostics",
                        icon = Icons.Rounded.Build,
                        onClick = onOpenDiagnostics
                    )
                    WarpmoSecondaryButton(
                        text = "Open Tailscale",
                        icon = Icons.Rounded.VpnKey,
                        onClick = onOpenTailscale
                    )
                    WarpmoPrimaryButton(
                        text = "Disconnect All",
                        icon = Icons.Rounded.PowerSettingsNew,
                        onClick = onDisconnectAll,
                        containerColor = if (isAnyActive) WarpmoColors.Danger else WarpmoColors.SurfaceElevated,
                        contentColor = if (isAnyActive) Color.White else WarpmoColors.TextSecondary
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
        val feedbackBanner = resolveFeedbackBanner(state.activationProgress, state.bannerMessage, state.mockLocationStatus.errorMessage)
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
                        color = WarpmoColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.bannerMessage != null) {
                        Spacer(Modifier.width(12.dp))
                        WarpmoSecondaryButton(
                            text = "Dismiss",
                            onClick = onDismissBanner
                        )
                    }
                }
            }
        }

        // Section Label
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "REGIONAL PROFILES (${state.profiles.size})",
                color = WarpmoColors.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
        }

        // Empty State
        if (state.profiles.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(WarpmoColors.Surface)
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "No Regional Profiles Configured",
                        color = WarpmoColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Create a profile to pair a VPN backend, spoofed coordinates, and a streaming app.",
                        color = WarpmoColors.TextSecondary,
                        fontSize = 14.sp
                    )
                    WarpmoPrimaryButton(
                        text = "Add First Profile",
                        icon = Icons.Rounded.Add,
                        onClick = onCreateProfile
                    )
                }
            }
        } else {
            // Focusable Profile Cards Grid
            items(state.profiles, key = { it.id }) { profile ->
                val isProfileActive = state.mockLocationStatus.isActive &&
                    state.mockLocationStatus.activeProfileId == profile.id
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
            containerColor = WarpmoColors.InfoContainer
        )
        !serviceError.isNullOrBlank() -> FeedbackBanner(
            text = serviceError,
            containerColor = WarpmoColors.DangerContainer
        )
        !bannerMessage.isNullOrBlank() -> FeedbackBanner(
            text = bannerMessage,
            containerColor = WarpmoColors.SurfaceElevated
        )
        progress is ActivationProgress.Warning -> FeedbackBanner(
            text = progress.message,
            containerColor = WarpmoColors.WarningContainer
        )
        progress is ActivationProgress.Active -> FeedbackBanner(
            text = progress.summary,
            containerColor = WarpmoColors.SuccessContainer
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
            containerColor = if (isActive) Color(0xFF102438) else WarpmoColors.Surface,
            contentColor = WarpmoColors.TextPrimary
        ),
        border = Border(
            border = BorderStroke(
                width = if (isActive) 2.dp else 1.dp,
                color = if (isActive) WarpmoColors.Success else WarpmoColors.BorderSubtle
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
                            .background(if (isActive) WarpmoColors.SuccessContainer else WarpmoColors.InfoContainer)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = profile.regionCode.uppercase(Locale.US),
                            color = if (isActive) WarpmoColors.Success else WarpmoColors.Primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Text(
                        text = profile.name,
                        color = WarpmoColors.TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isActive) {
                    InfoBadge(
                        text = "ACTIVE",
                        containerColor = WarpmoColors.SuccessContainer,
                        contentColor = WarpmoColors.Success
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
                    tint = WarpmoColors.Primary,
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
                    color = WarpmoColors.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // VPN Backend Badge & Target App Badge
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val vpnLabel = buildString {
                    append(profile.vpnBackend.displayName)
                    if (!profile.vpnTunnelOrNodeName.isNullOrBlank()) {
                        append(" • ")
                        append(profile.vpnTunnelOrNodeName)
                    }
                }
                val (vpnBg, vpnFg) = when (profile.vpnBackend) {
                    VpnBackend.TAILSCALE -> Color(0xFF1A2347) to Color(0xFF93C5FD)
                    VpnBackend.WG_TUNNEL -> Color(0xFF2B1C3D) to Color(0xFFD8B4FE)
                    VpnBackend.NONE -> WarpmoColors.SurfaceElevated to WarpmoColors.TextSecondary
                }
                InfoBadge(
                    text = vpnLabel,
                    containerColor = vpnBg,
                    contentColor = vpnFg
                )

                val friendlyApp = ProfilePresets.friendlyAppName(profile.targetAppPackage)
                if (friendlyApp != null) {
                    val suffix = if (profile.autoLaunchApp) " (Auto)" else " (Manual)"
                    InfoBadge(
                        text = "$friendlyApp$suffix",
                        containerColor = Color(0xFF122C2A),
                        contentColor = Color(0xFF5EEAD4)
                    )
                } else {
                    InfoBadge(
                        text = "No Target App",
                        containerColor = WarpmoColors.SurfaceElevated,
                        contentColor = WarpmoColors.TextMuted
                    )
                }
            }

            if (!profile.targetAppPackage.isNullOrBlank()) {
                Text(
                    text = profile.targetAppPackage,
                    color = WarpmoColors.TextMuted,
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
                    WarpmoPrimaryButton(
                        text = "Stop",
                        icon = Icons.Rounded.Stop,
                        onClick = onStop,
                        containerColor = WarpmoColors.Danger,
                        contentColor = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    WarpmoPrimaryButton(
                        text = if (isLoading) "Activating..." else "Activate",
                        icon = Icons.Rounded.PlayArrow,
                        onClick = onActivate,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    )
                }

                WarpmoSecondaryButton(
                    text = "Edit",
                    icon = Icons.Rounded.Edit,
                    onClick = onEdit
                )

                WarpmoSecondaryButton(
                    text = "",
                    icon = Icons.Rounded.DeleteOutline,
                    onClick = onDelete,
                    contentColor = WarpmoColors.Danger
                )
            }
        }
    }
}
