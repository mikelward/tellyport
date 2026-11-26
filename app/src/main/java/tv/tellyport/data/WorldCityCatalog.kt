package tv.tellyport.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A world city with population >= 1,000,000 available for offline mock-location selection.
 */
data class WorldCity(
    val id: String,
    val name: String,
    val asciiName: String,
    val adminName: String,
    val country: String,
    val countryCode: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val population: Long
) {
    /**
     * Region code matching Tellyport's display convention (`UK` for `GB`, otherwise ISO-2).
     */
    val regionCode: String
        get() = if (countryCode == "GB") "UK" else countryCode

    val formattedPopulation: String
        get() = String.format(Locale.US, "%.1fM", population / 1_000_000.0)

    val subtitle: String
        get() {
            val showAdmin = adminName.isNotBlank() &&
                !adminName.equals(name, ignoreCase = true) &&
                !adminName.equals(asciiName, ignoreCase = true) &&
                !adminName.equals(country, ignoreCase = true)
            return if (showAdmin) "$adminName, $country" else country
        }

    val displayLabel: String
        get() = "$name ($regionCode)"
}

/**
 * Offline catalog and fuzzy-find search engine for all world cities with population >= 1,000,000.
 *
 * Data is packaged in `/tv/tellyport/data/world_cities_1m.tsv` (SimpleMaps Basic + GeoNames
 * cities15000 elevation & supplemental city-proper 1M+ entries, CC BY 4.0) so search works
 * instantaneously on Android TV with zero network dependency.
 */
object WorldCityCatalog {

    const val MIN_POPULATION: Long = 1_000_000L
    private const val RESOURCE_PATH = "/tv/tellyport/data/world_cities_1m.tsv"

    val cities: List<WorldCity> by lazy { loadCatalog() }

    /**
     * Searches world cities >= 1M population using multi-token fuzzy matching across city name,
     * ASCII/diacritic-folded name, common city abbreviations (`NYC`, `LA`, `SF`, `CDMX`, etc.),
     * state/province, country name, ISO-2 code, and country aliases (`UK`, `USA`, `UAE`).
     *
     * When [query] is blank, returns cities sorted by population descending.
     */
    fun search(
        query: String,
        limit: Int = Int.MAX_VALUE,
        source: List<WorldCity> = cities
    ): List<WorldCity> {
        val tokens = query.trim()
            .split(WHITESPACE_REGEX)
            .filter { it.isNotEmpty() }

        if (tokens.isEmpty()) {
            return if (limit >= source.size) source else source.take(limit)
        }

        val scored = mutableListOf<Pair<WorldCity, Int>>()
        for (city in source) {
            val score = scoreCity(city, tokens)
            if (score != null) {
                scored.add(city to score)
            }
        }

        return scored
            .sortedWith(
                compareByDescending<Pair<WorldCity, Int>> { it.second }
                    .thenByDescending { it.first.population }
                    .thenBy { it.first.asciiName.lowercase() }
            )
            .take(limit)
            .map { it.first }
    }

    /**
     * Finds the nearest 1M+ city within [maxDistanceKm] of the given coordinates, or `null`.
     */
    fun findNearestCity(
        latitude: Double,
        longitude: Double,
        maxDistanceKm: Double = 35.0,
        source: List<WorldCity> = cities
    ): WorldCity? {
        var best: WorldCity? = null
        var bestDist = maxDistanceKm
        for (city in source) {
            val d = haversineKm(latitude, longitude, city.latitude, city.longitude)
            if (d <= bestDist) {
                bestDist = d
                best = city
            }
        }
        return best
    }

