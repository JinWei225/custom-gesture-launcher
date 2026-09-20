package dev.neffly.gesturelauncher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.UserHandle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import dev.neffly.gesturelauncher.crash.CrashHandler
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.drawer.AppRepository
import dev.neffly.gesturelauncher.drawer.IconCache
import dev.neffly.gesturelauncher.launch.UnrequestedHomeLaunch
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

        // The home screen has to be able to tell a home launch the user asked for from one the
        // system fires after a floating window appears; this is the only thing that separates
        // them. Installed here because the process outlives every activity, and the windows in
        // question change while the home screen is stopped. A no-op on any device that isn't
        // HyperOS, which this call is also what determines.
        UnrequestedHomeLaunch.install()

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
        // at all, hence context-registered here. The broadcast also fires on rotation, a fold and
        // a keyboard docking, none of which can change an icon, and clearing the cache there made
        // every visible row re-fetch from PackageManager for nothing. The diff is therefore
        // checked, and the clear skipped only when every changed field is one from that list —
        // anything else, an OEM's own configuration bits included, still clears, so a theme
        // engine this code doesn't know about fails towards fresh icons rather than stale ones.
        var lastConfig = Configuration(resources.configuration)
        val configReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val current = Configuration(context.resources.configuration)
                val diff = lastConfig.diff(current)
                lastConfig = current
                if ((diff and ICON_NEUTRAL_CHANGES.inv()) != 0) IconCache.clear()
            }
        }
        ContextCompat.registerReceiver(
            this,
            configReceiver,
            IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Seed the drawer's app list from the last scan's disk snapshot, so the first drawer or
        // search open after a process kill paints a full list on its first frame. Only the
        // snapshot: the LauncherApps scan that reconciles it, and the icon fetches behind it, used
        // to run here too, on every process start — work a home screen that only ever launches a
        // gesture never needed. Both now happen where they are first wanted, from the drawer's and
        // quick search's own loadApps(), off the main thread and behind the snapshot they refine.
        appScope.launch { AppRepository.primeFromDisk(applicationContext) }
    }

    private companion object {
        /** Configuration fields that can change without any app icon changing with them: the
         *  geometry of the screen and window, and the input hardware. */
        const val ICON_NEUTRAL_CHANGES = ActivityInfo.CONFIG_ORIENTATION or
            ActivityInfo.CONFIG_SCREEN_SIZE or
            ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or
            // The window-bounds bit rotation and multi-window set. Hidden from the SDK, so it is
            // the literal value of ActivityInfo.CONFIG_WINDOW_CONFIGURATION.
            0x20000000 or
            ActivityInfo.CONFIG_KEYBOARD or
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
            ActivityInfo.CONFIG_NAVIGATION or
            ActivityInfo.CONFIG_TOUCHSCREEN or
            ActivityInfo.CONFIG_FONT_SCALE
    }
}
