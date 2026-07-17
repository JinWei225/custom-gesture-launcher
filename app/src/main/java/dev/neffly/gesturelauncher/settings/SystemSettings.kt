package dev.neffly.gesturelauncher.settings

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import dev.neffly.gesturelauncher.R

/**
 * Deep-links to the system "change default Home app" picker, falling back gracefully by API
 * level/OEM. No app can force-unset itself as the default launcher (Android security) — this is
 * the best available shortcut to the picker. Shared by [SettingsHubActivity] and
 * [dev.neffly.gesturelauncher.crash.SafeModeActivity].
 */
fun Activity.openDefaultLauncherSettings() {
    val candidates = buildList {
        add(Intent(Settings.ACTION_HOME_SETTINGS))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
        add(Intent(Settings.ACTION_SETTINGS))
    }
    for (intent in candidates) {
        if (runCatching { startActivity(intent); true }.getOrDefault(false)) return
    }
    Toast.makeText(this, R.string.could_not_open_settings, Toast.LENGTH_SHORT).show()
}
