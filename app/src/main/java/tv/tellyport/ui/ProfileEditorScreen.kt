package tv.tellyport.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.LocationCity
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.tellyport.data.ProfilePresets
import tv.tellyport.data.ProfileRepository
import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.VpnBackend
import tv.tellyport.data.WorldCity
import tv.tellyport.data.WorldCityCatalog
import tv.tellyport.ui.components.TellyportPrimaryButton
import tv.tellyport.ui.components.TellyportSecondaryButton
import tv.tellyport.ui.components.TellyportSelectableChip
import tv.tellyport.ui.theme.TellyportColors
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorScreen(
    existingProfile: RegionalProfile?,
    initialCity: WorldCity? = null,
    allProfiles: List<RegionalProfile>,
    installedApps: List<InstalledTvApp>,
    currentWgRemoteControlKey: String,
    onSave: (RegionalProfile, String?) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember(existingProfile, initialCity) {
        mutableStateOf(existingProfile?.name ?: initialCity?.name ?: "")
    }
    var lastAutoCityName by remember(existingProfile, initialCity) {
        mutableStateOf(initialCity?.name)
    }
    var regionCode by remember(existingProfile, initialCity) {
        mutableStateOf(existingProfile?.regionCode ?: initialCity?.regionCode ?: "US")
    }
    var latitudeText by remember(existingProfile, initialCity) {
        mutableStateOf((existingProfile?.latitude ?: initialCity?.latitude ?: 40.7128).toString())
    }
    var longitudeText by remember(existingProfile, initialCity) {
        mutableStateOf((existingProfile?.longitude ?: initialCity?.longitude ?: -74.0060).toString())
    }
    var altitudeText by remember(existingProfile, initialCity) {
        mutableStateOf((existingProfile?.altitude ?: initialCity?.altitudeMeters ?: 10.0).toString())
    }
    var accuracyText by remember(existingProfile) {
        mutableStateOf((existingProfile?.accuracyMeters ?: 5.0f).toString())
    }
    var vpnBackend by remember(existingProfile) {
        mutableStateOf(existingProfile?.vpnBackend ?: VpnBackend.UNCONFIGURED)
    }
    var tunnelName by remember(existingProfile) {
        mutableStateOf(existingProfile?.tunnelName ?: "")
    }
    var wgRemoteControlKey by remember(currentWgRemoteControlKey) {
        mutableStateOf(currentWgRemoteControlKey)
    }
    var mappedPackages by remember(existingProfile) {
        mutableStateOf(existingProfile?.mappedPackages ?: emptyList())
    }
    var customPackageInput by remember { mutableStateOf("") }
    var autoLaunchApp by remember(existingProfile) {
        mutableStateOf(existingProfile?.autoLaunchApp ?: true)
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    var citySearchQuery by remember { mutableStateOf("") }
    var showFullCityBrowser by remember { mutableStateOf(false) }

    val allWorldCities = remember { WorldCityCatalog.cities }
    val inlineCityMatches = remember(citySearchQuery, allWorldCities) {
        if (citySearchQuery.isBlank()) {
            emptyList()
        } else {
            WorldCityCatalog.search(query = citySearchQuery, limit = 8, source = allWorldCities)
        }
    }

    val applySelectedCity: (WorldCity) -> Unit = { city ->
        if (name.isBlank() ||
            name == lastAutoCityName ||
            ProfilePresets.locationPresets.any { it.defaultName == name }
        ) {
            name = city.name
            lastAutoCityName = city.name
        }
        regionCode = city.regionCode
        latitudeText = city.latitude.toString()
        longitudeText = city.longitude.toString()
        altitudeText = city.altitudeMeters.toString()
        citySearchQuery = ""
        showFullCityBrowser = false
    }

    if (showFullCityBrowser) {
        BackHandler {
            showFullCityBrowser = false
        }
        val currentNearest = remember(latitudeText, longitudeText) {
            val lat = latitudeText.trim().toDoubleOrNull()
            val lon = longitudeText.trim().toDoubleOrNull()
            if (lat != null && lon != null) WorldCityCatalog.findNearestCity(lat, lon) else null
        }
        WorldCityPickerScreen(
            title = "Select Mock Location City (1M+ Pop.)",
            subtitle = "Choose any world city over 1M population to fill coordinates & altitude",
            initialSelectedCityId = currentNearest?.id,
            onBack = { showFullCityBrowser = false },
            onSelectForProfile = applySelectedCity
        )
        return
    }

    val conflictingTailscaleProfile = remember(allProfiles, existingProfile) {
        ProfileRepository.findTailscaleProfile(allProfiles, excludingProfileId = existingProfile?.id)
    }
    val tailscaleBlocked = conflictingTailscaleProfile != null

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TellyportColors.Background)
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
                TellyportSecondaryButton(
                    text = "Cancel",
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    onClick = onCancel
                )
                Column {
                    Text(
                        text = if (existingProfile == null) "Create Regional Profile" else "Edit Profile • ${existingProfile.name}",
                        color = TellyportColors.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Configure spoofed coordinates, VPN backend, and mapped streaming apps",
                        color = TellyportColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            TellyportPrimaryButton(
                text = "Save Profile",
                icon = Icons.Rounded.Save,
                onClick = {
                    val lat = latitudeText.trim().toDoubleOrNull()
                    val lon = longitudeText.trim().toDoubleOrNull()
                    val alt = altitudeText.trim().toDoubleOrNull()
                    val acc = accuracyText.trim().toFloatOrNull()
                    val coordinateError = coordinateFieldsError(lat, lon, alt, acc)

                    when {
                        name.isBlank() -> validationError = "Profile name is required."
                        regionCode.isBlank() -> validationError = "Region code is required (e.g., US, UK, AU)."
                        coordinateError != null || lat == null || lon == null || alt == null || acc == null ->
                            validationError = coordinateError
                        vpnBackend == VpnBackend.TAILSCALE && tailscaleBlocked -> {
                            validationError = ProfileRepository.SINGLE_TAILSCALE_MESSAGE
                        }
                        else -> {
                            validationError = null
                            val updatedKey = if (vpnBackend == VpnBackend.WG_TUNNEL &&
                                wgRemoteControlKey.trim() != currentWgRemoteControlKey.trim()
                            ) {
                                wgRemoteControlKey
                            } else {
                                null
                            }
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
                                    tunnelName = tunnelName.trim().ifBlank { null },
                                    mappedPackages = mappedPackages,
                                    autoLaunchApp = autoLaunchApp
                                ),
                                updatedKey
                            )
                        }
                    }
                }
            )
        }

        if (validationError != null) {
            Text(
                text = validationError!!,
                color = TellyportColors.Danger,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(TellyportColors.DangerContainer)
                    .padding(12.dp)
            )
        }

        // World City Fuzzy Search & Quick Location Presets
        EditorSection(title = "Mock Location City (${allWorldCities.size} World Cities, 1M+ Pop.)") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TellyportTextField(
                    value = citySearchQuery,
                    onValueChange = { citySearchQuery = it },
                    label = "Fuzzy-Find World City",
                    placeholder = "Type city, country, state, or code (e.g. melb, sao paulo, tokyo, nyc)...",
                    modifier = Modifier.weight(1f)
                )
                if (citySearchQuery.isNotEmpty()) {
                    TellyportSecondaryButton(
                        text = "Clear",
                        icon = Icons.Rounded.Clear,
                        onClick = { citySearchQuery = "" }
                    )
                }
                TellyportPrimaryButton(
                    text = "Browse All ${allWorldCities.size}",
                    icon = Icons.Rounded.LocationCity,
                    onClick = { showFullCityBrowser = true }
                )
            }

            if (citySearchQuery.isNotBlank()) {
                if (inlineCityMatches.isEmpty()) {
                    Text(
                        text = "No world cities (1M+ pop.) matched \"$citySearchQuery\".",
                        color = TellyportColors.TextMuted,
                        fontSize = 12.sp
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        inlineCityMatches.forEach { city ->
                            WorldCityRowCard(
                                city = city,
                                isSelected = false,
                                onClick = { applySelectedCity(city) }
                            )
                        }
                    }
                }
            }

            Text(
                text = "Quick Presets:",
                color = TellyportColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProfilePresets.locationPresets.forEach { preset ->
                    val isSelected = latitudeText.toDoubleOrNull() == preset.latitude &&
                        longitudeText.toDoubleOrNull() == preset.longitude
                    TellyportSelectableChip(
                        label = preset.label,
                        selected = isSelected,
                        onClick = {
                            if (name.isBlank() ||
                                name == lastAutoCityName ||
                                ProfilePresets.locationPresets.any { it.defaultName == name }
                            ) {
                                name = preset.defaultName
                                lastAutoCityName = preset.defaultName
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
                TellyportTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Profile Name",
                    placeholder = "e.g., New York, London, Sydney",
                    modifier = Modifier.weight(2f)
                )
                TellyportTextField(
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
                TellyportTextField(
                    value = latitudeText,
                    onValueChange = { latitudeText = it },
                    label = "Latitude",
                    placeholder = "40.7128",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
                TellyportTextField(
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
                TellyportTextField(
                    value = altitudeText,
                    onValueChange = { altitudeText = it },
                    label = "Altitude (m)",
                    placeholder = "10.0",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
                TellyportTextField(
                    value = accuracyText,
                    onValueChange = { accuracyText = it },
                    label = "Accuracy (m)",
                    placeholder = "5.0",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // VPN Backend Section with Single-Tailscale Guardrail
        EditorSection(title = "VPN Backend Automation") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VpnBackend.entries.forEach { backend ->
                    val isTailscaleBlockedHere = backend == VpnBackend.TAILSCALE && tailscaleBlocked
                    val label = if (isTailscaleBlockedHere) {
                        "${backend.displayName} (In use by ${conflictingTailscaleProfile?.name})"
                    } else {
                        backend.displayName
                    }
                    TellyportSelectableChip(
                        label = label,
                        selected = vpnBackend == backend,
                        onClick = {
                            if (isTailscaleBlockedHere) {
                                validationError = ProfileRepository.SINGLE_TAILSCALE_MESSAGE
                            } else {
                                if (validationError == ProfileRepository.SINGLE_TAILSCALE_MESSAGE) {
                                    validationError = null
                                }
                                vpnBackend = backend
                            }
                        }
                    )
                }
            }

            if (tailscaleBlocked) {
                Text(
                    text = ProfileRepository.SINGLE_TAILSCALE_MESSAGE,
                    color = TellyportColors.Warning,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(TellyportColors.WarningContainer)
                        .padding(10.dp)
                )
            }

            if (vpnBackend == VpnBackend.WG_TUNNEL ||
                vpnBackend == VpnBackend.TAILSCALE ||
                vpnBackend == VpnBackend.SOCKS_WRAPPER
            ) {
                TellyportTextField(
                    value = tunnelName,
                    onValueChange = { tunnelName = it },
                    label = when (vpnBackend) {
                        VpnBackend.WG_TUNNEL -> "WG Tunnel Name (passed via tunnelName extra)"
                        VpnBackend.SOCKS_WRAPPER -> "SOCKS Wrapper Profile Name"
                        else -> "Tailscale Exit Node Label (reminder)"
                    },
                    placeholder = when (vpnBackend) {
                        VpnBackend.WG_TUNNEL -> "e.g., unifi-au-syd"
                        VpnBackend.SOCKS_WRAPPER -> "e.g., london-socks"
                        else -> "e.g., us-nyc-exit"
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (vpnBackend == VpnBackend.WG_TUNNEL) {
                TellyportTextField(
                    value = wgRemoteControlKey,
                    onValueChange = { wgRemoteControlKey = it },
                    label = "WG Tunnel Remote Control Key (required for START_TUNNEL / STOP_TUNNEL)",
                    placeholder = "Copy from WG Tunnel → Settings → Remote App Control",
                    modifier = Modifier.fillMaxWidth(),
                    isSecret = true
                )
            }
        }

        // Mapped Streaming Apps Section (Multi-app picker + presets + installed apps + custom)
        EditorSection(title = "Mapped Streaming Apps (Zero-Touch Interception)") {
            Text(
                text = "When any mapped app is opened from the Google TV home screen, Tellyport automatically switches to this profile and relaunches it cleanly.",
                color = TellyportColors.TextSecondary,
                fontSize = 12.sp
            )

            if (mappedPackages.isNotEmpty()) {
                Text(
                    text = "Currently Mapped (select to remove):",
                    color = TellyportColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    mappedPackages.forEach { pkg ->
                        val label = ProfilePresets.friendlyAppName(pkg)
                            ?.let { "$it ($pkg) ✕" } ?: "$pkg ✕"
                        TellyportSelectableChip(
                            label = label,
                            selected = true,
                            onClick = { mappedPackages = mappedPackages.filterNot { it == pkg } }
                        )
                    }
                }
            }

            Text(
                text = "Popular Streaming App Presets (toggle):",
                color = TellyportColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProfilePresets.streamingAppPresets.forEach { preset ->
                    val isMapped = preset.packageName in mappedPackages
                    TellyportSelectableChip(
                        label = preset.label,
                        selected = isMapped,
                        onClick = {
                            mappedPackages = if (isMapped) {
                                mappedPackages.filterNot { it == preset.packageName }
                            } else {
                                (mappedPackages + preset.packageName).distinct()
                            }
                        }
                    )
                }
            }

            if (installedApps.isNotEmpty()) {
                Text(
                    text = "Installed Apps on This Device (toggle):",
                    color = TellyportColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    installedApps.take(18).forEach { app ->
                        val isMapped = app.packageName in mappedPackages
                        TellyportSelectableChip(
                            label = app.label,
                            selected = isMapped,
                            onClick = {
                                mappedPackages = if (isMapped) {
                                    mappedPackages.filterNot { it == app.packageName }
                                } else {
                                    (mappedPackages + app.packageName).distinct()
                                }
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TellyportTextField(
                    value = customPackageInput,
                    onValueChange = { customPackageInput = it },
                    label = "Add Custom Package Name",
                    placeholder = "e.g., com.cbs.ott, uk.co.bbc.iplayer",
                    modifier = Modifier.weight(1f)
                )
                TellyportSecondaryButton(
                    text = "Add",
                    icon = Icons.Rounded.Add,
                    onClick = {
                        val pkg = customPackageInput.trim()
                        if (pkg.isNotEmpty()) {
                            mappedPackages = (mappedPackages + pkg).distinct()
                            customPackageInput = ""
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-launch First Mapped App on Manual Activation",
                        color = TellyportColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Launches the first mapped app when you click Activate from Tellyport's dashboard.",
                        color = TellyportColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = autoLaunchApp,
                    onCheckedChange = { autoLaunchApp = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TellyportColors.OnPrimary,
                        checkedTrackColor = TellyportColors.Primary
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
            .background(TellyportColors.Surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            color = TellyportColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun TellyportTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    isSecret: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TellyportColors.TextSecondary, fontSize = 12.sp) },
        placeholder = { Text(placeholder, color = TellyportColors.TextMuted, fontSize = 13.sp) },
        singleLine = true,
        visualTransformation = if (isSecret) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
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
}

/**
 * Returns the error to show for the editor's coordinate fields, or `null` when they are all
 * valid. Unparseable or non-finite values are rejected instead of defaulted, so a typo never
 * silently saves different coordinates.
 */
internal fun coordinateFieldsError(
    latitude: Double?,
    longitude: Double?,
    altitude: Double?,
    accuracyMeters: Float?
): String? = when {
    latitude == null || latitude !in -90.0..90.0 -> "Latitude must be between -90 and 90."
    longitude == null || longitude !in -180.0..180.0 -> "Longitude must be between -180 and 180."
    altitude == null || !altitude.isFinite() -> "Altitude must be a number of meters."
    accuracyMeters == null || !accuracyMeters.isFinite() || accuracyMeters <= 0f ->
        "Accuracy must be greater than 0 meters."
    else -> null
}
