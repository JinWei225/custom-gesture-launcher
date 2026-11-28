package dev.neffly.gesturelauncher.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import dev.neffly.gesturelauncher.R

/** Opening a typed web address — shared by quicklinks and the "Open a URL" gesture action. */
object Links {

    /**
     * [raw] as an address to save, or null when it names a scheme that isn't one of [SCHEMES].
     *
     * A bare address ("github.com") gets https:// so the browser takes it as a site, not a path —
     * and so does one with a port ("localhost:3000", "192.168.1.10:8080"), which Uri reads as a
     * scheme. Anything else that looks like a scheme ("javascript:", "file:", "intent:") is refused:
     * it can only fail when opened, or open something nobody meant to save.
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        val scheme = SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase()
        return when {
            scheme == null -> "https://$trimmed"
            // Before the port check: "tel:123" is a phone number, not host "tel" on port 123.
            scheme in SCHEMES -> Uri.parse(trimmed).normalizeScheme().toString()
            HOST_WITH_PORT.containsMatchIn(trimmed) -> "https://$trimmed"
            else -> null
        }
    }

    /** The host to show for [url], or the url itself when it has none. */
    fun host(url: String): String = Uri.parse(url).host ?: url

    /**
     * Hands [url] to whatever the system picks for it — the default browser for an ordinary page,
     * or the app that claims the link. Toasts and returns false when nothing can open it.
     */
    fun open(context: Context, url: String): Boolean {
        // Checked here too, not only when saved: a backup file can carry any address.
        val address = normalize(url)
        val opened = address != null && runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!opened) Toast.makeText(context, R.string.gesture_url_open_failed, Toast.LENGTH_SHORT).show()
        return opened
    }

    /** What a saved address may open: the web, and the links a browser hands to the mail, phone
     *  and maps apps. */
    private val SCHEMES = setOf("http", "https", "mailto", "tel", "geo")

    private val SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*):""")
    private val HOST_WITH_PORT = Regex("""^[^/:?#]+:\d+(?:[/?#]|$)""")
}
