package dev.neffly.gesturelauncher.drawer

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Process
import android.widget.Toast

/**
 * App shortcuts (e.g. "New message", "Take a selfie") for the long-press drawer menu. Only works
 * while this app is the default HOME app, which is automatically granted shortcut-host access.
 */
object AppShortcutHelper {

    private const val MAX_SHORTCUTS = 4

    fun queryShortcuts(context: Context, packageName: String): List<ShortcutInfo> {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val query = LauncherApps.ShortcutQuery().apply {
            setPackage(packageName)
            setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
        }
        return runCatching { launcherApps.getShortcuts(query, Process.myUserHandle()) }
            .getOrNull().orEmpty().take(MAX_SHORTCUTS)
    }

    /** The shortcut's own icon (e.g. a camera-with-lens-flare for "Take a selfie"), or null if the
     *  OS has none to give — callers should fall back to a generic icon in that case. */
    fun icon(context: Context, shortcut: ShortcutInfo): Drawable? {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        val density = context.resources.displayMetrics.densityDpi
        return runCatching { launcherApps.getShortcutIconDrawable(shortcut, density) }.getOrNull()
    }

    fun launch(activity: Activity, shortcut: ShortcutInfo) {
        val launcherApps = activity.getSystemService(LauncherApps::class.java) ?: return
        runCatching { launcherApps.startShortcut(shortcut, null, null) }
            .onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_SHORT).show() }
    }
}
