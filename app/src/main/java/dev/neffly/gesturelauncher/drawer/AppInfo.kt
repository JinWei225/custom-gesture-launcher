package dev.neffly.gesturelauncher.drawer

import android.content.ComponentName
import android.os.UserHandle

/** A launchable app entry for the drawer / app picker. [label] is always the real OS-provided
 *  name — never overwritten. [tag] is an optional short user-set searchable shortcut (see
 *  AppTagStore), shown as a small badge and given top priority on an exact search match.
 *  [user] is the profile the activity lives in (work-profile apps appear alongside personal
 *  ones). Icons are NOT carried here — they're loaded lazily via [IconCache] so the app scan
 *  stays cheap and memory isn't pinned by icons the user never scrolls to. */
data class AppInfo(
    val label: String,
    val packageName: String,
    val componentName: ComponentName,
    val user: UserHandle,
    val tag: String? = null
) {
    /** Stable identity for icon caching and list diffing: component + profile. */
    val key: String get() = "${componentName.flattenToString()}#$user"
}

/** Bucket used by the drawer's alphabet fast-scroll index: A-Z as themselves, everything else
 *  (digits, symbols, non-Latin scripts) under '#' — the conventional fast-scroll-index catch-all,
 *  same as e.g. AOSP Contacts. */
fun AppInfo.indexLetter(): Char = label.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
