package dev.neffly.gesturelauncher.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
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

/** Whether the OS has been told to leave this app alone. Drives the settings row's subtitle. */
fun Context.isBatteryOptimizationExempt(): Boolean {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(packageName)
}

/**
 * Deep-links to whatever screen on this device stops the launcher being hibernated.
 *
 * This is the actual fix for the drawer feeling slow: OEM power management (Xiaomi/HyperOS most
 * aggressively) kills the launcher process when it hasn't been used for a while, which drops the
 * app-list cache, the icon cache, and the LauncherApps callback registration. The disk snapshot
 * (see AppListSnapshot) makes that cheap to recover from; this makes it stop happening.
 *
 * Same shape as [openDefaultLauncherSettings]: a candidate list tried in order. It uses
 * `runCatching { startActivity }` rather than `resolveActivity` on purpose — declaring five OEM
 * packages in <queries> just to probe for them is worse than catching the failure, and MIUI throws
 * SecurityException (not ActivityNotFoundException) for some of these, which a resolve check
 * wouldn't predict anyway.
 */
// BatteryLife flags ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS as an abused API. Here the app
// being killed IS the user-visible bug the setting exists to fix, and it's a launcher, not
// something doing background work it hasn't earned.
@SuppressLint("BatteryLife")
fun Activity.openBatteryOptimizationSettings() {
    val candidates = buildList {
        // Skipped when already exempt: the system dialog is a no-op in that case, which reads as
        // the button being broken.
        if (!isBatteryOptimizationExempt()) {
            add(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
        add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        OEM_AUTOSTART_SCREENS.forEach { add(Intent().setComponent(it)) }
        add(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }
    for (intent in candidates) {
        if (runCatching { startActivity(intent); true }.getOrDefault(false)) return
    }
    Toast.makeText(this, R.string.could_not_open_settings, Toast.LENGTH_SHORT).show()
}

/** OEM "autostart" / background-app managers, which on those skins gate more than the AOSP
 *  battery-optimization exemption does. Tried only after the standard intents fail. */
private val OEM_AUTOSTART_SCREENS = listOf(
    ComponentName(
        "com.miui.securitycenter",
        "com.miui.permcenter.autostart.AutoStartManagementActivity"
    ),
    ComponentName(
        "com.huawei.systemmanager",
        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
    ),
    ComponentName(
        "com.coloros.safecenter",
        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
    ),
    ComponentName(
        "com.vivo.permissionmanager",
        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
    ),
    ComponentName(
        "com.samsung.android.lool",
        "com.samsung.android.sm.ui.battery.BatteryActivity"
    )
)
