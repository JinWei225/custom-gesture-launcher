package dev.neffly.gesturelauncher.launch

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.drawer.AppInfo
import dev.neffly.gesturelauncher.drawer.AppRepository
import dev.neffly.gesturelauncher.search.FileSearcher
import dev.neffly.gesturelauncher.search.SearchResult
import dev.neffly.gesturelauncher.search.WebSearch

/**
 * Opens a search result in a floating (freeform) window instead of full screen.
 *
 * Reached by swiping a result row to the right; see [dev.neffly.gesturelauncher.ui.SwipeToFloat].
 * Works for every kind of openable row — an app, a file (whichever viewer handles it) and the web
 * row (the default browser) — because all three end up as an Intent, and the float is nothing but
 * ActivityOptions bolted onto that intent.
 *
 * [canFloat] is the single gate on whether the gesture exists at all, so nothing below has to
 * handle "this device can't do this" a second time.
 */
object FloatingWindow {

    private const val TAG = "FloatingWindow"

    /**
     * Height of the window as a multiple of its width on a handset, matching the shape the
     * device's own small-window gesture produces there — see [options] for how this was measured.
     */
    private const val PHONE_ASPECT = 1.6f

    /** On a large screen the window is sized from the display's short edge instead: this much of
     *  it tall, and [TABLET_ASPECT] times narrower than that. See [options]. */
    private const val TABLET_HEIGHT_FRACTION = 0.90f
    private const val TABLET_ASPECT = 16f / 9f

    /** The conventional large-screen threshold, and the one the resource system uses for
     *  `sw600dp` — so this agrees with which fractions.xml the quick-search card picked up. */
    private const val LARGE_SCREEN_SW_DP = 600


    /** ActivityOptions' own key for the launch windowing mode, and WindowConfiguration's freeform
     *  constant. Both are hidden as symbols but stable as values — see [options]. */
    private const val KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode"
    private const val WINDOWING_MODE_FREEFORM = 5

    /**
     * Whether [result] can be floated on this device.
     *
     * Two things have to hold. The device must report freeform support — false on most phones,
     * true on the desktop-mode and large-screen builds and on OEM skins that ship their own
     * small-window mode on top of the same platform feature. And the result must be something
     * with its own window: our settings hub is a screen of this very app, so floating it means
     * nothing.
     *
     * Callers gate the gesture on this rather than letting the swipe run and reporting failure
     * afterwards: a swipe that is never offered is clearer than one that is offered and then
     * apologises.
     */
    fun canFloat(context: Context, result: SearchResult): Boolean = when (result) {
        // Neither has a window of its own: the settings hub is a screen of this very app, and a
        // calculation is a number to copy. Exhaustive rather than a negated list, so a new kind of
        // result has to say which side it falls on.
        is SearchResult.Settings, is SearchResult.Calculation -> false
        is SearchResult.App, is SearchResult.File, is SearchResult.Web ->
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
    }

    /** Opens [result] floating. Only call for a [result] that [canFloat] accepts. */
    fun open(activity: Activity, result: SearchResult) {
        when (result) {
            is SearchResult.App -> openApp(activity, result.app)
            is SearchResult.File ->
                start(activity, FileSearcher.intentFor(result.hit), R.string.file_open_failed)
            is SearchResult.Web ->
                start(activity, WebSearch.intentFor(result.query, result.url), R.string.web_search_failed)
            is SearchResult.Settings, is SearchResult.Calculation -> Unit
        }
    }

    /** Work-profile apps can't be started by Intent from here — LauncherApps is the only route,
     *  and it takes the same options Bundle, so the float survives the detour. Nothing about that
     *  profile's activities is readable from here, so it goes unchecked rather than guessed at. */
    private fun openApp(activity: Activity, app: AppInfo) {
        val options = options(activity)
        if (app.user != Process.myUserHandle()) {
            runCatching {
                val launcherApps =
                    activity.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                launcherApps.startMainActivity(app.componentName, app.user, null, options)
            }.onFailure { Log.w(TAG, "work-profile float failed for ${app.componentName}", it) }
            return
        }
        val intent = AppRepository.launchIntent(activity, app.componentName)
        if (intent == null) {
            Toast.makeText(activity, R.string.app_not_available, Toast.LENGTH_SHORT).show()
            AppRepository.invalidate()
            return
        }
        warnIfUnresizeable(activity, intent, app.label)
        runCatching { activity.startActivity(intent, options) }
            .onFailure { Log.w(TAG, "float failed for ${app.componentName}", it) }
    }

