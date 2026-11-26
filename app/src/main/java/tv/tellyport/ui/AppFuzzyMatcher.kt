package tv.tellyport.ui

import tv.tellyport.data.FuzzyTextMatcher
import tv.tellyport.data.ProfilePresets
import tv.tellyport.data.ProfileRepository
import tv.tellyport.data.RegionalProfile

/**
 * Filter chips available in the App Routing Configurator.
 */
enum class AppFilterMode(val label: String) {
    ALL("All"),
    MAPPED("Mapped"),
    UNMAPPED("Unmapped")
}

/**
 * Unified row model for an app shown in the App Routing Configurator, combining installed
 * device apps and any mapped packages that are not yet installed.
 */
data class ConfigurableAppEntry(
    val label: String,
    val packageName: String,
    val isInstalled: Boolean,
    val assignedProfile: RegionalProfile?
)

/**
 * Pure-Kotlin fuzzy matcher and list builder for the App Routing Configurator.
 *
 * Delegates core fuzzy field matching to [FuzzyTextMatcher] and ranks multi-token matches
 * across app display names, package names, and assigned profile metadata.
 */
object AppFuzzyMatcher {

    fun buildConfigurableAppList(
        installedApps: List<InstalledTvApp>,
        profiles: List<RegionalProfile>
    ): List<ConfigurableAppEntry> {
        val byPackage = linkedMapOf<String, ConfigurableAppEntry>()

        for (app in installedApps) {
            val pkg = app.packageName.trim()
            if (pkg.isEmpty() || pkg in byPackage) continue
            val assigned = ProfileRepository.findProfileForPackage(profiles, pkg)
            byPackage[pkg] = ConfigurableAppEntry(
                label = app.label.trim().ifEmpty { pkg },
                packageName = pkg,
                isInstalled = true,
                assignedProfile = assigned
            )
        }

        // Include any mapped packages that are not currently installed on the device so users
        // can inspect, reassign, or unmap them too.
        for (profile in profiles) {
            for (rawPkg in profile.mappedPackages) {
                val pkg = rawPkg.trim()
                if (pkg.isEmpty() || pkg in byPackage) continue
                byPackage[pkg] = ConfigurableAppEntry(
                    label = ProfilePresets.friendlyAppName(pkg) ?: pkg,
                    packageName = pkg,
                    isInstalled = false,
                    assignedProfile = profile
                )
            }
        }

        return byPackage.values.sortedWith(
            compareBy<ConfigurableAppEntry>({ it.label.lowercase() }, { it.packageName.lowercase() })
        )
    }

    fun filterAndRank(
        entries: List<ConfigurableAppEntry>,
        query: String,
        filterMode: AppFilterMode = AppFilterMode.ALL
    ): List<ConfigurableAppEntry> {
        val modeFiltered = when (filterMode) {
            AppFilterMode.ALL -> entries
            AppFilterMode.MAPPED -> entries.filter { it.assignedProfile != null }
            AppFilterMode.UNMAPPED -> entries.filter { it.assignedProfile == null }
        }

        val tokens = query.trim()
            .split(WHITESPACE_REGEX)
            .filter { it.isNotEmpty() }

        if (tokens.isEmpty()) {
            return modeFiltered
        }

        val scored = mutableListOf<Pair<ConfigurableAppEntry, Int>>()
        for (entry in modeFiltered) {
            val score = scoreEntry(entry, tokens)
            if (score != null) {
                scored.add(entry to score)
            }
        }

        return scored
            .sortedWith(
                compareByDescending<Pair<ConfigurableAppEntry, Int>> { it.second }
                    .thenByDescending { it.first.isInstalled }
                    .thenBy { it.first.label.lowercase() }
                    .thenBy { it.first.packageName.lowercase() }
            )
            .map { it.first }
    }

    internal fun scoreEntry(entry: ConfigurableAppEntry, tokens: List<String>): Int? {
        if (tokens.isEmpty()) return 0

        val profileMeta = entry.assignedProfile?.let { profile ->
            buildString {
                append(profile.name)
                append(' ')
                append(profile.regionCode)
                append(' ')
                append(profile.vpnBackend.displayName)
                if (!profile.tunnelName.isNullOrBlank()) {
                    append(' ')
                    append(profile.tunnelName)
                }
            }
        }

        var totalScore = 0
        for (token in tokens) {
            val labelScore = FuzzyTextMatcher.scoreField(entry.label, token)?.let { it + LABEL_FIELD_BONUS }
            val packageScore = FuzzyTextMatcher.scoreField(entry.packageName, token)?.let { it + PACKAGE_FIELD_BONUS }
            val profileScore = profileMeta?.let { FuzzyTextMatcher.scoreField(it, token) }

            val bestTokenScore = listOfNotNull(labelScore, packageScore, profileScore).maxOrNull()
                ?: return null
            totalScore += bestTokenScore
        }

        val joined = tokens.joinToString(" ")
        if (FuzzyTextMatcher.foldDiacritics(entry.label)
                .startsWith(FuzzyTextMatcher.foldDiacritics(joined), ignoreCase = true)
        ) {
            totalScore += FULL_LABEL_PREFIX_BONUS
        }

        return totalScore
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val LABEL_FIELD_BONUS = 200
    private const val PACKAGE_FIELD_BONUS = 60
    private const val FULL_LABEL_PREFIX_BONUS = 150
}
