package dev.neffly.gesturelauncher.drawer

import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.core.util.AtomicFile
import dev.neffly.gesturelauncher.data.AppTagStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Last known app list, persisted so the drawer can paint a full list on its first frame after the
 * process was killed.
 *
 * This exists because [AppRepository]'s cache is in-memory only, and a launcher is a background
 * process that OEM power management (notably Xiaomi/HyperOS) hibernates aggressively. Without a
 * snapshot, every cold start re-runs the full LauncherApps scan before a single row can be shown,
 * which is the 1-2s of empty drawer this fixes.
 *
 * Same persistence shape as [dev.neffly.gesturelauncher.data.GestureStore]: AtomicFile
 * (write-to-temp + rename) so a crash mid-write can't leave a truncated file. Unlike GestureStore
 * there's no `.bak` fallback for a corrupt file — this is pure cache, and the authoritative copy is
 * always one LauncherApps scan away.
 */
object AppListSnapshot {

    private const val TAG = "AppListSnapshot"
    private const val FILE_NAME = "app_list.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Content hash of what's currently on disk, so an unchanged scan writes nothing.
     *
     * This is the whole battery story for this class: the reconciling scan runs on every cold
     * start, but the app list almost never actually differs, so the common path costs zero writes.
     * Seeded by [read] as well as [write] — otherwise the first scan after a cold start would
     * always rewrite an identical file.
     */
    @Volatile
    private var signature: Int = 0

    @Serializable
    private data class Entry(
        val label: String,
        /** [ComponentName.flattenToString]. */
        val component: String,
        /** [UserManager.getSerialNumberForUser] — see the note in [write]. */
        val userSerial: Long
    )

    @Serializable
    private data class Snapshot(val version: Int = 1, val entries: List<Entry> = emptyList())

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** The last persisted list, or empty if there's no (readable) snapshot. Cheap — a small JSON
     *  file — but still IO, so it's only called where the alternative is showing nothing. */
    fun read(context: Context): List<AppInfo> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        val snapshot = runCatching {
            json.decodeFromString<Snapshot>(AtomicFile(file).readFully().decodeToString())
        }.getOrElse { e ->
            Log.w(TAG, "unreadable $FILE_NAME; ignoring it", e)
            return emptyList()
        }
        signature = snapshot.entries.hashCode()

        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        return snapshot.entries.mapNotNull { entry ->
            val component = ComponentName.unflattenFromString(entry.component)
                ?: return@mapNotNull null
            // Null for a profile that no longer exists — e.g. a work profile removed while this
            // process was dead. Dropping the entry is right: it can't be launched either way.
            val user = userManager.getUserForSerialNumber(entry.userSerial)
                ?: return@mapNotNull null
            AppInfo(
                label = entry.label,
                packageName = component.packageName,
                componentName = component,
                user = user,
                // Read live rather than persisted, so editing an alias never has to rewrite the
                // snapshot — AppTagStore is a SharedPreferences map lookup once the file is loaded.
                tag = AppTagStore.tag(context, component)
            )
        }
    }

    /** Persists [apps], or does nothing if the content is byte-identical to what's already there. */
    fun write(context: Context, apps: List<AppInfo>) {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        // The profile serial, not the UserHandle: a UserHandle wraps an int that carries no
        // meaning across reboots, whereas the serial is the OS's stable, never-reused profile id.
        val snapshot = Snapshot(
            entries = apps.map {
                Entry(it.label, it.componentName.flattenToString(), userManager.getSerialNumberForUser(it.user))
            }
        )
        val newSignature = snapshot.entries.hashCode()
        if (newSignature == signature) return

        val atomic = AtomicFile(file(context))
        val out = atomic.startWrite()
        try {
            out.write(json.encodeToString(snapshot).encodeToByteArray())
            atomic.finishWrite(out)
            signature = newSignature
        } catch (e: Exception) {
            atomic.failWrite(out)
            Log.e(TAG, "failed to save $FILE_NAME", e)
        }
    }
}
