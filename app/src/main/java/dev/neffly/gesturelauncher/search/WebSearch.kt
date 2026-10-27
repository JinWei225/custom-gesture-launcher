package dev.neffly.gesturelauncher.search

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import dev.neffly.gesturelauncher.R

/**
 * The browser side of the `web` command: a search on the user's chosen [Engine], or a typed
 * address opened directly.
 */
object WebSearch {

    /** The search engines the settings hub offers. [searchBase] takes the encoded query appended. */
    enum class Engine(@param:StringRes val label: Int, val searchBase: String) {
        GOOGLE(R.string.engine_google, "https://www.google.com/search?q="),
        BING(R.string.engine_bing, "https://www.bing.com/search?q="),
        DUCKDUCKGO(R.string.engine_duckduckgo, "https://duckduckgo.com/?q="),
        BRAVE(R.string.engine_brave, "https://search.brave.com/search?q="),
        ECOSIA(R.string.engine_ecosia, "https://www.ecosia.org/search?q=");

        companion object {
            val DEFAULT = GOOGLE

            /** The engine stored under [name], or [DEFAULT] for null or a name this build lacks. */
            fun fromName(name: String?): Engine = entries.firstOrNull { it.name == name } ?: DEFAULT
        }
    }

    /**
     * The address [query] refers to, or null if it doesn't look like one.
     *
     * Deliberately conservative: a single token, no whitespace, and either an explicit scheme or a
     * dot followed by a plausible TLD. "docs.google.com" opens directly; "3.5 inch" and
     * "version 2.0" stay searches.
     */
    fun detectUrl(query: String): String? {
        val q = query.trim()
        if (q.isEmpty() || q.any { it.isWhitespace() }) return null
        if (q.startsWith("http://", ignoreCase = true) || q.startsWith("https://", ignoreCase = true)) {
            return q
        }
        val host = q.substringBefore('/').substringBefore('?')
        if (!HOST.matches(host)) return null
        return "https://$q"
    }

    /** The browser intent for [query] — a search on [engine], or the address it names. */
    fun intentFor(engine: Engine, query: String, url: String?): Intent {
        val target = url ?: (engine.searchBase + Uri.encode(query.trim()))
        return Intent(Intent.ACTION_VIEW, Uri.parse(target))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** host.tld, optionally with subdomains and a port; TLD is letters only, 2+ chars. */
    private val HOST = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.[a-zA-Z]{2,}(:\\d+)?$")
}
