package dev.neffly.gesturelauncher.data

import android.content.Context

/**
 * Optional short searchable label per app (e.g. "tng" for "TNG eWallet") — a shortcut on top of
 * fuzzy search, not a rename: the app's real name is never touched (see AppRepository/AppInfo.tag).
 *
 * Keyed by package name, not launcher component: apps that swap their icon (Apple Music, Duolingo)
 * do it by switching to a different launcher activity, which would orphan a component-keyed tag.
 */
object AppTagStore {

    private const val FILE = "app_tags_by_package"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun tag(context: Context, packageName: String): String? =
        prefs(context).getString(packageName, null)?.takeIf { it.isNotBlank() }

    fun setTag(context: Context, packageName: String, tag: String) {
        prefs(context).edit().putString(packageName, tag).apply()
    }

    fun clearTag(context: Context, packageName: String) {
        prefs(context).edit().remove(packageName).apply()
    }

    /** All tags as `packageName -> tag`, for backup export. */
    fun allTags(context: Context): Map<String, String> =
        prefs(context).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    /** Wipes and replaces every tag — backup restore's "Replace all" mode. */
    fun replaceAll(context: Context, tags: Map<String, String>) {
        val editor = prefs(context).edit().clear()
        tags.forEach { (k, v) -> editor.putString(k, v) }
        editor.apply()
    }

    /** Overlays [tags] onto the existing set without clearing — backup restore's "Merge" mode. */
    fun mergeAll(context: Context, tags: Map<String, String>) {
        val editor = prefs(context).edit()
        tags.forEach { (k, v) -> editor.putString(k, v) }
        editor.apply()
    }
}
