package dev.neffly.gesturelauncher.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.search.FilePermissions

/**
 * Deep-links to the system "change default Home app" picker. No app can force-unset itself as the default launcher (Android security) — this is
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
    startFirstThatOpens(candidates)
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
 * Same shape as the other deep links here — see [startFirstThatOpens].
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
    startFirstThatOpens(candidates)
}

/**
 * Whether this app currently holds the digital-assistant role — i.e. whether long-pressing the
 * power button reaches [dev.neffly.gesturelauncher.search.QuickSearchActivity].
 *
 * Two sources, because they don't always agree: RoleManager is the modern authority, but the
 * assistant also lives in a Secure setting that predates it and is what some OEM skins actually
 * update. Either naming us counts. That setting's key has no public constant, so it's spelled out,
 * and it holds either a flattened ComponentName or a bare package name depending on the build —
 * hence comparing only the part before the '/'.
 */
fun Context.isAssistantRoleHeld(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val held = runCatching {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }.getOrNull()
        if (held == true) return true
    }
    val current = Settings.Secure.getString(contentResolver, "assistant")
    return !current.isNullOrEmpty() && current.substringBefore('/') == packageName
}

/**
 * Opens the screen where the digital assistant is chosen, so quick search can be bound to the
 * power-button hold.
 *
 * Deliberately NOT RoleManager.createRequestRoleIntent(ROLE_ASSISTANT): the assistant role isn't
 * user-requestable, so that dialog finishes itself the instant it opens — it logs
 * "Package name cannot be null or empty" and returns RESULT_CANCELED, with nothing shown. (It also
 * requires startActivityForResult to populate the calling package at all, which plain
 * startActivity never does.) The result was a settings row that visibly did nothing. Assistant
 * selection is a Settings-only choice by design; this deep-links straight to it.
 *
 * ACTION_VOICE_INPUT_SETTINGS lands on "Assist & voice input", whose first row is "Digital
 * assistant app".
 */
fun Activity.openAssistantSettings() {
    val candidates = buildList {
        add(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
        add(Intent(Settings.ACTION_SETTINGS))
    }
    startFirstThatOpens(candidates)
}

/**
 * Opens whichever screen grants this app all-files access, needed for local file search on API 30+.
 * A no-op below that, where the grant is an ordinary runtime permission instead.
 */
fun Activity.openAllFilesAccessSettings() {
    val candidates = FilePermissions.requestIntents(this)
    // Empty means the grant doesn't exist on this API level, which is not a failure to report.
    if (candidates.isNotEmpty()) startFirstThatOpens(candidates)
}

/**
 * Starts the first of [candidates] that opens, and tells the user if none do.
 *
 * Every deep link here is a best guess at where a given build keeps a setting, so each is a list
 * tried in order — the AOSP intent first, then OEM-specific screens, then a broad fallback that at
 * least lands the user somewhere useful.
 *
 * `runCatching { startActivity }` rather than `resolveActivity`: probing would mean declaring every
 * OEM package in <queries> just to ask about it, and MIUI throws SecurityException (not
 * ActivityNotFoundException) for some of these, which a resolve check wouldn't predict anyway.
 */
private fun Activity.startFirstThatOpens(candidates: List<Intent>) {
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
