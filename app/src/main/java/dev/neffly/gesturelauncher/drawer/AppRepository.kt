package dev.neffly.gesturelauncher.drawer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.AppTagStore
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loads and caches the list of launchable apps via LauncherApps (the API built for launchers —
 * it also surfaces work-profile apps, which plain PackageManager queries never see). No
 * gesture-code dependencies — this feeds the crash-resistant drawer escape hatch.
 */
object AppRepository {

    @Volatile
    private var cache: List<AppInfo>? = null

    /**
     * True while [cache] holds a disk snapshot rather than the result of a real scan.
     *
     * Load-bearing: [load]'s fast path must treat a primed cache as "still needs scanning",
     * otherwise the snapshot becomes permanently authoritative and the app never re-reads
     * LauncherApps again for the life of the process.
     */
    @Volatile
    private var cacheFromDisk = false

    // Serializes the actual PackageManager scan so a background preload (see App.onCreate) and an
    // activity's own loadApps() call racing against each other don't both pay the full scan cost —
    // the second caller just waits for the first's result instead of duplicating the work.
    private val loadLock = Any()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    // A-Z first, then the '#' bucket as one block at the end, matching the alphabet index's glyph
    // order. Sorting by label alone splits '#' in two — digits sort before 'A' and CJK after 'Z' —
    // which rendered as two separate sections both headed '#'.
    private val DRAWER_ORDER = compareBy<AppInfo>(
        { if (it.indexLetter() == '#') 1 else 0 },
        { it.label.lowercase(Locale.getDefault()) }
    )

    /** Notified (on whichever thread calls [invalidate]) after the cache is dropped. */
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    /** Drops the cached app list (and stale icons), e.g. after a LauncherApps package callback. */
    fun invalidate() {
        cache = null
        cacheFromDisk = false
        IconCache.clear()
        listeners.forEach { it() }
    }

    /**
     * Fills [cache] from the last scan's on-disk snapshot when the process has just been recreated.
     * Idempotent and a no-op once anything is cached.
     *
     * Deliberately does NOT notify listeners: [cachedOrPrime] can reach this from the main thread
     * from inside a listener's own callback (AppDrawerActivity.loadApps), and notifying there would
     * re-enter that callback synchronously mid-call.
     */
    fun primeFromDisk(context: Context) {
        if (cache != null) return
        val apps = AppListSnapshot.read(context)
        if (apps.isEmpty()) return
        synchronized(loadLock) {
            if (cache != null) return
            cache = apps
            cacheFromDisk = true
        }
    }

    /**
     * The cached list, priming from disk first if this process hasn't scanned yet. Safe on the main
     * thread: the disk read is a small JSON file, and it only ever happens where the alternative is
     * rendering an empty list.
     */
    fun cachedOrPrime(context: Context): List<AppInfo> {
        cache?.let { return it }
        primeFromDisk(context)
        return cache ?: emptyList()
    }

    /** Whether [load] would do real work — false means the cache is a scan result and current. */
    fun needsScan(): Boolean = cache == null || cacheFromDisk

    /** Loads apps (blocking). Call off the main thread; result is cached for reuse. Icons are
     *  deliberately NOT loaded here — see [IconCache] — which keeps this scan cheap. */
    fun load(context: Context, forceReload: Boolean = false): List<AppInfo> {
        if (!forceReload && !cacheFromDisk) cache?.let { return it }
        synchronized(loadLock) {
            if (!forceReload && !cacheFromDisk) cache?.let { return it }
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            val self = context.packageName
            val apps = userManager.userProfiles
                .flatMap { user -> runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList()) }
                .mapNotNull { activity ->
                    // Hide ourselves from the drawer — this launcher isn't a normal app to open.
                    if (activity.componentName.packageName == self) return@mapNotNull null
                    AppInfo(
                        label = activity.label.toString(),
                        packageName = activity.componentName.packageName,
                        componentName = activity.componentName,
                        user = activity.user,
                        tag = AppTagStore.tag(context, activity.componentName)
                    )
                }.sortedWith(DRAWER_ORDER)
            cache = apps
            cacheFromDisk = false
            // Cheap: a no-op unless the list actually changed since the last scan.
            AppListSnapshot.write(context, apps)
            return apps
        }
    }

    /** Launches a drawer entry, honoring its profile: work-profile apps must go through
     *  LauncherApps (a plain Intent can only target the current user). */
    fun launch(context: Context, app: AppInfo) {
        if (app.user == Process.myUserHandle()) {
            launch(context, app.componentName)
        } else {
            runCatching {
                val launcherApps =
                    context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                launcherApps.startMainActivity(app.componentName, app.user, null, null)
            }.onFailure { Log.w("AppRepository", "work-profile launch failed for ${app.componentName}", it) }
        }
    }

    fun cached(): List<AppInfo> = cache ?: emptyList()

    /**
     * The intent that opens [componentName], or null when the package offers none.
     *
     * Some apps (e.g. Duolingo) toggle which launcher-activity alias is enabled over time for
     * seasonal/promotional icon swapping. A [componentName] captured at gesture-training time can
     * go stale once the app disables that alias in favor of another, so a direct launch by the
     * exact stored component can throw ActivityNotFoundException indefinitely afterward. Hence the
     * resolve check: when the stored component no longer resolves, this falls back to whatever
     * launcher activity is currently enabled for the package.
     *
     * Separate from [launch] because a floating-window launch needs this same intent with its own
     * ActivityOptions attached, and needs to resolve the target activity before starting it — see
     * [dev.neffly.gesturelauncher.launch.FloatingWindow].
     */
    fun launchIntent(context: Context, componentName: ComponentName): Intent? {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        val direct = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(componentName)
            .addFlags(flags)
        if (direct.resolveActivity(context.packageManager) != null) return direct
        return context.packageManager.getLaunchIntentForPackage(componentName.packageName)
            ?.addFlags(flags)
    }

    /**
     * Opens [componentName], or reports that it is gone.
     *
     * The other way a component goes stale is the drawer rendering a disk snapshot (see
     * [AppListSnapshot]) that was written before an app was uninstalled while this process was
     * dead. When nothing can be launched, the list is invalidated so it self-heals immediately
     * rather than waiting for a LauncherApps callback that already fired while nothing was
     * listening.
     */
    fun launch(context: Context, componentName: ComponentName) {
        val intent = launchIntent(context, componentName)
        if (intent != null && runCatching { context.startActivity(intent) }.isSuccess) return
        Log.w("AppRepository", "launch failed for $componentName (no valid launcher intent)")
        Toast.makeText(context, R.string.app_not_available, Toast.LENGTH_SHORT).show()
        invalidate()
    }
}
