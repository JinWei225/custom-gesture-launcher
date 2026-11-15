package dev.neffly.gesturelauncher.launch

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log

/**
 * Pulls down the notification shade or quick settings, for the gestures that do.
 *
 * There is no public API for it. StatusBarManager has the methods, hidden but callable with the
 * normal (auto-granted) EXPAND_STATUS_BAR permission — the route third-party launchers have long
 * taken, and one that needs no accessibility service. Reflection, as with ActivityInfo.resizeMode
 * elsewhere in the app; a phone where it has gone reports false, and the caller says so.
 */
object SystemPanels {

    fun expandNotifications(context: Context): Boolean = call(context, "expandNotificationsPanel")

    fun expandQuickSettings(context: Context): Boolean = call(context, "expandSettingsPanel")

    @SuppressLint("WrongConstant") // "statusbar" is the service's name; it has no public constant.
    private fun call(context: Context, method: String): Boolean = runCatching {
        val statusBar = context.getSystemService("statusbar") ?: return false
        statusBar.javaClass.getMethod(method).invoke(statusBar)
        true
    }.getOrElse {
        Log.w("SystemPanels", "$method failed", it)
        false
    }
}
