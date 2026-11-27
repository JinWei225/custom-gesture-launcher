package dev.neffly.gesturelauncher.data

import android.content.ComponentName
import android.content.Context
import android.os.Process
import dev.neffly.gesturelauncher.drawer.AppInfo
import kotlinx.serialization.builtins.serializer

/**
 * The apps on the home screen's pie, in ring order (the first sits at the top, the rest follow
 * clockwise), as flattened component names — personal-profile apps only, like gesture mappings.
 * Persisted as `pie_apps.json` (see [JsonListFile]).
 */
object PieStore {

    /** Beyond this the ring's icons get too small, or the ring too wide, to aim at reliably. */
    const val MAX_APPS = 12

    private val file = JsonListFile("pie_apps.json", String.serializer())

    fun all(context: Context): List<String> = file.read(context)

    /** Every writer goes through here, so the pie never holds a repeat or more than [MAX_APPS]. */
    fun replaceAll(context: Context, components: List<String>) =
        file.write(context, components.distinct().take(MAX_APPS))

    /**
     * The installed apps behind [components], in order, each at most once. A component an app has
     * since swapped for another launcher alias (seasonal icons do this) falls back to that
     * package's entry — only when the package has just the one, since otherwise there is no
     * telling which was meant. One whose app is gone is left out.
     */
    fun resolve(components: List<String>, apps: List<AppInfo>): List<AppInfo> {
        val personal = apps.filter { it.user == Process.myUserHandle() }
        val byComponent = personal.associateBy { it.componentName.flattenToString() }
        val byPackage = personal.groupBy { it.packageName }
        return components.mapNotNull { flat ->
            byComponent[flat] ?: ComponentName.unflattenFromString(flat)?.let { comp ->
                byPackage[comp.packageName]?.singleOrNull()
            }
        }.distinctBy { it.key }
    }
}