    internal fun scoreCity(city: WorldCity, tokens: List<String>): Int? {
        if (tokens.isEmpty()) return 0

        val aliases = cityAliases(city)
        val countrySearch = buildCountrySearchString(city)

        var totalScore = 0
        for (token in tokens) {
            val nameScore = FuzzyTextMatcher.scoreField(city.name, token)?.let { it + CITY_NAME_BONUS }
            val asciiScore = if (city.asciiName != city.name) {
                FuzzyTextMatcher.scoreField(city.asciiName, token)?.let { it + CITY_NAME_BONUS }
            } else {
                null
            }
            val aliasScore = aliases?.let { FuzzyTextMatcher.scoreField(it, token)?.let { s -> s + ALIAS_BONUS } }
            val countryScore = FuzzyTextMatcher.scoreField(countrySearch, token)?.let { it + COUNTRY_BONUS }
            val adminScore = city.adminName.takeIf { it.isNotBlank() }
                ?.let { FuzzyTextMatcher.scoreField(it, token)?.let { s -> s + ADMIN_BONUS } }

            val bestTokenScore = listOfNotNull(
                nameScore,
                asciiScore,
                aliasScore,
                countryScore,
                adminScore
            ).maxOrNull() ?: return null

            totalScore += bestTokenScore
        }

        val joined = FuzzyTextMatcher.foldDiacritics(tokens.joinToString(" "))
        val foldedName = FuzzyTextMatcher.foldDiacritics(city.name)
        if (foldedName.equals(joined, ignoreCase = true)) {
            totalScore += EXACT_CITY_BONUS
        } else if (foldedName.startsWith(joined, ignoreCase = true)) {
            totalScore += FULL_CITY_PREFIX_BONUS
        }

        // Gentle log-population tie-break (0..35 pts) so major global hubs rank cleanly ahead of
        // identically-scored smaller cities without ever overriding a stronger text match.
        val popBoost = (ln(city.population.coerceAtLeast(MIN_POPULATION).toDouble() / MIN_POPULATION) * 9.0)
            .toInt()
            .coerceIn(0, 35)
        return totalScore + popBoost
    }

    private fun buildCountrySearchString(city: WorldCity): String {
        val extra = COUNTRY_ALIASES[city.countryCode]
        return if (extra != null) {
            "${city.country} ${city.countryCode} ${city.regionCode} $extra"
        } else if (city.regionCode != city.countryCode) {
            "${city.country} ${city.countryCode} ${city.regionCode}"
        } else {
            "${city.country} ${city.countryCode}"
        }
    }

    private fun cityAliases(city: WorldCity): String? {
        return CITY_ALIASES["${city.countryCode}:${city.asciiName.lowercase()}"]
    }

    private fun loadCatalog(): List<WorldCity> {
        val stream = WorldCityCatalog::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: return fallbackCities()
        return stream.use { input ->
            parseTsv(BufferedReader(InputStreamReader(input, Charsets.UTF_8)))
        }
    }

