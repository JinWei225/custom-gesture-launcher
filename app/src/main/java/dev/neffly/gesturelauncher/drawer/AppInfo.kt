package dev.neffly.gesturelauncher.drawer

import android.content.ComponentName
import android.os.UserHandle
import dev.neffly.gesturelauncher.search.SearchScoring

/** A launchable app entry for the drawer / app picker. [label] is always the real OS-provided
 *  name — never overwritten. [tag] is an optional user-set searchable shortcut (see AppTagStore),
 *  shown as a badge and prioritized on an exact match. [user] is the activity's profile (work
 *  apps appear alongside personal ones). Icons aren't carried here — loaded lazily via [IconCache]
 *  so the scan stays cheap and memory isn't pinned by icons never scrolled to. */
data class AppInfo(
    val label: String,
    val packageName: String,
    val componentName: ComponentName,
    val user: UserHandle,
    val tag: String? = null
) {
    /** Stable identity for icon caching and list diffing: component + profile. Computed once:
     *  DiffUtil and the icon cache ask for it on every bind and comparison. */
    val key: String = "${componentName.flattenToString()}#$user"

    /** [label] and [tag] folded for matching — see [SearchScoring.normalize]. Done here, once per
     *  scan, rather than per keystroke: ranking normalizes every label on every character typed,
     *  and the fold (an NFKD pass plus a regex) was the bulk of that work. */
    val normalizedLabel: String = SearchScoring.normalize(label)
    val normalizedTag: String? = tag?.let(SearchScoring::normalize)
}

/** Bucket used by the drawer's alphabet fast-scroll index: A-Z as themselves, everything else
 *  (digits, symbols, non-Latin scripts) under '#' — the conventional fast-scroll-index catch-all,
 *  same as e.g. AOSP Contacts. */
fun AppInfo.indexLetter(): Char = label.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
