package tv.tellyport.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import tv.tellyport.data.WorldCity
import tv.tellyport.data.WorldCityCatalog
import tv.tellyport.ui.components.InfoBadge
import tv.tellyport.ui.components.TellyportPrimaryButton
import tv.tellyport.ui.components.TellyportSecondaryButton
import tv.tellyport.ui.components.TellyportSelectableChip
import tv.tellyport.ui.theme.TellyportColors
import java.util.Locale

private data class QuickCountryFilter(
    val label: String,
    val countryCode: String?
)

private val QUICK_COUNTRY_FILTERS = listOf(
    QuickCountryFilter("All Countries", null),
    QuickCountryFilter("US", "US"),
    QuickCountryFilter("UK", "GB"),
    QuickCountryFilter("AU", "AU"),
    QuickCountryFilter("CA", "CA"),
    QuickCountryFilter("JP", "JP"),
    QuickCountryFilter("DE", "DE"),
    QuickCountryFilter("FR", "FR"),
    QuickCountryFilter("BR", "BR"),
    QuickCountryFilter("IN", "IN")
)

/**
 * Full-screen D-Pad and keyboard friendly fuzzy city picker covering all world cities with
 * population >= 1,000,000.
 *
 * Used in two modes:
 * - **Profile selection mode** (`onSelectForProfile != null`): picking a city immediately
 *   populates a profile's mock location coordinates, altitude, region code, and name.
 * - **Quick mock mode** (`onMockCityNow != null`): selecting a city lets the user immediately
 *   start `MockLocationService` at that city or create a full regional profile for it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorldCityPickerScreen(
    title: String = "World Cities (1M+ Population)",
    subtitle: String = "Scroll or type to fuzzy-find any city over 1M population as your mock location",
    initialSelectedCityId: String? = null,
    activeMockCityName: String? = null,
    bannerMessage: String? = null,
    onDismissBanner: (() -> Unit)? = null,
    onBack: () -> Unit,
    onSelectForProfile: ((WorldCity) -> Unit)? = null,
    onMockCityNow: ((WorldCity) -> Unit)? = null,
    onCreateProfileForCity: ((WorldCity) -> Unit)? = null
) {
    val allCities = remember { WorldCityCatalog.cities }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCountryCode by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCityId by rememberSaveable { mutableStateOf(initialSelectedCityId) }

    val countryFiltered = remember(allCities, selectedCountryCode) {
        val code = selectedCountryCode
        if (code == null) allCities else allCities.filter { it.countryCode == code }
    }
    val filteredCities = remember(countryFiltered, searchQuery) {
        WorldCityCatalog.search(query = searchQuery, source = countryFiltered)
    }

    val listState = rememberLazyListState()
    val listFocusRequester = remember { FocusRequester() }
    val actionFocusRequester = remember { FocusRequester() }
    var pendingActionFocus by remember { mutableStateOf(false) }

    LaunchedEffect(filteredCities) {
        if (filteredCities.isEmpty()) {
            selectedCityId = null
        } else if (selectedCityId == null || filteredCities.none { it.id == selectedCityId }) {
            selectedCityId = filteredCities.first().id
        }
        if (searchQuery.isNotBlank()) {
            listState.scrollToItem(0)
        }
    }

    val selectedCity = remember(allCities, filteredCities, selectedCityId) {
        allCities.firstOrNull { it.id == selectedCityId } ?: filteredCities.firstOrNull()
    }

    LaunchedEffect(pendingActionFocus, selectedCity?.id) {
        if (pendingActionFocus && selectedCity != null) {
            pendingActionFocus = false
            runCatching { actionFocusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TellyportColors.Background)
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
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
                    text = "Back",
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    onClick = onBack
                )
                Column {
                    Text(
                        text = title,
                        color = TellyportColors.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$subtitle • ${allCities.size} cities offline",
                        color = TellyportColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            if (!activeMockCityName.isNullOrBlank()) {
                InfoBadge(
                    text = "Mock GPS: $activeMockCityName",
                    containerColor = TellyportColors.InfoContainer,
                    contentColor = TellyportColors.Primary
                )
            }
        }

        if (!bannerMessage.isNullOrBlank()) {
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
                    text = bannerMessage,
                    color = TellyportColors.TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                if (onDismissBanner != null) {
                    Spacer(Modifier.width(12.dp))
                    TellyportSecondaryButton(text = "Dismiss", onClick = onDismissBanner)
                }
            }
        }

        // Fuzzy search box
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
                        text = "Fuzzy-search city, country, state, or code (e.g. tokyo, melb, sao paulo, nyc, uk)...",
                        color = TellyportColors.TextMuted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search world cities",
                        tint = TellyportColors.Primary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { runCatching { listFocusRequester.requestFocus() } }
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

        // Quick country filter chips & match count
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QUICK_COUNTRY_FILTERS.forEach { filter ->
                    TellyportSelectableChip(
                        label = filter.label,
                        selected = selectedCountryCode == filter.countryCode,
                        onClick = { selectedCountryCode = filter.countryCode }
                    )
                }
            }

            Spacer(Modifier.width(12.dp))
            Text(
                text = if (searchQuery.isBlank()) {
                    "${filteredCities.size} cities"
                } else {
                    "${filteredCities.size} matches"
                },
                color = TellyportColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Responsive two-pane (TV/tablet) vs single-pane (compact)
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val isWide = maxWidth >= 680.dp

            if (isWide && onSelectForProfile == null) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Box(modifier = Modifier.weight(1.2f).fillMaxHeight()) {
                        CityListPane(
                            cities = filteredCities,
                            selectedCityId = selectedCity?.id,
                            listState = listState,
                            firstItemFocusRequester = listFocusRequester,
                            hasActiveFilter = searchQuery.isNotBlank() || selectedCountryCode != null,
                            onCityClick = { city ->
                                selectedCityId = city.id
                                pendingActionFocus = true
                            },
                            onClearFilter = {
                                searchQuery = ""
                                selectedCountryCode = null
                            }
                        )
                    }

                    Box(modifier = Modifier.weight(0.8f).fillMaxHeight()) {
                        CityActionPane(
                            city = selectedCity,
                            primaryActionFocusRequester = actionFocusRequester,
                            onMockCityNow = onMockCityNow,
                            onCreateProfileForCity = onCreateProfileForCity
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        CityListPane(
                            cities = filteredCities,
                            selectedCityId = selectedCity?.id,
                            listState = listState,
                            firstItemFocusRequester = listFocusRequester,
                            hasActiveFilter = searchQuery.isNotBlank() || selectedCountryCode != null,
                            onCityClick = { city ->
                                if (onSelectForProfile != null) {
                                    onSelectForProfile(city)
                                } else {
                                    selectedCityId = city.id
                                }
                            },
                            onClearFilter = {
                                searchQuery = ""
                                selectedCountryCode = null
                            }
                        )
                    }

                    if (onSelectForProfile == null && selectedCity != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(TellyportColors.Surface)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${selectedCity.name} (${selectedCity.regionCode})",
                                    color = TellyportColors.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "%.4f, %.4f • %.0fm • Pop %s",
                                        selectedCity.latitude,
                                        selectedCity.longitude,
                                        selectedCity.altitudeMeters,
                                        selectedCity.formattedPopulation
                                    ),
                                    color = TellyportColors.TextSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                            if (onMockCityNow != null) {
                                TellyportPrimaryButton(
                                    text = "Mock Here",
                                    icon = Icons.Rounded.MyLocation,
                                    onClick = { onMockCityNow(selectedCity) }
                                )
                            }
                            if (onCreateProfileForCity != null) {
                                TellyportSecondaryButton(
                                    text = "New Profile",
                                    icon = Icons.Rounded.Add,
                                    onClick = { onCreateProfileForCity(selectedCity) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CityListPane(
    cities: List<WorldCity>,
    selectedCityId: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    firstItemFocusRequester: FocusRequester,
    hasActiveFilter: Boolean,
    onCityClick: (WorldCity) -> Unit,
    onClearFilter: () -> Unit
) {
    if (cities.isEmpty()) {
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
                imageVector = Icons.Rounded.LocationCity,
                contentDescription = null,
                tint = TellyportColors.TextMuted,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "No world cities (1M+ population) matched your filter.",
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
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(cities, key = { it.id }) { city ->
            val isFirst = cities.firstOrNull()?.id == city.id
            WorldCityRowCard(
                city = city,
                isSelected = city.id == selectedCityId,
                modifier = if (isFirst) Modifier.focusRequester(firstItemFocusRequester) else Modifier,
                onClick = { onCityClick(city) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WorldCityRowCard(
    city: WorldCity,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) TellyportColors.Primary else TellyportColors.InfoContainer)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = city.regionCode,
                    color = if (isSelected) TellyportColors.OnPrimary else TellyportColors.Primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = city.name,
                    color = TellyportColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = city.subtitle,
                    color = TellyportColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                InfoBadge(
                    text = "Pop ${city.formattedPopulation}",
                    containerColor = TellyportColors.Background,
                    contentColor = TellyportColors.TextSecondary
                )
                Text(
                    text = String.format(Locale.US, "%.2f, %.2f • %.0fm", city.latitude, city.longitude, city.altitudeMeters),
                    color = TellyportColors.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = TellyportColors.Primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CityActionPane(
    city: WorldCity?,
    primaryActionFocusRequester: FocusRequester,
    onMockCityNow: ((WorldCity) -> Unit)?,
    onCreateProfileForCity: ((WorldCity) -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(TellyportColors.Surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (city == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Select a city to mock its GPS location or create a regional profile.",
                    color = TellyportColors.TextSecondary,
                    fontSize = 14.sp
                )
            }
            return
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = city.name,
                    color = TellyportColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${city.subtitle} (${city.countryCode})",
                    color = TellyportColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(TellyportColors.Background)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CityMetricRow("Population", "${city.formattedPopulation} (${String.format(Locale.US, "%,d", city.population)})")
            CityMetricRow("Latitude", String.format(Locale.US, "%.4f°", city.latitude))
            CityMetricRow("Longitude", String.format(Locale.US, "%.4f°", city.longitude))
            CityMetricRow("Elevation", String.format(Locale.US, "%.0f m", city.altitudeMeters))
        }

        Text(
            text = "Starts GPS + Network mock location injection immediately with realistic <2m jitter, or saves this city into a new regional VPN profile.",
            color = TellyportColors.TextSecondary,
            fontSize = 12.sp
        )

        Spacer(Modifier.weight(1f))

        if (onMockCityNow != null) {
            TellyportPrimaryButton(
                text = "Mock Location Here Now",
                icon = Icons.Rounded.MyLocation,
                onClick = { onMockCityNow(city) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(primaryActionFocusRequester)
            )
        }

        if (onCreateProfileForCity != null) {
            TellyportSecondaryButton(
                text = "Create Profile for ${city.name}",
                icon = Icons.Rounded.Add,
                onClick = { onCreateProfileForCity(city) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CityMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TellyportColors.TextMuted,
            fontSize = 12.sp
        )
        Text(
            text = value,
            color = TellyportColors.TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
