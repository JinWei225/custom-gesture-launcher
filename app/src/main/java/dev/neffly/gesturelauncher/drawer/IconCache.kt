package dev.neffly.gesturelauncher.drawer

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.LruCache
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Small LRU of app icons, loaded on demand as rows bind (see AppListAdapter) instead of eagerly
 * for every installed app during the scan. Bounded so a big app list can't pin hundreds of icon
 * bitmaps in memory for the process's whole lifetime. Work-profile icons get the OS badge.
 */
object IconCache {

    private const val MAX_ENTRIES = 64

    private val cache = LruCache<String, Drawable>(MAX_ENTRIES)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Notified whenever [clear] runs, so any adapter currently showing icons (see
     *  AppListAdapter) can force its bound rows to rebind — DiffUtil alone won't do this, since an
     *  app's label/tag are typically unchanged when only its icon did (a theme swap, or an update
     *  that changes the icon without changing the label). */
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    /** Cached icon, or null if it hasn't been loaded yet. Cheap; safe on the main thread. */
    fun cached(app: AppInfo): Drawable? = cache.get(app.key)

    /** Loads (and caches) the icon. Call off the main thread. Null if the app vanished.
     *
     *  Personal-profile icons go through PackageManager, NOT LauncherApps: OEM theme engines
     *  (e.g. Xiaomi/HyperOS icon packs) hook the PackageManager loadIcon path, while
     *  LauncherActivityInfo.getIcon(density) reads the app's raw icon resource and bypasses the
     *  theme entirely. LauncherApps is only used for work-profile apps, where PackageManager
     *  can't see across profiles. */
    fun load(context: Context, app: AppInfo): Drawable? {
        cache.get(app.key)?.let { return it }
        val pm = context.packageManager
        val icon = if (app.user == Process.myUserHandle()) {
            runCatching { pm.getActivityIcon(app.componentName) }.getOrNull()
                ?: runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
        } else {
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val intent = Intent(Intent.ACTION_MAIN).setComponent(app.componentName)
            runCatching {
                launcherApps.resolveActivity(intent, app.user)
                    ?.getIcon(context.resources.displayMetrics.densityDpi)
            }.getOrNull()
        } ?: return null
        val badged = pm.getUserBadgedIcon(icon, app.user)
        cache.put(app.key, badged)
        return badged
    }

    /** Drops everything — called when the app list is invalidated (installs/updates can change
     *  icons) and whenever the system theme/icon pack changes (see App's configuration-change
     *  receiver). */
    fun clear() {
        cache.evictAll()
        listeners.forEach { it() }
    }
}
