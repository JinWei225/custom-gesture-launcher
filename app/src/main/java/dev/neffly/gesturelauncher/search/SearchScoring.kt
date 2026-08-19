package dev.neffly.gesturelauncher.search

import dev.neffly.gesturelauncher.drawer.AppInfo
import java.text.Normalizer
import java.util.Locale

/**
 * How this app ranks anything against a typed query: app labels, their user-set aliases, and file
 * names, all by the same rules — so a change made for one can't quietly reorder another.
 *
 * Kept apart from [dev.neffly.gesturelauncher.drawer.AppRepository], which loads and caches the
 * app list; what that list is *sorted by* for a given query is this file's business.
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

    /**
     * Case- and diacritic-insensitive fuzzy filter over labels: [query]'s characters must appear
     * in order (not necessarily contiguous), ranked so contiguous/word-boundary matches sort first.
     *
     * An app's user-set [AppInfo.tag], when present, is fuzzy-matched the same way and used if
     * it scores better than the label — including apps whose real label wouldn't match at all
     * (e.g. a Chinese label tagged with an English shortcut), since that's the point of a tag: a
     * guaranteed-findable alias. Any tag match is boosted above label matches (it's a deliberate
     * shortcut the user typed themselves); an exact tag match is pinned above everything.
     */
    fun rankApps(apps: List<AppInfo>, query: String): List<AppInfo> {
        val q = normalize(query)
        if (q.isEmpty()) return apps
        return apps.mapNotNull { app ->
            val tagNorm = app.tag?.let { normalize(it) }
            val score = if (tagNorm != null && tagNorm == q) {
                TAG_EXACT_SCORE
            } else {
                val tagScore = tagNorm?.let { fuzzyScore(it, q) }?.plus(TAG_MATCH_BONUS)
                val labelScore = fuzzyScore(normalize(app.label), q)
                listOfNotNull(tagScore, labelScore).maxOrNull()
            }
            score?.let { app to it }
        }.sortedWith(compareByDescending<Pair<AppInfo, Int>> { it.second }
            .thenBy { normalize(it.first.label) })
            .map { it.first }
    }

    private fun boundaryBonus(text: String, p: Int): Int {
        if (p == 0 || text[p - 1] in SEPARATORS) return WORD_BOUNDARY_BONUS
        return 0
    }

    /** Sort tier for an exact alias match — comfortably above any possible fuzzy score. */
    private const val TAG_EXACT_SCORE = Int.MAX_VALUE

    /** Added to a fuzzy alias match's score so it outranks any ordinary label match, however good
     *  — comfortably larger than a realistic label fuzzy score even for long queries/labels. */
    private const val TAG_MATCH_BONUS = 1000

    private const val MATCH_SCORE = 16
    private const val CONSECUTIVE_BONUS = 12
    private const val WORD_BOUNDARY_BONUS = 10
    private const val GAP_PENALTY = 1
    // '/' is here for file names, which the app-label side never contains.
    private val SEPARATORS = charArrayOf(' ', '-', '_', '.', '/')
    private const val NO_MATCH = Int.MIN_VALUE / 2
}
