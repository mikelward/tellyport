package tv.tellyport.data

import java.text.Normalizer
import kotlin.math.abs

/**
 * Shared pure-Kotlin fuzzy text matcher used across Tellyport (App Routing Configurator
 * and 1M+ World City Mock Location Picker).
 *
 * Supports:
 * - Case-insensitive and diacritic-folded matching (e.g. `"sao paulo"` -> `"São Paulo"`)
 * - Exact, prefix, word-boundary, and camelCase-boundary matches
 * - Substring matches
 * - Ordered fuzzy subsequence matching (e.g. `"iplr"` -> `"BBC iPlayer"`, `"syd"` -> `"Sydney"`)
 * - Single-edit Damerau-Levenshtein typo tolerance for tokens of length >= 4
 */
object FuzzyTextMatcher {

    private val COMBINING_MARKS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val WORD_SPLIT_REGEX = Regex("[^a-z0-9]+")
    private const val MIN_TYPO_TOKEN_LENGTH = 4

    /**
     * Folds diacritics/accents to ASCII equivalents while keeping character positions aligned
     * for single-codepoint accented Latin characters (e.g. `"São Paulo"` -> `"Sao Paulo"`).
     */
    fun foldDiacritics(input: String): String {
        if (input.isEmpty()) return input
        var hasNonAscii = false
        for (ch in input) {
            if (ch.code >= 128) {
                hasNonAscii = true
                break
            }
        }
        if (!hasNonAscii) return input
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        return COMBINING_MARKS_REGEX.replace(normalized, "")
            .replace('ø', 'o')
            .replace('Ø', 'O')
            .replace('ł', 'l')
            .replace('Ł', 'L')
            .replace('đ', 'd')
            .replace('Đ', 'D')
            .replace('ß', 's')
    }

    private fun lowercasePreservingLength(input: String): String {
        return CharArray(input.length) { idx -> input[idx].lowercaseChar() }.concatToString()
    }

    /**
     * Scores how well [rawToken] matches [candidate], returning `null` if there is no match.
     * Higher scores represent stronger matches.
     */
    fun scoreField(candidate: String, rawToken: String): Int? {
        val token = lowercasePreservingLength(foldDiacritics(rawToken))
        if (token.isEmpty() || candidate.isEmpty()) return null
        val folded = foldDiacritics(candidate)
        val lower = lowercasePreservingLength(folded)

        // 1. Exact match
        if (lower == token) return 1_000

        // 2. Direct prefix match
        if (lower.startsWith(token)) {
            return 850 - (lower.length - token.length).coerceAtMost(100)
        }

        // 3. Word-boundary prefix match (including camelCase boundaries like "iPlayer" -> "player")
        val boundaryMatchIndex = findWordBoundaryPrefix(folded, lower, token)
        if (boundaryMatchIndex >= 0) {
            return 740 - boundaryMatchIndex.coerceAtMost(80)
        }

        // 4. General substring match
        val substringIndex = lower.indexOf(token)
        if (substringIndex >= 0) {
            return 600 - substringIndex.coerceAtMost(100)
        }

        // 5. Ordered fuzzy subsequence match (e.g. "iplr" in "BBC iPlayer")
        val subsequenceScore = scoreSubsequence(folded, lower, token)
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
                    score -= (i * 2).coerceAtMost(30)
                }
                lastMatchIdx = i
                tokenIdx++
                if (tokenIdx == token.length) {
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
    fun isWithinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        val lenDiff = abs(a.length - b.length)
        if (lenDiff > 1) return false

        if (a.length == b.length) {
            var i = 0
            while (i < a.length && a[i] == b[i]) i++
            if (i == a.length) return true
            if (i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i]) {
                return a.substring(i + 2) == b.substring(i + 2)
            }
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
}
