package dev.neffly.gesturelauncher.voice

import dev.neffly.gesturelauncher.drawer.AppInfo
import dev.neffly.gesturelauncher.search.SearchScoring

/**
 * Turns what was said into what the search bar would have been typed.
 *
 * Spoken requests are phrased as sentences ("set a timer for ten minutes", "open Spotify") where
 * typed ones are keywords, so a few lead-ins are rewritten onto the keywords
 * [dev.neffly.gesturelauncher.search.Commands] already answers to, and everything else is searched
 * exactly as heard. The search bar then does what it always does with the text: the action row and
 * app matches appear and the user picks one.
 *
 * "Open …" is the exception, because it says outright what to do: when the name is one installed
 * app it is launched straight away. The match is deliberately stricter than the search bar's fuzzy
 * one — launching without a look at the results is only right when the name really is the app's —
 * and a name that fits several apps equally (two apps both called "Authenticator") is searched,
 * so the user picks.
 *
 * Unmatched speech is still only searched here. Quick search, which can see whether the text found
 * anything, turns a search with no results into a web search — see QuickSearchActivity.
 */
object VoiceQuery {

    /** [query] is the text for the search bar; [open] is set when the speaker asked to open it. */
    data class Spoken(val query: String, val open: Boolean)

    sealed interface Resolution {
        data class Launch(val app: AppInfo) : Resolution
        data class Search(val query: String) : Resolution
    }

    /**
     * The first "open …" hypothesis that names any installed app decides, from all of [candidates]:
     * the recognizer's best guess at an unusual name is often an ordinary word, and the right
     * spelling is further down its list. It launches the app when it names exactly one, and
     * searches for the name when it fits several. With no app named, the best guess is searched.
     *
     * Hypotheses containing one of the user's [words] are taken first. Those are the words the
     * user has said the recognizer gets wrong — "Ollama" heard as "Obama" — so when the
     * recognizer's own alternatives include the right one, it is the one meant.
     */
    fun resolve(candidates: List<String>, apps: List<AppInfo>, words: List<String> = emptyList()): Resolution {
        val spoken = candidates
            .sortedByDescending { c -> words.any { c.contains(it, ignoreCase = true) } }
            .map(::read)
        for (s in spoken) {
            if (!s.open) continue
            val matches = matchingApps(apps, s.query)
            if (matches.size == 1) return Resolution.Launch(matches.single())
            if (matches.size > 1) return Resolution.Search(s.query)
        }
        return Resolution.Search(spoken.first().query)
    }

    /**
     * What to steer the recognizer toward: the user's own [words] first, then app names and
     * aliases — an alias like "hok" is no word it would otherwise write. Format characters are
     * dropped — they are invisible, and the recognizer is matching sounds, not code points.
     */
    fun vocabulary(apps: List<AppInfo>, words: List<String> = emptyList()): List<String> =
        (words + apps.flatMap { listOfNotNull(it.label, it.tag) })
            .map { name -> name.filterNot { Character.getType(it) == Character.FORMAT.toInt() }.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun read(utterance: String): Spoken {
        val text = utterance
            .replace(SPOKEN_MERIDIEM, "$1m")
            .trim()
            .trimEnd('.', '!', '?', '。', '！', '？')
            .trim()
        for ((pattern, keyword) in REWRITES) {
            pattern.matchEntire(text)?.let { match ->
                // The space after the keyword is what makes it a command, even with nothing after.
                return Spoken("$keyword ${match.groupValues.last().trim()}", open = false)
            }
        }
        OPEN.matchEntire(text)?.let { return Spoken(it.groupValues[1].trim(), open = true) }
        return Spoken(text, open = false)
    }

    /**
     * The apps [name] fits best, or none. Compared with spaces and punctuation removed, since a
     * recognizer writes "WhatsApp" as "what's app" and an alias spelled out letter by letter as
     * "h o k": an exact name first, then a name that starts with what was said ("keep" → "Keep
     * Notes"), then one with a later word that does ("calendar" → "Google Calendar"). Only the best
     * tier is returned, so "camera" is Camera alone even with Camera Raw installed. The user's
     * alias for an app counts the same as its label.
     */
    internal fun matchingApps(apps: List<AppInfo>, name: String): List<AppInfo> {
        val q = compact(name)
        if (q.isEmpty()) return emptyList()
        val tiers = apps.mapNotNull { app ->
            listOfNotNull(app.normalizedTag, app.normalizedLabel)
                .mapNotNull { tier(it, q) }
                .minOrNull()
                ?.let { app to it }
        }
        val best = tiers.minOfOrNull { it.second } ?: return emptyList()
        return tiers.filter { it.second == best }.map { it.first }
    }

    private fun tier(normalizedName: String, q: String): Int? {
        val words = normalizedName.split(NON_WORD).filter { it.isNotEmpty() }
        if (words.joinToString("") == q) return 0
        for (i in words.indices) {
            if (words.drop(i).joinToString("").startsWith(q)) return if (i == 0) 1 else 2
        }
        return null
    }

    private fun compact(s: String): String = SearchScoring.normalize(s).filter { it.isLetterOrDigit() }

    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /** "7 a.m." → "7 am": the dots would otherwise be left behind in an alarm's label. */
    private val SPOKEN_MERIDIEM = Regex("""\b([ap])\.m\.?""", RegexOption.IGNORE_CASE)

    private val OPEN = Regex("""(?:open|launch)\s+(?:the\s+|my\s+)?(.+?)(?:\s+app)?""", RegexOption.IGNORE_CASE)

    /** Checked before [OPEN] and in order; the last group of each is the rest of the request. */
    private val REWRITES = listOf(
        Regex("""(?:set|start)\s+(?:an?\s+|the\s+)?timer\s*(?:for\s+)?(.*)""", RegexOption.IGNORE_CASE) to "timer",
        Regex("""(?:set|add)\s+(?:an?\s+|the\s+)?alarm\s*(?:for\s+|at\s+)?(.*)""", RegexOption.IGNORE_CASE) to "alarm",
        Regex("""(?:add|create|new|schedule)\s+(?:an?\s+|new\s+)?event\s*(.*)""", RegexOption.IGNORE_CASE) to "event",
        Regex("""(?:search|google|look\s+up)\s+(?:the\s+web\s+)?(?:for\s+)?(.+)""", RegexOption.IGNORE_CASE) to "web",
        Regex("""(?:navigate|directions|take\s+me)\s+to\s+(.+)""", RegexOption.IGNORE_CASE) to "map"
    )
}
