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

    fun replaceAll(context: Context, components: List<String>) = file.write(context, components)

    /**
     * The installed apps behind [components], in order. A component an app has since swapped for
     * another launcher alias (seasonal icons do this) falls back to that package's entry; one whose
     * app is gone is left out.
     */
    fun resolve(components: List<String>, apps: List<AppInfo>): List<AppInfo> {
        val personal = apps.filter { it.user == Process.myUserHandle() }
        val byComponent = personal.associateBy { it.componentName.flattenToString() }
        return components.mapNotNull { flat ->
            byComponent[flat] ?: ComponentName.unflattenFromString(flat)?.let { comp ->
                personal.firstOrNull { it.packageName == comp.packageName }
            }
        }
    }
}
