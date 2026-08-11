package dev.neffly.gesturelauncher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.UserHandle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import dev.neffly.gesturelauncher.crash.CrashHandler
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.drawer.AppRepository
import dev.neffly.gesturelauncher.drawer.IconCache
import dev.neffly.gesturelauncher.ui.FontEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Registers the crash handler as early as possible so even startup crashes are counted. */
class App : Application() {

    /** Process-lifetime scope for work that isn't tied to any activity (cache warm-ups). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext, previous))

        // Apply the saved Light/Dark/Follow-system choice before anything can inflate. Must happen
        // here, not in an activity: Application.onCreate finishes before the first Activity.onCreate,
        // so no window is ever built against the wrong mode (no light-to-dark flash on first frame).
        // Deliberately after the crash handler, preserving "crash handler first, always". The
        // synchronous SharedPreferences read is the same file MainActivity reads on its safe-mode
        // path, so it's paged in either way.
        AppCompatDelegate.setDefaultNightMode(Prefs.themeMode(this))

        // Same reasoning, same place: the user's font has to be resolved before the first window is
        // built, or the home screen renders once in the system font and visibly re-renders. Reads
        // the prefs file the line above just paged in, and only touches the filesystem when a
        // custom font is actually set.
        FontEngine.init(this)

        // Keep the drawer's app list live. LauncherApps callbacks are the launcher-grade
        // replacement for PACKAGE_ADDED/REMOVED broadcasts (which stopped reaching manifest
        // receivers in API 26) and also cover work-profile changes.
        val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) =
                AppRepository.invalidate()
            override fun onPackageAdded(packageName: String, user: UserHandle) =
                AppRepository.invalidate()
            override fun onPackageChanged(packageName: String, user: UserHandle) =
                AppRepository.invalidate()
            override fun onPackagesAvailable(
                packageNames: Array<out String>, user: UserHandle, replacing: Boolean
            ) = AppRepository.invalidate()
            override fun onPackagesUnavailable(
                packageNames: Array<out String>, user: UserHandle, replacing: Boolean
            ) = AppRepository.invalidate()
        })

        // OEM theme engines (e.g. Xiaomi/HyperOS "Themes") re-skin icons in place — no package is
        // installed/updated/removed, so the LauncherApps callback above never fires. What they do
        // trigger is a CONFIGURATION_CHANGED broadcast, which doesn't support manifest registration
        // at all, hence context-registered here. Clearing the icon cache on every such broadcast is
        // deliberately broad (it also fires on rotation), but it's just an in-memory evictAll — worst
        // case a handful of visible icons re-fetch from PackageManager (cheap; see IconCache).
        val configReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = IconCache.clear()
        }
        ContextCompat.registerReceiver(
            this,
            configReceiver,
            IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Warm the drawer's app-list cache early. MainActivity (the HOME app) is a background/cached
        // process, so the OS commonly kills and recreates it after time away, dropping AppRepository's
        // in-memory cache. Priming it here overlaps with the home screen appearing, so the first
        // drawer open skips the synchronous scan. (Icons are lazy — see IconCache — so this is
        // label-only and cheap.)
        //
        // Sequential, and the disk snapshot goes first: it lands in milliseconds and makes the
        // drawer instantly renderable, while the LauncherApps scan behind it takes long enough on a
        // phone with a lot of apps that the user can beat it to the drawer button. The scan then
        // reconciles. AppDrawerActivity primes for itself too, for the case where it's restored
        // from recents before this coroutine has run at all.
        appScope.launch {
            AppRepository.primeFromDisk(applicationContext)
            AppRepository.load(applicationContext, forceReload = false)
            // Icons are the other half of a cold start: IconCache is in-memory too, so after a kill
            // every visible row would fetch from PackageManager as it binds. Warming just the first
            // screenful (bounded well under IconCache.MAX_ENTRIES) is work the drawer would do
            // anyway, moved a few hundred ms earlier.
            AppRepository.cached().take(ICON_PREWARM_COUNT).forEach {
                IconCache.load(applicationContext, it)
            }
        }
    }

    private companion object {
        /** Roughly a screenful of drawer rows. */
        const val ICON_PREWARM_COUNT = 24
    }
}
