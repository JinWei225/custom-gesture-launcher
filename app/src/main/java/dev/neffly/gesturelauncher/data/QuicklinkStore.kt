package dev.neffly.gesturelauncher.data

import android.content.Context
import kotlinx.serialization.Serializable

/**
 * A saved web address that search finds by [name] or [alias] and opens in the default browser —
 * the launcher's take on Raycast's quicklinks. [alias] is a short, exact shortcut ("gh"), matched
 * the way an app's alias is (see SearchScoring).
 */
@Serializable
data class Quicklink(
    val id: String,
    val name: String,
    val url: String,
    val alias: String? = null
)

/** Every [Quicklink], persisted as `quicklinks.json` (see [JsonListFile]), in the user's order. */
object QuicklinkStore {

    private val file = JsonListFile("quicklinks.json", Quicklink.serializer())

    fun all(context: Context): List<Quicklink> = file.read(context)

    /** Adds [link], or replaces the one with its id in place. */
    fun save(context: Context, link: Quicklink) = file.update(context) { list ->
        if (list.any { it.id == link.id }) list.map { if (it.id == link.id) link else it }
        else list + link
    }

    fun remove(context: Context, id: String) = file.update(context) { list -> list.filterNot { it.id == id } }

    /** Puts [link] back at [index] — the undo of [remove]. */
    fun restore(context: Context, link: Quicklink, index: Int) = file.update(context) { list ->
        list.toMutableList().apply { add(index.coerceIn(0, size), link) }
    }

    /** Backup restore: "Replace all" swaps the list, "Merge" adds or updates by id. */
    fun import(context: Context, links: List<Quicklink>, replace: Boolean) {
        if (replace) file.write(context, links) else links.forEach { save(context, it) }
    }
}
