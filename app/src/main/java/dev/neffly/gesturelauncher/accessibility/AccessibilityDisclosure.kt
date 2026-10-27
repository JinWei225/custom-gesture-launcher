package dev.neffly.gesturelauncher.accessibility

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.settings.openAccessibilitySettings
import dev.neffly.gesturelauncher.ui.showWithFont

/**
 * The one way into the accessibility screen for [LauncherAccessibilityService]: the settings hub's
 * keyboard row and the dock's double-tap lock both come through here.
 *
 * Google Play requires an app that isn't an accessibility tool to say, in the app and before the
 * user is sent to grant the access, what the service touches and what it does with it, and to get
 * an affirmative answer. So nothing opens the system screen until "Agree" is tapped; dismissing the
 * dialog leaves the user where they were.
 *
 * Once access is granted there is nothing left to consent to, so the settings screen opens
 * directly — that path is for reviewing or turning the access off.
 */
fun Activity.requestAccessibilityAccess() {
    if (LauncherAccessibilityService.isGranted(this)) {
        openAccessibilitySettings()
        return
    }
    AlertDialog.Builder(this)
        .setTitle(R.string.accessibility_disclosure_title)
        .setMessage(R.string.accessibility_disclosure_message)
        .setPositiveButton(R.string.accessibility_disclosure_agree) { _, _ -> openAccessibilitySettings() }
        .setNegativeButton(R.string.accessibility_disclosure_decline, null)
        .showWithFont()
}
