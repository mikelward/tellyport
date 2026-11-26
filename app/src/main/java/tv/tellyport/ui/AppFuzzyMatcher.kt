package tv.tellyport.ui

import tv.tellyport.data.ProfilePresets
import tv.tellyport.data.ProfileRepository
import tv.tellyport.data.RegionalProfile
import kotlin.math.abs

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
 * Supports multi-token queries matching across app display names, package names, and assigned
 * profile metadata (city, region code, VPN backend, tunnel name) using:
 * - Exact / prefix / word-boundary / camelCase-boundary matches
 * - Substring matches
 * - Ordered subsequence fuzzy matching (e.g. `"iplr"` -> `"BBC iPlayer"`)
 * - 1-edit-distance typo tolerance (Damerau-Levenshtein <= 1 for tokens of length >= 4)
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
            val labelScore = scoreField(entry.label, token)?.let { it + LABEL_FIELD_BONUS }
            val packageScore = scoreField(entry.packageName, token)?.let { it + PACKAGE_FIELD_BONUS }
            val profileScore = profileMeta?.let { scoreField(it, token) }

            val bestTokenScore = listOfNotNull(labelScore, packageScore, profileScore).maxOrNull()
                ?: return null
            totalScore += bestTokenScore
        }

        // Slight tie-break boost if the full combined query is a direct prefix of the app label.
        val joined = tokens.joinToString(" ")
        if (entry.label.startsWith(joined, ignoreCase = true)) {
            totalScore += FULL_LABEL_PREFIX_BONUS
        }

        return totalScore
    }

    private fun lowercasePreservingLength(input: String): String {
        return CharArray(input.length) { idx -> input[idx].lowercaseChar() }.concatToString()
    }

    internal fun scoreField(candidate: String, rawToken: String): Int? {
        val token = lowercasePreservingLength(rawToken)
        if (token.isEmpty() || candidate.isEmpty()) return null
        val lower = lowercasePreservingLength(candidate)

        // 1. Exact match
        if (lower == token) return 1_000

        // 2. Direct prefix match
        if (lower.startsWith(token)) {
            return 850 - (lower.length - token.length).coerceAtMost(100)
        }

        // 3. Word-boundary prefix match (including camelCase boundaries like "iPlayer" -> "player")
        val boundaryMatchIndex = findWordBoundaryPrefix(candidate, lower, token)
        if (boundaryMatchIndex >= 0) {
            return 740 - boundaryMatchIndex.coerceAtMost(80)
        }

        // 4. General substring match
        val substringIndex = lower.indexOf(token)
        if (substringIndex >= 0) {
            return 600 - substringIndex.coerceAtMost(100)
        }

        // 5. Ordered fuzzy subsequence match (e.g. "iplr" in "BBC iPlayer")
        val subsequenceScore = scoreSubsequence(candidate, lower, token)
        if (subsequenceScore != null) {
            return subsequenceScore
        }

        // 6. Small typo tolerance (Damerau-Levenshtein <= 1) on words for tokens of length >= 4
        if (token.length >= MIN_TYPO_TOKEN_LENGTH) {
            val typoScore = scoreSingleTypoAgainstWords(lower, token)
            if (typoScore != null) {
                return typoScore
            }
        }

        return null
    }

    private fun findWordBoundaryPrefix(original: String, lower: String, token: String): Int {
        var searchFrom = 0
        while (searchFrom < lower.length) {
            val idx = lower.indexOf(token, startIndex = searchFrom)
            if (idx < 0) return -1
            if (isWordBoundary(original, idx)) return idx
            searchFrom = idx + 1
        }
        return -1
    }

    private fun isWordBoundary(original: String, index: Int): Boolean {
        if (index == 0) return true
        val prev = original[index - 1]
        val curr = original[index]
        if (!prev.isLetterOrDigit()) return true
        // CamelCase transition (e.g. "iPlayer" -> 'P', "YouTube" -> 'T')
        if (prev.isLowerCase() && curr.isUpperCase()) return true
        return false
    }

    private fun scoreSubsequence(original: String, lower: String, token: String): Int? {
        var tokenIdx = 0
        var lastMatchIdx = -1
        var consecutiveCount = 0
        var score = 260

        for (i in lower.indices) {
            if (lower[i] == token[tokenIdx]) {
                if (isWordBoundary(original, i)) {
                    score += 36
                }
                if (lastMatchIdx >= 0) {
                    val gap = i - lastMatchIdx - 1
                    if (gap == 0) {
                        consecutiveCount++
                        score += 24 + (consecutiveCount * 6).coerceAtMost(30)
                    } else {
                        consecutiveCount = 0
                        score -= (gap * 4).coerceAtMost(40)
                    }
                } else {
                    // Earlier first match scores slightly higher.
                    score -= (i * 2).coerceAtMost(30)
                }
                lastMatchIdx = i
                tokenIdx++
                if (tokenIdx == token.length) {
                    // Reject overly sparse single/two-char accidents across very long strings
                    return score.coerceAtLeast(100)
                }
            }
        }
        return null
    }

    private fun scoreSingleTypoAgainstWords(lower: String, token: String): Int? {
        val words = lower.split(WORD_SPLIT_REGEX).filter { it.length >= MIN_TYPO_TOKEN_LENGTH - 1 }
        var best: Int? = null
        for (word in words) {
            if (isWithinOneEdit(word, token)) {
                val candidateScore = 220 - abs(word.length - token.length) * 10
                if (best == null || candidateScore > best) {
                    best = candidateScore
                }
            } else if (word.length > token.length) {
                // Also allow 1-edit match against the prefix of a longer word (e.g., "ipaly" vs "iplayer").
                val prefix = word.substring(0, token.length)
                if (isWithinOneEdit(prefix, token)) {
                    val candidateScore = 180
                    if (best == null || candidateScore > best) {
                        best = candidateScore
                    }
                }
            }
        }
        return best
    }

    /**
     * Checks whether [a] and [b] are within Damerau-Levenshtein distance <= 1
     * (one insertion, deletion, substitution, or adjacent transposition).
     */
    internal fun isWithinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        val lenDiff = abs(a.length - b.length)
        if (lenDiff > 1) return false

        if (a.length == b.length) {
            var i = 0
            while (i < a.length && a[i] == b[i]) i++
            if (i == a.length) return true
            // Adjacent transposition check (e.g., "ipalyer" vs "iplayer")
            if (i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i]) {
                return a.substring(i + 2) == b.substring(i + 2)
            }
            // Single substitution check
            return a.substring(i + 1) == b.substring(i + 1)
        }

        val shorter = if (a.length < b.length) a else b
        val longer = if (a.length < b.length) b else a
        var s = 0
        var l = 0
        var skipped = false
        while (s < shorter.length && l < longer.length) {
            if (shorter[s] == longer[l]) {
                s++
                l++
            } else if (!skipped) {
                skipped = true
                l++
            } else {
                return false
            }
        }
        return true
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val WORD_SPLIT_REGEX = Regex("[^a-z0-9]+")
    private const val LABEL_FIELD_BONUS = 200
    private const val PACKAGE_FIELD_BONUS = 60
    private const val FULL_LABEL_PREFIX_BONUS = 150
    private const val MIN_TYPO_TOKEN_LENGTH = 4
}
