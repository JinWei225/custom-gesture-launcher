package dev.neffly.gesturelauncher.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import dev.neffly.gesturelauncher.R

/** Opening a typed web address — shared by quicklinks and the "Open a URL" gesture action. */
object Links {

    /** A bare address ("github.com") gets https:// so the browser takes it as a site, not a path. */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        return if (Uri.parse(trimmed).scheme == null) "https://$trimmed" else trimmed
    }

    /** The host to show for [url], or the url itself when it has none. */
    fun host(url: String): String = Uri.parse(url).host ?: url

    /**
     * Hands [url] to whatever the system picks for it — the default browser for an ordinary page,
     * or the app that claims the link. Toasts and returns false when nothing can open it.
     */
    fun open(context: Context, url: String): Boolean {
        val opened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!opened) Toast.makeText(context, R.string.gesture_url_open_failed, Toast.LENGTH_SHORT).show()
        return opened
    }
}
