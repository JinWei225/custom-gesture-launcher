package dev.neffly.gesturelauncher.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import dev.neffly.gesturelauncher.R

/** Sends a query to the default browser — as a Google search, or straight to a typed address. */
object WebSearch {

    private const val SEARCH_BASE = "https://www.google.com/search?q="

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

    /** The browser intent for [query] — a search, or the address it names. Split out from [open]
     *  so a floating-window launch can attach its own ActivityOptions to the same intent. */
    fun intentFor(query: String, url: String?): Intent {
        val target = url ?: (SEARCH_BASE + Uri.encode(query.trim()))
        return Intent(Intent.ACTION_VIEW, Uri.parse(target))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Opens [query] in the default browser, as a search or as the address it names. */
    fun open(context: Context, query: String, url: String?) {
        if (runCatching { context.startActivity(intentFor(query, url)) }.isFailure) {
            Toast.makeText(context, R.string.web_search_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** host.tld, optionally with subdomains and a port; TLD is letters only, 2+ chars. */
    private val HOST = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.[a-zA-Z]{2,}(:\\d+)?$")
}
