package dev.neffly.gesturelauncher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.UserHandle
import androidx.core.content.ContextCompat
import dev.neffly.gesturelauncher.crash.CrashHandler
import dev.neffly.gesturelauncher.drawer.AppRepository
import dev.neffly.gesturelauncher.drawer.IconCache
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

        // OEM theme engines (e.g. Xiaomi/HyperOS "Themes") re-skin app icons in place — no package
        // is installed/updated/removed, so the LauncherApps callback above never fires for them.
        // What they do trigger is a resource/asset change, which the OS reports via a
        // CONFIGURATION_CHANGED broadcast; that's not something a manifest-declared receiver can
        // ever see (the action doesn't support manifest registration at all), only a
        // context-registered one, so it's done here rather than in AndroidManifest. Dropping the
        // icon cache on every such broadcast is deliberately broad — it also fires for unrelated
        // config changes like rotation — but the drop itself is just an in-memory evictAll, so the
        // worst case is a handful of already-visible icons re-fetching from PackageManager (cheap;
        // see IconCache) rather than any full app-list rescan.
        val configReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = IconCache.clear()
        }
        ContextCompat.registerReceiver(
            this,
            configReceiver,
            IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Warm the drawer's app-list cache as early as possible. MainActivity (the HOME app) is a
        // background/cached process from Android's point of view, so after the user spends a
        // while in other apps this process is a common target for the OS to kill and later
        // recreate from scratch — which drops AppRepository's in-memory cache too. Priming it
        // here runs concurrently with the home screen appearing, so the first drawer open
        // doesn't pay the scan synchronously. (Icons are lazy — see IconCache — so this is a
        // label-only scan and much cheaper than it used to be.)
        appScope.launch { AppRepository.load(applicationContext, forceReload = false) }
    }
}
