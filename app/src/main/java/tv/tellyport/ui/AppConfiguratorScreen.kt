package tv.tellyport.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PublicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.tellyport.data.RegionalProfile
import tv.tellyport.data.VpnBackend
import tv.tellyport.ui.components.InfoBadge
import tv.tellyport.ui.components.TellyportPrimaryButton
import tv.tellyport.ui.components.TellyportSecondaryButton
import tv.tellyport.ui.components.TellyportSelectableChip
import tv.tellyport.ui.theme.TellyportColors
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppConfiguratorScreen(
    state: TellyportUiState,
    onBack: () -> Unit,
    onRefreshApps: () -> Unit,
    onAssignAppToProfile: (packageName: String, appLabel: String, targetProfileId: String?) -> Unit,
    onEditProfile: (RegionalProfile) -> Unit,
    onCreateProfile: () -> Unit,
    onDismissBanner: () -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filterMode by rememberSaveable { mutableStateOf(AppFilterMode.ALL) }
    var selectedPackageName by rememberSaveable { mutableStateOf<String?>(null) }
    var compactPickerOpen by rememberSaveable { mutableStateOf(false) }

    val allEntries = remember(state.installedApps, state.profiles) {
        AppFuzzyMatcher.buildConfigurableAppList(state.installedApps, state.profiles)
    }
    val filteredEntries = remember(allEntries, searchQuery, filterMode) {
        AppFuzzyMatcher.filterAndRank(allEntries, searchQuery, filterMode)
    }

    val mappedCount = remember(allEntries) { allEntries.count { it.assignedProfile != null } }
    val unmappedCount = allEntries.size - mappedCount

    // Keep selection coherent when filter results change.
    LaunchedEffect(filteredEntries) {
        if (filteredEntries.isEmpty()) {
            selectedPackageName = null
            compactPickerOpen = false
        } else if (selectedPackageName == null || filteredEntries.none { it.packageName == selectedPackageName }) {
            selectedPackageName = filteredEntries.first().packageName
        }
    }

    val selectedEntry = remember(allEntries, filteredEntries, selectedPackageName) {
        allEntries.firstOrNull { it.packageName == selectedPackageName }
            ?: filteredEntries.firstOrNull()
    }

    val appListFocusRequester = remember { FocusRequester() }
    val profilePickerFocusRequester = remember { FocusRequester() }
    var pendingPickerFocus by remember { mutableStateOf(false) }

    LaunchedEffect(pendingPickerFocus, selectedEntry?.packageName) {
        if (pendingPickerFocus && selectedEntry != null) {
            pendingPickerFocus = false
            runCatching { profilePickerFocusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TellyportColors.Background)
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header bar
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
                        text = "App Routing Configurator",
                        color = TellyportColors.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$mappedCount of ${allEntries.size} apps mapped • Scroll or type to fuzzy-filter, then pick a VPN / country profile",
                        color = TellyportColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TellyportSecondaryButton(
                    text = "Refresh Apps",
                    icon = Icons.Rounded.Refresh,
                    onClick = onRefreshApps
                )
                TellyportPrimaryButton(
                    text = "New Profile",
                    icon = Icons.Rounded.Add,
                    onClick = onCreateProfile
                )
            }
        }

        // Optional confirmation/error banner
        if (!state.bannerMessage.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(TellyportColors.InfoContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.bannerMessage,
                    color = TellyportColors.TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                TellyportSecondaryButton(text = "Dismiss", onClick = onDismissBanner)
            }
        }

        // Search box & filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = "Type to fuzzy-filter apps, package IDs, countries, or VPNs (e.g. iplayer, stan, uk)...",
                        color = TellyportColors.TextMuted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search apps",
                        tint = TellyportColors.Primary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        runCatching { appListFocusRequester.requestFocus() }
                    }
                ),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TellyportColors.TextPrimary,
                    unfocusedTextColor = TellyportColors.TextPrimary,
                    focusedContainerColor = TellyportColors.Surface,
                    unfocusedContainerColor = TellyportColors.Surface,
                    focusedBorderColor = TellyportColors.FocusRing,
                    unfocusedBorderColor = TellyportColors.BorderSubtle,
                    cursorColor = TellyportColors.Primary
                )
            )

            if (searchQuery.isNotEmpty()) {
                TellyportSecondaryButton(
                    text = "Clear",
                    icon = Icons.Rounded.Clear,
                    onClick = { searchQuery = "" }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TellyportSelectableChip(
                    label = "${AppFilterMode.ALL.label} (${allEntries.size})",
                    selected = filterMode == AppFilterMode.ALL,
                    onClick = { filterMode = AppFilterMode.ALL }
                )
                TellyportSelectableChip(
                    label = "${AppFilterMode.MAPPED.label} ($mappedCount)",
                    selected = filterMode == AppFilterMode.MAPPED,
                    onClick = { filterMode = AppFilterMode.MAPPED }
                )
                TellyportSelectableChip(
                    label = "${AppFilterMode.UNMAPPED.label} ($unmappedCount)",
                    selected = filterMode == AppFilterMode.UNMAPPED,
                    onClick = { filterMode = AppFilterMode.UNMAPPED }
                )
            }

            Text(
                text = if (searchQuery.isBlank()) {
                    "${filteredEntries.size} apps"
                } else {
                    "${filteredEntries.size} matches for \"$searchQuery\""
                },
                color = TellyportColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Responsive two-pane (TV / tablet) vs single-pane picker (narrow mobile)
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val isWideLayout = maxWidth >= 680.dp

            if (isWideLayout) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Left pane: scrollable fuzzy-filtered apps list
                    Box(
                        modifier = Modifier
                            .weight(1.15f)
                            .fillMaxHeight()
                    ) {
                        AppListPane(
                            entries = filteredEntries,
                            selectedPackageName = selectedEntry?.packageName,
                            isLoading = state.isLoadingInstalledApps && allEntries.isEmpty(),
                            hasActiveFilter = searchQuery.isNotBlank() || filterMode != AppFilterMode.ALL,
                            firstItemFocusRequester = appListFocusRequester,
                            onSelectApp = { entry ->
                                selectedPackageName = entry.packageName
                                pendingPickerFocus = true
                            },
                            onClearFilter = {
                                searchQuery = ""
                                filterMode = AppFilterMode.ALL
                            }
                        )
                    }

                    // Right pane: Profile / VPN / Country selector for the chosen app
                    Box(
                        modifier = Modifier
                            .weight(0.85f)
                            .fillMaxHeight()
                    ) {
                        ProfileSelectorPane(
                            selectedApp = selectedEntry,
                            profiles = state.profiles,
                            firstOptionFocusRequester = profilePickerFocusRequester,
                            onAssignProfile = { targetProfileId ->
                                selectedEntry?.let { entry ->
                                    onAssignAppToProfile(entry.packageName, entry.label, targetProfileId)
                                }
                            },
                            onEditProfile = onEditProfile,
                            onCreateProfile = onCreateProfile
                        )
                    }
                }
            } else {
                // Compact screen: toggle between filtered list and full-width profile selector
                BackHandler(enabled = compactPickerOpen) {
                    compactPickerOpen = false
                }
                if (compactPickerOpen && selectedEntry != null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TellyportSecondaryButton(
                            text = "Back to App List",
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            onClick = { compactPickerOpen = false }
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            ProfileSelectorPane(
                                selectedApp = selectedEntry,
                                profiles = state.profiles,
                                firstOptionFocusRequester = profilePickerFocusRequester,
                                onAssignProfile = { targetProfileId ->
                                    onAssignAppToProfile(selectedEntry.packageName, selectedEntry.label, targetProfileId)
                                    compactPickerOpen = false
                                },
                                onEditProfile = onEditProfile,
                                onCreateProfile = onCreateProfile
                            )
                        }
                    }
                } else {
                    AppListPane(
                        entries = filteredEntries,
                        selectedPackageName = selectedEntry?.packageName,
                        isLoading = state.isLoadingInstalledApps && allEntries.isEmpty(),
                        hasActiveFilter = searchQuery.isNotBlank() || filterMode != AppFilterMode.ALL,
                        firstItemFocusRequester = appListFocusRequester,
                        onSelectApp = { entry ->
                            selectedPackageName = entry.packageName
                            compactPickerOpen = true
                        },
                        onClearFilter = {
                            searchQuery = ""
                            filterMode = AppFilterMode.ALL
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppListPane(
    entries: List<ConfigurableAppEntry>,
    selectedPackageName: String?,
    isLoading: Boolean,
    hasActiveFilter: Boolean,
    firstItemFocusRequester: FocusRequester,
    onSelectApp: (ConfigurableAppEntry) -> Unit,
    onClearFilter: () -> Unit
) {
    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(TellyportColors.Surface),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Scanning installed apps...",
                color = TellyportColors.TextSecondary,
                fontSize = 15.sp
            )
        }
        return
    }

    if (entries.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(TellyportColors.Surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.Apps,
                contentDescription = null,
                tint = TellyportColors.TextMuted,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (hasActiveFilter) {
                    "No apps match your current filter."
                } else {
                    "No launchable apps were discovered on this device."
                },
                color = TellyportColors.TextSecondary,
                fontSize = 14.sp
            )
            if (hasActiveFilter) {
                Spacer(Modifier.height(14.dp))
                TellyportSecondaryButton(
                    text = "Reset Filter",
                    icon = Icons.Rounded.Clear,
                    onClick = onClearFilter
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries, key = { it.packageName }) { entry ->
            val isFirst = entries.firstOrNull()?.packageName == entry.packageName
            AppRowCard(
                entry = entry,
                isSelected = entry.packageName == selectedPackageName,
                modifier = if (isFirst) Modifier.focusRequester(firstItemFocusRequester) else Modifier,
                onClick = { onSelectApp(entry) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppRowCard(
    entry: ConfigurableAppEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profile = entry.assignedProfile
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) TellyportColors.SurfaceElevated else TellyportColors.Surface,
            contentColor = TellyportColors.TextPrimary,
            focusedContainerColor = TellyportColors.SurfaceFocused,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = if (isSelected) 1.75.dp else 1.dp,
                    color = if (isSelected) TellyportColors.Primary else TellyportColors.BorderSubtle
                ),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.5.dp, TellyportColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppIconOrMonogram(
                packageName = entry.packageName,
                label = entry.label,
                isInstalled = entry.isInstalled,
                modifier = Modifier.size(38.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = entry.label,
                        color = TellyportColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!entry.isInstalled) {
                        InfoBadge(
                            text = "Not installed",
                            containerColor = TellyportColors.Background,
                            contentColor = TellyportColors.TextMuted
                        )
                    }
                }
                Text(
                    text = entry.packageName,
                    color = TellyportColors.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (profile != null) {
                val badgeColor = when (profile.vpnBackend) {
                    VpnBackend.UNCONFIGURED -> TellyportColors.WarningContainer
                    VpnBackend.NONE -> TellyportColors.InfoContainer
                    else -> TellyportColors.SuccessContainer
                }
                val badgeTextColor = when (profile.vpnBackend) {
                    VpnBackend.UNCONFIGURED -> TellyportColors.Warning
                    VpnBackend.NONE -> TellyportColors.Primary
                    else -> TellyportColors.Success
                }
                InfoBadge(
                    text = "${profile.regionCode} • ${profile.name} • ${profile.vpnBackend.displayName}",
                    containerColor = badgeColor,
                    contentColor = badgeTextColor
                )
            } else {
                InfoBadge(
                    text = "Direct",
                    containerColor = TellyportColors.Background,
                    contentColor = TellyportColors.TextMuted
                )
            }
        }
    }
}

@Composable
private fun ProfileSelectorPane(
    selectedApp: ConfigurableAppEntry?,
    profiles: List<RegionalProfile>,
    firstOptionFocusRequester: FocusRequester,
    onAssignProfile: (String?) -> Unit,
    onEditProfile: (RegionalProfile) -> Unit,
    onCreateProfile: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(TellyportColors.Surface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (selectedApp == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Select an app from the list to assign a VPN / country profile.",
                    color = TellyportColors.TextSecondary,
                    fontSize = 14.sp
                )
            }
            return
        }

        // Selected app summary header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(TellyportColors.Background)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppIconOrMonogram(
                packageName = selectedApp.packageName,
                label = selectedApp.label,
                isInstalled = selectedApp.isInstalled,
                modifier = Modifier.size(42.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedApp.label,
                    color = TellyportColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = selectedApp.packageName,
                    color = TellyportColors.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Text(
            text = "Connection Profile / VPN / Country",
            color = TellyportColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Option 0: Direct / No VPN (unmapped)
            item(key = "direct-unmapped") {
                DirectOptionCard(
                    selected = selectedApp.assignedProfile == null,
                    modifier = Modifier.focusRequester(firstOptionFocusRequester),
                    onClick = { onAssignProfile(null) }
                )
            }

            // Options 1..N: Each configured RegionalProfile
            items(profiles, key = { it.id }) { profile ->
                ProfileOptionCard(
                    profile = profile,
                    selected = selectedApp.assignedProfile?.id == profile.id,
                    onClick = { onAssignProfile(profile.id) }
                )
            }
        }

        // Quick profile edit/create footer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val assigned = selectedApp.assignedProfile
            if (assigned != null) {
                TellyportSecondaryButton(
                    text = "Edit ${assigned.name}",
                    icon = Icons.Rounded.Edit,
                    onClick = { onEditProfile(assigned) },
                    modifier = Modifier.weight(1f)
                )
            }
            TellyportSecondaryButton(
                text = "New Profile",
                icon = Icons.Rounded.Add,
                onClick = onCreateProfile,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DirectOptionCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) TellyportColors.InfoContainer else TellyportColors.SurfaceElevated,
            contentColor = TellyportColors.TextPrimary,
            focusedContainerColor = TellyportColors.SurfaceFocused,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) TellyportColors.Primary else TellyportColors.BorderSubtle
                ),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.5.dp, TellyportColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.PublicOff,
                contentDescription = null,
                tint = if (selected) TellyportColors.Primary else TellyportColors.TextMuted,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Direct / No VPN (Unmapped)",
                    color = TellyportColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Launch normally without Tellyport interception",
                    color = TellyportColors.TextSecondary,
                    fontSize = 11.sp
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = TellyportColors.Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfileOptionCard(
    profile: RegionalProfile,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUnconfigured = profile.vpnBackend == VpnBackend.UNCONFIGURED
    val backendDetail = buildString {
        append(profile.vpnBackend.displayName)
        if (!profile.tunnelName.isNullOrBlank()) {
            append(" • ")
            append(profile.tunnelName)
        }
    }

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) TellyportColors.InfoContainer else TellyportColors.SurfaceElevated,
            contentColor = TellyportColors.TextPrimary,
            focusedContainerColor = TellyportColors.SurfaceFocused,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) TellyportColors.Primary else TellyportColors.BorderSubtle
                ),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.5.dp, TellyportColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) TellyportColors.Primary else TellyportColors.Background)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = profile.regionCode.ifBlank { "??" },
                    color = if (selected) TellyportColors.OnPrimary else TellyportColors.Primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = profile.name,
                        color = TellyportColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isUnconfigured) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = "VPN backend unconfigured",
                            tint = TellyportColors.Warning,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Text(
                    text = if (isUnconfigured) {
                        "VPN not configured yet • Edit profile to assign backend"
                    } else {
                        String.format(
                            Locale.US,
                            "%s • %.3f, %.3f",
                            backendDetail,
                            profile.latitude,
                            profile.longitude
                        )
                    },
                    color = if (isUnconfigured) TellyportColors.Warning else TellyportColors.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = TellyportColors.Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun AppIconOrMonogram(
    packageName: String,
    label: String,
    isInstalled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appIcon by produceState<ImageBitmap?>(initialValue = null, packageName, isInstalled) {
        value = if (isInstalled) {
            withContext(Dispatchers.IO) { loadAppIconBitmap(context, packageName) }
        } else {
            null
        }
    }

    if (appIcon != null) {
        Image(
            bitmap = appIcon!!,
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(10.dp))
        )
    } else {
        val initials = remember(label, packageName) {
            val source = label.trim().ifEmpty { packageName }
            source.split(Regex("[\\s._-]+"))
                .filter { it.isNotEmpty() }
                .take(2)
                .joinToString("") { it.first().uppercaseChar().toString() }
                .ifEmpty { "?" }
        }
        val accentPalette = listOf(
            Color(0xFF1E3A5F),
            Color(0xFF28355E),
            Color(0xFF1D4347),
            Color(0xFF3E2C59),
            Color(0xFF423526)
        )
        val containerColor = accentPalette[(packageName.hashCode() and Int.MAX_VALUE) % accentPalette.size]

        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(containerColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                color = TellyportColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun loadAppIconBitmap(context: Context, packageName: String): ImageBitmap? {
    return try {
        val rawDrawable = context.packageManager.getApplicationIcon(packageName)
        val drawable = rawDrawable.constantState?.newDrawable()?.mutate() ?: rawDrawable
        val sizePx = 96
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}
