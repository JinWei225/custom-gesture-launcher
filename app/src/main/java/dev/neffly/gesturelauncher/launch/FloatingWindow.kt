package dev.neffly.gesturelauncher.launch

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
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

    /** Fraction of the screen the floating window occupies. Wide enough for real content, small
     *  enough that what it was launched over stays visible around it — the point of floating it. */
    private const val WIDTH_FRACTION = 0.86f
    private const val HEIGHT_FRACTION = 0.60f

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
    fun canFloat(context: Context, result: SearchResult): Boolean =
        result !is SearchResult.Settings &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)

    /** Opens [result] floating. Only call for a [result] that [canFloat] accepts. */
    fun open(activity: Activity, result: SearchResult) {
        when (result) {
            is SearchResult.App -> openApp(activity, result.app)
            is SearchResult.File ->
                start(activity, FileSearcher.intentFor(result.hit), R.string.file_open_failed)
            is SearchResult.Web ->
                start(activity, WebSearch.intentFor(result.query, result.url), R.string.web_search_failed)
            is SearchResult.Settings -> Unit
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
     * Centred bounds plus the freeform windowing mode.
     *
     * setLaunchBounds is public API and is documented as the way to launch into a freeform window,
     * but on its own it isn't enough: measured on device, an app started with bounds and nothing
     * else comes up full screen, while the same app started with `am start --windowingMode 5`
     * lands in freeform. The mode has to be asked for explicitly.
     *
     * ActivityOptions.setLaunchWindowingMode is hidden, so the mode goes into the options Bundle
     * under the key that method writes. That is a plain Bundle entry, not a reflective call into a
     * hidden member, so the non-SDK interface restrictions don't apply to it — and on a platform
     * that doesn't read the key it is an ignored extra, leaving exactly the full-screen launch
     * that omitting it would have produced.
     */
    private fun options(activity: Activity): Bundle {
        val metrics = activity.resources.displayMetrics
        val width = (metrics.widthPixels * WIDTH_FRACTION).toInt()
        val height = (metrics.heightPixels * HEIGHT_FRACTION).toInt()
        val left = (metrics.widthPixels - width) / 2
        val top = (metrics.heightPixels - height) / 2
        return ActivityOptions.makeBasic()
            .setLaunchBounds(Rect(left, top, left + width, top + height))
            .toBundle()
            .apply { putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_FREEFORM) }
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
