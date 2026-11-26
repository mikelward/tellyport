package com.warpmo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Save
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.warpmo.app.data.ProfilePresets
import com.warpmo.app.data.RegionalProfile
import com.warpmo.app.data.VpnBackend
import com.warpmo.app.ui.components.WarpmoPrimaryButton
import com.warpmo.app.ui.components.WarpmoSecondaryButton
import com.warpmo.app.ui.components.WarpmoSelectableChip
import com.warpmo.app.ui.theme.WarpmoColors
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorScreen(
    existingProfile: RegionalProfile?,
    onSave: (RegionalProfile) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember(existingProfile) { mutableStateOf(existingProfile?.name ?: "") }
    var regionCode by remember(existingProfile) { mutableStateOf(existingProfile?.regionCode ?: "US") }
    var latitudeText by remember(existingProfile) {
        mutableStateOf((existingProfile?.latitude ?: 40.7128).toString())
    }
    var longitudeText by remember(existingProfile) {
        mutableStateOf((existingProfile?.longitude ?: -74.0060).toString())
    }
    var altitudeText by remember(existingProfile) {
        mutableStateOf((existingProfile?.altitude ?: 10.0).toString())
    }
    var accuracyText by remember(existingProfile) {
        mutableStateOf((existingProfile?.accuracyMeters ?: 5.0f).toString())
    }
    var vpnBackend by remember(existingProfile) {
        mutableStateOf(existingProfile?.vpnBackend ?: VpnBackend.TAILSCALE)
    }
    var vpnTunnelOrNodeName by remember(existingProfile) {
        mutableStateOf(existingProfile?.vpnTunnelOrNodeName ?: "")
    }
    var targetAppPackage by remember(existingProfile) {
        mutableStateOf(existingProfile?.targetAppPackage ?: "")
    }
    var autoLaunchApp by remember(existingProfile) {
        mutableStateOf(existingProfile?.autoLaunchApp ?: true)
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarpmoColors.Background)
            .verticalScroll(scrollState)
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Header
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
                    text = "Cancel",
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    onClick = onCancel
                )
                Column {
                    Text(
                        text = if (existingProfile == null) "Create Regional Profile" else "Edit Profile • ${existingProfile.name}",
                        color = WarpmoColors.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Configure spoofed coordinates, VPN backend, and target streaming app",
                        color = WarpmoColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            WarpmoPrimaryButton(
                text = "Save Profile",
                icon = Icons.Rounded.Save,
                onClick = {
                    val lat = latitudeText.trim().toDoubleOrNull()
                    val lon = longitudeText.trim().toDoubleOrNull()
                    val alt = altitudeText.trim().toDoubleOrNull() ?: 10.0
                    val acc = accuracyText.trim().toFloatOrNull() ?: 5.0f

                    when {
                        name.isBlank() -> validationError = "Profile name is required."
                        regionCode.isBlank() -> validationError = "Region code is required (e.g., US, UK, AU)."
                        lat == null || lat !in -90.0..90.0 -> validationError = "Latitude must be between -90 and 90."
                        lon == null || lon !in -180.0..180.0 -> validationError = "Longitude must be between -180 and 180."
                        acc <= 0f -> validationError = "Accuracy must be greater than 0 meters."
                        else -> {
                            validationError = null
                            onSave(
                                RegionalProfile(
                                    id = existingProfile?.id ?: UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    regionCode = regionCode.trim().uppercase(),
                                    latitude = lat,
                                    longitude = lon,
                                    altitude = alt,
                                    accuracyMeters = acc,
                                    vpnBackend = vpnBackend,
                                    vpnTunnelOrNodeName = vpnTunnelOrNodeName.trim().ifBlank { null },
                                    targetAppPackage = targetAppPackage.trim().ifBlank { null },
                                    autoLaunchApp = autoLaunchApp
                                )
                            )
                        }
                    }
                }
            )
        }

        if (validationError != null) {
            Text(
                text = validationError!!,
                color = WarpmoColors.Danger,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(WarpmoColors.DangerContainer)
                    .padding(12.dp)
            )
        }

        // Quick Location Presets
        EditorSection(title = "Location Presets") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProfilePresets.locationPresets.forEach { preset ->
                    val isSelected = latitudeText.toDoubleOrNull() == preset.latitude &&
                        longitudeText.toDoubleOrNull() == preset.longitude
                    WarpmoSelectableChip(
                        label = preset.label,
                        selected = isSelected,
                        onClick = {
                            if (name.isBlank() || ProfilePresets.locationPresets.any { it.defaultName == name }) {
                                name = preset.defaultName
                            }
                            regionCode = preset.regionCode
                            latitudeText = preset.latitude.toString()
                            longitudeText = preset.longitude.toString()
                            altitudeText = preset.altitude.toString()
                        }
                    )
                }
            }
        }

        // Basic Identity & Coordinates
        EditorSection(title = "Profile & Coordinates") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WarpmoTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Profile Name",
                    placeholder = "e.g., US, UK, AU",
                    modifier = Modifier.weight(2f)
                )
                WarpmoTextField(
                    value = regionCode,
                    onValueChange = { regionCode = it },
                    label = "Region Code",
                    placeholder = "US / UK / AU",
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WarpmoTextField(
                    value = latitudeText,
                    onValueChange = { latitudeText = it },
                    label = "Latitude",
                    placeholder = "40.7128",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
                WarpmoTextField(
                    value = longitudeText,
                    onValueChange = { longitudeText = it },
                    label = "Longitude",
                    placeholder = "-74.0060",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WarpmoTextField(
                    value = altitudeText,
                    onValueChange = { altitudeText = it },
                    label = "Altitude (m)",
                    placeholder = "10.0",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
                WarpmoTextField(
                    value = accuracyText,
                    onValueChange = { accuracyText = it },
                    label = "Accuracy (m)",
                    placeholder = "5.0",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // VPN Backend Section
        EditorSection(title = "VPN Backend Automation") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VpnBackend.entries.forEach { backend ->
                    WarpmoSelectableChip(
                        label = backend.displayName,
                        selected = vpnBackend == backend,
                        onClick = { vpnBackend = backend }
                    )
                }
            }

            if (vpnBackend != VpnBackend.NONE) {
                WarpmoTextField(
                    value = vpnTunnelOrNodeName,
                    onValueChange = { vpnTunnelOrNodeName = it },
                    label = if (vpnBackend == VpnBackend.WG_TUNNEL) {
                        "WG Tunnel Name (passed via tunnel_name extra)"
                    } else {
                        "Tailscale Exit Node Label (reminder)"
                    },
                    placeholder = if (vpnBackend == VpnBackend.WG_TUNNEL) {
                        "e.g., unifi-au-syd"
                    } else {
                        "e.g., us-nyc-exit"
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Target Streaming App Section
        EditorSection(title = "Target Streaming App") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProfilePresets.streamingAppPresets.forEach { appPreset ->
                    WarpmoSelectableChip(
                        label = appPreset.label,
                        selected = targetAppPackage.trim() == appPreset.packageName,
                        onClick = { targetAppPackage = appPreset.packageName }
                    )
                }
            }

            WarpmoTextField(
                value = targetAppPackage,
                onValueChange = { targetAppPackage = it },
                label = "Target App Package Name",
                placeholder = "e.g., com.cbs.ott, uk.co.bbc.iplayer",
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-launch Target App on Activation",
                        color = WarpmoColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Waits up to 8s for TRANSPORT_VPN (or launches immediately when VPN is None).",
                        color = WarpmoColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = autoLaunchApp,
                    onCheckedChange = { autoLaunchApp = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = WarpmoColors.OnPrimary,
                        checkedTrackColor = WarpmoColors.Primary
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun EditorSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(WarpmoColors.Surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            color = WarpmoColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun WarpmoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = WarpmoColors.TextSecondary, fontSize = 12.sp) },
        placeholder = { Text(placeholder, color = WarpmoColors.TextMuted, fontSize = 13.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = WarpmoColors.TextPrimary,
            unfocusedTextColor = WarpmoColors.TextPrimary,
            focusedContainerColor = WarpmoColors.Background,
            unfocusedContainerColor = WarpmoColors.Background,
            focusedBorderColor = WarpmoColors.FocusRing,
            unfocusedBorderColor = WarpmoColors.BorderSubtle,
            cursorColor = WarpmoColors.Primary
        )
    )
}
