package dev.neffly.gesturelauncher

import android.app.Application
import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import dev.neffly.gesturelauncher.crash.CrashHandler
import dev.neffly.gesturelauncher.drawer.AppRepository
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
