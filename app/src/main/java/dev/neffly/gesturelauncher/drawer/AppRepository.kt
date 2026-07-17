package dev.neffly.gesturelauncher.drawer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager
import android.util.Log
import dev.neffly.gesturelauncher.data.AppTagStore
import java.text.Normalizer
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

    // Serializes the actual PackageManager scan so a background preload (see App.onCreate) and an
    // activity's own loadApps() call racing against each other don't both pay the full scan cost —
    // the second caller just waits for the first's result instead of duplicating the work.
    private val loadLock = Any()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Notified (on whichever thread calls [invalidate]) after the cache is dropped. */
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    /** Drops the cached app list (and stale icons), e.g. after a LauncherApps package callback. */
    fun invalidate() {
        cache = null
        IconCache.clear()
        listeners.forEach { it() }
    }

    /** Loads apps (blocking). Call off the main thread; result is cached for reuse. Icons are
     *  deliberately NOT loaded here — see [IconCache] — which keeps this scan cheap. */
    fun load(context: Context, forceReload: Boolean = false): List<AppInfo> {
        if (!forceReload) cache?.let { return it }
        synchronized(loadLock) {
            if (!forceReload) cache?.let { return it }
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
                }.sortedBy { it.label.lowercase(Locale.getDefault()) }
            cache = apps
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
     * Case- and diacritic-insensitive fuzzy filter over labels: matches whenever [query]'s
     * characters appear in order in the label (not necessarily contiguous — a superset of a
     * plain substring match), ranked so contiguous/word-boundary matches sort first.
     *
     * An app whose user-set [AppInfo.tag] exactly matches [query] is pinned above every fuzzy
     * match regardless of its own fuzzy score — including apps whose real label wouldn't fuzzy
     * -match the query at all (e.g. a Chinese-labeled app tagged with an English shortcut), since
     * that's the whole point of a tag: an independent, guaranteed-findable shortcut.
     */
    fun filter(apps: List<AppInfo>, query: String): List<AppInfo> {
        val q = normalize(query)
        if (q.isEmpty()) return apps
        return apps.mapNotNull { app ->
            val tagExact = app.tag?.let { normalize(it) == q } == true
            if (tagExact) app to TAG_EXACT_SCORE
            else fuzzyScore(normalize(app.label), q)?.let { app to it }
        }.sortedWith(compareByDescending<Pair<AppInfo, Int>> { it.second }
            .thenBy { normalize(it.first.label) })
            .map { it.first }
    }

    /**
     * Some apps (e.g. Duolingo) toggle which launcher-activity alias is enabled over time for
     * seasonal/promotional icon swapping. A [componentName] captured at gesture-training time can
     * go stale once the app disables that alias in favor of another, so a direct launch by the
     * exact stored component can throw ActivityNotFoundException indefinitely afterward. Falls
     * back to whatever launcher activity is currently enabled for the package in that case.
     */
    fun launch(context: Context, componentName: ComponentName) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        val direct = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(componentName)
            .addFlags(flags)
        val launched = runCatching { context.startActivity(direct) }.isSuccess
        if (!launched) {
            val fallback = context.packageManager.getLaunchIntentForPackage(componentName.packageName)
                ?.addFlags(flags)
            val fallbackLaunched = fallback != null &&
                runCatching { context.startActivity(fallback) }.isSuccess
            if (!fallbackLaunched) {
                Log.w("AppRepository", "launch failed for $componentName (no valid launcher intent)")
            }
        }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .trim()

    /** Sort tier for an exact tag match — comfortably above any possible fuzzy score. */
    private const val TAG_EXACT_SCORE = Int.MAX_VALUE

    private const val MATCH_SCORE = 16
    private const val CONSECUTIVE_BONUS = 12
    private const val WORD_BOUNDARY_BONUS = 10
    private const val GAP_PENALTY = 1
    private val SEPARATORS = charArrayOf(' ', '-', '_', '.')
    private const val NO_MATCH = Int.MIN_VALUE / 2

    /**
     * Best-alignment score for [query] as a subsequence of [text] (both already normalized), or
     * null if [query]'s characters don't all appear in [text] in order. Rewards contiguous runs
     * and word-boundary starts so e.g. "gm" ranks "Google Maps" above "Backgammon".
     */
    private fun fuzzyScore(text: String, query: String): Int? {
        if (query.isEmpty()) return 0
        // dp[p] = best score to match the first i query chars, with the i-th match landing at
        // text position p (0-indexed); rebuilt one row per query character.
        var dp = IntArray(text.length) { p ->
            if (text[p] == query[0]) MATCH_SCORE + boundaryBonus(text, p) - GAP_PENALTY * p
            else NO_MATCH
        }
        for (i in 1 until query.length) {
            val next = IntArray(text.length) { NO_MATCH }
            for (p in i until text.length) {
                if (text[p] != query[i]) continue
                var best = NO_MATCH
                for (prevP in i - 1 until p) {
                    if (dp[prevP] <= NO_MATCH) continue
                    val gap = p - prevP - 1
                    val bonus = if (gap == 0) CONSECUTIVE_BONUS else -GAP_PENALTY * gap
                    best = maxOf(best, dp[prevP] + bonus)
                }
                if (best > NO_MATCH) next[p] = best + MATCH_SCORE + boundaryBonus(text, p)
            }
            dp = next
        }
        val best = dp.maxOrNull() ?: NO_MATCH
        return if (best <= NO_MATCH) null else best
    }

    private fun boundaryBonus(text: String, p: Int): Int {
        if (p == 0 || text[p - 1] in SEPARATORS) return WORD_BOUNDARY_BONUS
        return 0
    }
}