    private fun start(activity: Activity, intent: Intent, @StringRes failureMessage: Int) {
        warnIfUnresizeable(activity, intent, null)
        if (runCatching { activity.startActivity(intent, options(activity)) }.isFailure) {
            Toast.makeText(activity, failureMessage, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The freeform windowing mode, plus bounds shaped like the ones the device's own small-window
     * gesture uses.
     *
     * The mode is what actually gets a window floating: measured on device, an app started with
     * bounds and nothing else comes up full screen, while the same app started with
     * `am start --windowingMode 5` lands in freeform.
     *
     * The bounds then decide what the window *is*, and both devices' answers were measured from a
     * window the system itself opened.
     *
     * On the phone, HyperOS's sidebar opens WhatsApp at Rect(64, 574 - 1264, 2494) — 1200x1920 on
     * a 1200x2670 screen, so exactly the full display width, which the config confirms as w369dp,
     * the same width in dp the app gets full screen. The app therefore lays out as if it were full
     * screen and the whole surface is scaled down to the visible box, which is why nothing reflows
     * and text keeps its proportions. Leaving the bounds unset does not reproduce that: the system
     * then picked 1032x1426 (317dp wide), a genuinely narrower layout. Neither did a fraction of
     * the screen, for the same reason.
     *
     * On the tablet the answer is different and, more importantly, it is *the same window in both
     * orientations*: a floating Brave measured 1084x1936 (394x704dp) on a 2136x3200 screen, and
     * rotating the display to landscape left those numbers untouched. Full display width would be
     * absurd there — in landscape it is the whole 1164dp screen — so the size is taken from the
     * short edge, which is the one dimension rotation doesn't change. 90% of it tall by 16:9 comes
     * out at 1922x1081, within a couple of percent of what the system picked.
     *
     * That orientation-invariance is the reason the short edge is used on the phone too, where it
     * happens to be the width anyway: it means one rule produces one window, rather than the
     * launcher handing out a tall window in portrait and a squat one in landscape.
     *
     * Only the size is worth asking for precisely. The platform treats the position as a hint and
     * moves it — the same request landed at three different tops across runs, and it cascades a
     * new window when one is already open — so this centres the window and leaves placement to
     * the system rather than chasing it with a constant the system overrules anyway.
     *
     * ActivityOptions.setLaunchWindowingMode is hidden, so the mode goes into the options Bundle
     * under the key that method writes. That is a plain Bundle entry, not a reflective call into a
     * hidden member, so the non-SDK interface restrictions don't apply to it — and on a platform
     * that doesn't read the key it is an ignored extra, leaving exactly the full-screen launch
     * that omitting it would have produced.
     */
    private fun options(activity: Activity): Bundle {
        val display = displayBounds(activity)
        val shortEdge = minOf(display.width(), display.height())
        val largeScreen =
            activity.resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_SW_DP

        val width: Int
        val height: Int
        if (largeScreen) {
            height = (shortEdge * TABLET_HEIGHT_FRACTION).toInt()
            width = (height / TABLET_ASPECT).toInt()
        } else {
            width = shortEdge
            height = (width * PHONE_ASPECT).toInt()
        }

        // Clamped so a display shorter than the window it asked for still gets a window, rather
        // than bounds the platform has to reinterpret for us.
        val boundedHeight = height.coerceAtMost(display.height())
        val left = display.left + (display.width() - width) / 2
        val top = display.top + (display.height() - boundedHeight) / 2
        return ActivityOptions.makeBasic()
            .setLaunchBounds(Rect(left, top, left + width, top + boundedHeight))
            .toBundle()
            .apply { putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_FREEFORM) }
    }

    /**
     * The whole display, not the caller's window.
     *
     * `resources.displayMetrics` is the tempting source and it is the wrong one: it reports the
     * area the *calling activity* was given, which on this tablet is 2006px tall rather than the
     * display's 2136 because the navigation bar is subtracted. Sizing from that produced a window
     * 6% smaller than the one the system's own gesture opens, and would have made the float's size
     * depend on which of our screens launched it. maximumWindowMetrics is defined as the largest
     * bounds the activity could ever occupy, which is the display area, whatever the current
     * window happens to be.
     */
    private fun displayBounds(activity: Activity): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Rect(activity.windowManager.maximumWindowMetrics.bounds)
        }
        @Suppress("DEPRECATION")
        val size = Point().also { activity.windowManager.defaultDisplay.getRealSize(it) }
        return Rect(0, 0, size.x, size.y)
    }

    /**
     * Tells the user when the thing they just swiped will come up full screen anyway.
     *
     * An activity declaring `resizeableActivity="false"` is launched full screen whatever options
     * it is given, and silently. That is the one refusal reachable from here: resizeability lives
     * in ActivityInfo.resizeMode, which is hidden, so it is read reflectively and any failure to
     * read it is taken as "assume it floats" — a missed warning costs one surprised glance, while
     * warning whenever the field can't be read would fire on every launch if it ever disappears,
     * which trains the user to ignore it.
     *
     * It is deliberately not the whole story. OEM skins keep their own lists on top of the
     * platform's: measured on this device, HyperOS expands Google's Phone app to full screen even
     * though it reports itself resizeable and does reach freeform first. Nothing public exposes
     * that list, or the windowing mode another app's task ended up in, so those launches go
     * unwarned rather than being guessed at.
     */
    private fun warnIfUnresizeable(activity: Activity, intent: Intent, label: CharSequence?) {
        val info = intent.resolveActivityInfo(activity.packageManager, 0) ?: return
        if (isResizeable(info) != false) return
        val name = label ?: info.loadLabel(activity.packageManager)
        Toast.makeText(
            activity,
            activity.getString(R.string.float_unsupported, name),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** True/false when the hidden `resizeMode` could be read, null when it couldn't.
     *  RESIZE_MODE_UNRESIZEABLE is 0; every other mode floats, at least when forced. */
    private fun isResizeable(info: ActivityInfo): Boolean? = runCatching {
        ActivityInfo::class.java.getField("resizeMode").getInt(info) != 0
    }.getOrNull()
}
