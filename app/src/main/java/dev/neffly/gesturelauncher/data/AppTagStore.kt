package dev.neffly.gesturelauncher.data

import android.content.ComponentName
import android.content.Context

/**
 * Optional short searchable label per app (e.g. "tng" for "TNG eWallet") — a shortcut on top of
 * fuzzy search, not a rename: the app's real name is never touched (see AppRepository/AppInfo.tag).
 */
object AppTagStore {

    private const val FILE = "app_tags"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun tag(context: Context, component: ComponentName): String? =
        prefs(context).getString(component.flattenToString(), null)?.takeIf { it.isNotBlank() }

    fun setTag(context: Context, component: ComponentName, tag: String) {
        prefs(context).edit().putString(component.flattenToString(), tag).apply()
    }

    fun clearTag(context: Context, component: ComponentName) {
        prefs(context).edit().remove(component.flattenToString()).apply()
    }

    /** All tags as `componentName -> tag`, for backup export. */
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
