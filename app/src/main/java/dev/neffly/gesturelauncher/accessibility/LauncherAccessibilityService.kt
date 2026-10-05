package dev.neffly.gesturelauncher.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.search.QuickSearchActivity

/**
 * The launcher's accessibility service, doing the two things an ordinary app can't do alone:
 * opening the floating search bar from a physical keyboard (Alt+Space), and locking the screen
 * from the home screen's lock button.
 *
 * An accessibility service is the only way an ordinary app can see a key press that isn't aimed at
 * one of its own windows. Nothing else reaches: `onKeyShortcut` and `dispatchKeyEvent` only fire
 * while this app is focused, which is exactly when the shortcut is useless; the Meta+key table the
 * platform does dispatch globally is `/system/etc/bookmarks.xml`, readable only by the system. This
 * is how a launcher that isn't part of the OS gets the behaviour HyperOS's own launcher has by
 * being part of it.
 *
 * Locking is the same story. The only other route is device-admin, which puts a password-policy
 * authority on the device and blocks uninstalling the launcher until it is revoked — far more than
 * a lock button should ask for. [AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN] asks for nothing
 * beyond the grant this service already has.
 *
 * The service is otherwise inert. It requests no accessibility event types it acts on, reads no
 * window content, and its only outputs are starting an activity the user asked for and a lock the
 * user tapped — see the `<accessibility-service>` config, which asks for
 * `flagRequestFilterKeyEvents` and nothing else.
 *
 * Enabling it is left entirely to the user in the system's accessibility screen; the settings hub
 * and the lock button only link there. Once bound it stays bound, and
 * [Prefs.keyboardShortcutEnabled] is what turns the shortcut on and off — so toggling the feature
 * never costs a second trip through that screen.
 */
class LauncherAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        bound = this
    }

    override fun onDestroy() {
        if (bound === this) bound = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * Returns true to swallow the combo, so Alt+Space never also reaches the app underneath.
     *
     * Both halves are consumed, not just the down: releasing a key whose press was eaten leaves
     * some apps holding a stuck modifier. The repeat guard is what stops a held combo from
     * launching the search bar over and over.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != SHORTCUT_KEY_CODE) return false
        if (!event.isAltPressed) return false
        if (!Prefs.keyboardShortcutEnabled(this)) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            runCatching { startActivity(QuickSearchActivity.intent(this)) }
                .onFailure { Log.w(TAG, "could not open quick search from the keyboard", it) }
        }
        return true
    }

    companion object {

        private const val TAG = "LauncherAccessibility"

        /** Alt+Space. Fixed rather than configurable: it is the combo the user asked for, it is
         *  free on Android (unlike Meta+Space, which switches keyboard layout), and a picker for
         *  it would need a physical keyboard attached just to configure the feature. */
        private const val SHORTCUT_KEY_CODE = KeyEvent.KEYCODE_SPACE

        /** The live instance while the system has this service bound. Global actions can only be
         *  performed from the service itself, so the lock button reaches it through here. */
        @Volatile
        private var bound: LauncherAccessibilityService? = null

        /** Whether the platform can lock the screen through an accessibility service at all. */
        val canLock: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        /** Locks the screen; false when the service isn't bound (access not granted) or the
         *  system refused. */
        fun lockScreen(): Boolean {
            if (!canLock) return false
            return bound?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
        }

        /**
         * Whether the user has granted this service accessibility access.
         *
         * Read from the secure setting rather than from [bound]: the user can revoke access from
         * the system screen at any time, and the process may well have been started fresh since.
         * The stored value is a colon-separated list of flattened component names, so it is
         * matched against both spellings the platform writes.
         */
        fun isGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = ComponentName(context, LauncherAccessibilityService::class.java)
            return enabled.split(':').any {
                it.equals(component.flattenToString(), ignoreCase = true) ||
                    it.equals(component.flattenToShortString(), ignoreCase = true)
            }
        }
    }
}
