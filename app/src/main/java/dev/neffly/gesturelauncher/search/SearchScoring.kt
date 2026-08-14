package dev.neffly.gesturelauncher.search

import java.text.Normalizer
import java.util.Locale

/**
 * The drawer's fuzzy matcher, lifted out of AppRepository so file names are ranked by exactly the
 * same rules as app labels. Behaviour is unchanged from when it lived there.
 */
internal object SearchScoring {

    /** Lowercase, strip diacritics, trim — so "Café" matches "cafe". */
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .trim()

    /**
     * Best-alignment score for [query] as a subsequence of [text] (both already normalized), or
     * null if [query]'s characters don't all appear in [text] in order. Rewards contiguous runs
     * and word-boundary starts so e.g. "gm" ranks "Google Maps" above "Backgammon".
     */
    fun fuzzyScore(text: String, query: String): Int? {
        if (query.isEmpty()) return 0
        // dp[p] = best score to match the first i query chars, with the i-th match landing at
        // text position p (0-indexed); rebuilt one row per query character.
        var dp = IntArray(text.length) { p ->
            if (text[p] == query[0]) MATCH_SCORE + boundaryBonus(text, p) - GAP_PENALTY * p
            else NO_MATCH
        }
        for (i in 1 until query.length) {
            val next = IntArray(text.length) { NO_MATCH }
            for (p in i until text.length) {
                if (text[p] != query[i]) continue
                var best = NO_MATCH
                for (prevP in i - 1 until p) {
                    if (dp[prevP] <= NO_MATCH) continue
                    val gap = p - prevP - 1
                    val bonus = if (gap == 0) CONSECUTIVE_BONUS else -GAP_PENALTY * gap
                    best = maxOf(best, dp[prevP] + bonus)
                }
                if (best > NO_MATCH) next[p] = best + MATCH_SCORE + boundaryBonus(text, p)
            }
            dp = next
        }
        val best = dp.maxOrNull() ?: NO_MATCH
        return if (best <= NO_MATCH) null else best
    }

    private fun boundaryBonus(text: String, p: Int): Int {
        if (p == 0 || text[p - 1] in SEPARATORS) return WORD_BOUNDARY_BONUS
        return 0
    }

    private const val MATCH_SCORE = 16
    private const val CONSECUTIVE_BONUS = 12
    private const val WORD_BOUNDARY_BONUS = 10
    private const val GAP_PENALTY = 1
    // '/' is here for file names, which the app-label side never contains.
    private val SEPARATORS = charArrayOf(' ', '-', '_', '.', '/')
    private const val NO_MATCH = Int.MIN_VALUE / 2
}
