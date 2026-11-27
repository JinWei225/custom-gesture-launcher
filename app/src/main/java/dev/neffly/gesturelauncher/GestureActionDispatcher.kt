package dev.neffly.gesturelauncher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dev.neffly.gesturelauncher.data.GestureAction
import dev.neffly.gesturelauncher.data.GestureMapping
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.drawer.AppDrawerActivity
import dev.neffly.gesturelauncher.drawer.AppRepository
import dev.neffly.gesturelauncher.launch.SystemPanels
import dev.neffly.gesturelauncher.search.Links
import dev.neffly.gesturelauncher.search.QuickSearchActivity
import dev.neffly.gesturelauncher.ui.overrideNextTransition

/** Carries out whatever a recognized [GestureMapping] means — the one place that understands every
 *  [GestureAction] value, so callers (currently just [MainActivity]) don't need to. */
object GestureActionDispatcher {

    /**
     * Nothing to report back: every branch that can fail already tells the user itself
     * ([AppRepository.launch] and the URL branch both toast), and the one caller's confirmation
     * haptic deliberately fires *before* this runs — see MainActivity.onHomeStroke, where the point
     * is instant feedback while the window takes a beat to appear. So there is no moment at which a
     * result could still change what the caller does.
     */
    fun perform(context: Context, mapping: GestureMapping) {
        when (mapping.action) {
            GestureAction.LAUNCH_APP ->
                ComponentName.unflattenFromString(mapping.componentName)?.let { comp ->
                    AppRepository.launch(context, comp)
                }
            GestureAction.OPEN_DRAWER -> openDrawer(context)
            GestureAction.QUICK_SEARCH -> openSearch(context)
            GestureAction.NOTIFICATIONS -> if (!SystemPanels.expandNotifications(context)) {
                Toast.makeText(context, R.string.system_panel_failed, Toast.LENGTH_SHORT).show()
            }
            GestureAction.QUICK_SETTINGS -> if (!SystemPanels.expandQuickSettings(context)) {
                Toast.makeText(context, R.string.system_panel_failed, Toast.LENGTH_SHORT).show()
            }
            GestureAction.OPEN_URL -> {
                val url = mapping.url
                if (url != null) Links.open(context, url)
                else Toast.makeText(context, R.string.gesture_url_open_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openDrawer(context: Context) {
        context.startActivity(Intent(context, AppDrawerActivity::class.java))
        // The drawer animates its own slide-up; keep the OS transition out of it, so it can't
        // fight with (or be replaced by an OEM "app open" animation instead of) that slide. On
        // Android 14+ the drawer's own overrideActivityTransition(..., 0, 0) handles its side.
        (context as? Activity)?.overrideNextTransition()
    }

    /**
     * The floating search when it is switched on, else the drawer. The overlay switch is off by
     * default, and a search the user asked for — a tap on the search button or a drawn gesture —
     * must never be a dead end: the drawer has a search field of its own.
     */
    fun openSearch(context: Context) {
        if (!Prefs.quickSearchEnabled(context)) return openDrawer(context)
        context.startActivity(QuickSearchActivity.intent(context))
        // The card animates its own fade/rise — same reasoning as the drawer.
        (context as? Activity)?.overrideNextTransition()
    }
}