    internal fun parseTsv(reader: BufferedReader): List<WorldCity> {
        val result = mutableListOf<WorldCity>()
        val usedIds = hashSetOf<String>()
        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val parts = rawLine.split('\t')
            if (parts.size < 9) return@forEachLine

            val name = parts[0].trim()
            val asciiName = parts[1].trim().ifEmpty { name }
            val adminName = parts[2].trim()
            val country = parts[3].trim()
            val countryCode = parts[4].trim().uppercase(Locale.US)
            val lat = parts[5].trim().toDoubleOrNull() ?: return@forEachLine
            val lon = parts[6].trim().toDoubleOrNull() ?: return@forEachLine
            val alt = parts[7].trim().toDoubleOrNull() ?: 10.0
            val pop = parts[8].trim().toLongOrNull() ?: return@forEachLine

            if (name.isEmpty() || countryCode.isEmpty()) return@forEachLine
            if (pop < MIN_POPULATION || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@forEachLine

            val baseId = buildString {
                append(countryCode.lowercase(Locale.US))
                append('-')
                append(asciiName.lowercase(Locale.US).replace(ID_CLEAN_REGEX, "_"))
                if (adminName.isNotEmpty()) {
                    append('-')
                    append(
                        FuzzyTextMatcher.foldDiacritics(adminName)
                            .lowercase(Locale.US)
                            .replace(ID_CLEAN_REGEX, "_")
                    )
                }
            }
            val uniqueId = if (usedIds.add(baseId)) baseId else "${baseId}_${result.size}"
            result.add(
                WorldCity(
                    id = uniqueId,
                    name = name,
                    asciiName = asciiName,
                    adminName = adminName,
                    country = country,
                    countryCode = countryCode,
                    latitude = lat,
                    longitude = lon,
                    altitudeMeters = alt,
                    population = pop
                )
            )
        }
        return result.sortedWith(compareByDescending<WorldCity> { it.population }.thenBy { it.asciiName.lowercase() })
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun fallbackCities(): List<WorldCity> = listOf(
        WorldCity("us-new_york-new_york", "New York", "New York", "New York", "United States", "US", 40.7128, -74.0060, 10.0, 18_908_608L),
        WorldCity("gb-london-greater_london", "London", "London", "Greater London", "United Kingdom", "GB", 51.5074, -0.1278, 11.0, 11_262_000L),
        WorldCity("au-sydney-new_south_wales", "Sydney", "Sydney", "New South Wales", "Australia", "AU", -33.8688, 151.2093, 19.0, 4_840_600L)
    )

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val ID_CLEAN_REGEX = Regex("[^a-z0-9]+")

    private const val CITY_NAME_BONUS = 240
    private const val ALIAS_BONUS = 180
    private const val COUNTRY_BONUS = 100
    private const val ADMIN_BONUS = 80
    private const val EXACT_CITY_BONUS = 220
    private const val FULL_CITY_PREFIX_BONUS = 140

    private val COUNTRY_ALIASES = mapOf(
        "GB" to "UK Britain England Scotland Wales",
        "US" to "USA America",
        "AU" to "AUS Aussie",
        "NZ" to "Aotearoa",
        "AE" to "UAE Emirates",
        "NL" to "Holland",
        "DE" to "Deutschland",
        "KR" to "ROK",
        "ZA" to "RSA"
    )

    private val CITY_ALIASES = mapOf(
        "US:new york" to "NYC Big Apple Manhattan",
        "US:los angeles" to "LA LAX",
        "US:san francisco" to "SF SFO Bay Area",
        "US:washington" to "DC Washington DC",
        "US:chicago" to "CHI",
        "US:atlanta" to "ATL",
        "US:miami" to "MIA",
        "US:seattle" to "SEA",
        "US:boston" to "BOS",
        "US:dallas" to "DFW",
        "US:houston" to "IAH",
        "US:denver" to "DEN",
        "GB:london" to "LON LHR",
        "GB:manchester" to "MAN",
        "AU:sydney" to "SYD",
        "AU:melbourne" to "MEL",
        "AU:brisbane" to "BNE",
        "AU:perth" to "PER",
        "AU:adelaide" to "ADL",
        "NZ:auckland" to "AKL",
        "CA:toronto" to "YYZ GTA",
        "CA:vancouver" to "YVR",
        "CA:montreal" to "YUL",
        "MX:mexico city" to "CDMX DF",
        "BR:rio de janeiro" to "Rio",
        "BR:sao paulo" to "SP",
        "JP:tokyo" to "TYO",
        "JP:osaka" to "OSA",
        "HK:hong kong" to "HK HKG",
        "SG:singapore" to "SG SIN",
        "MY:kuala lumpur" to "KL KUL",
        "IN:mumbai" to "Bombay BOM",
        "IN:bengaluru" to "Bangalore BLR",
        "IN:kolkata" to "Calcutta CCU",
        "IN:chennai" to "Madras MAA",
        "ZA:johannesburg" to "Joburg Jozi JHB",
        "ZA:cape town" to "CPT",
        "FR:paris" to "PAR CDG",
        "DE:munich" to "Muenchen Munchen MUC",
        "DE:berlin" to "BER",
        "NL:amsterdam" to "AMS",
        "IE:dublin" to "DUB"
    )
}
