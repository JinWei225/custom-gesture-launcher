package dev.neffly.gesturelauncher.ui

import android.app.Activity
import android.os.Build
import androidx.annotation.AnimRes

/**
 * Keeps the platform — and OEM skins — out of this app's screen transitions.
 *
 * Every screen here draws its own entry and exit motion inside its own view hierarchy: the drawer
 * slides its root view up, the settings hub comes in from the right, the quick-search card fades
 * and lifts. A system transition running underneath that lands as a second, conflicting animation,
 * and skins like HyperOS substitute their own "app opening" zoom on any activity that leaves the
 * slot empty. So the slots are claimed and pinned to nothing.
 *
 * The platform splits this across an API boundary, which is the only reason there are two
 * functions rather than one:
 *
 *  - From API 34 the animation is a property of the activity, set once in `onCreate` and applying
 *    to every open and close from then on — [overrideOwnTransitions].
 *  - Before that it is a property of a single pending transition, so it has to be re-stated after
 *    each `startActivity` or `finish` — [overrideNextTransition].
 *
 * The two are complements, not alternatives: on API 34+ [overrideNextTransition] does nothing,
 * because the target screen's own [overrideOwnTransitions] has already covered that transition.
 * A screen that animates itself calls the first; a caller that starts or finishes one calls the
 * second, and between them every path is covered on every supported version.
 */

/**
 * Claims this activity's open and close animations, so nothing else can fill them. [closeExit] is
 * the one exception: a screen whose exit motion has to be driven by the window rather than by its
 * own views passes it here (the drawer's slide-out, which must play on Home too — the system
 * removes that window without the activity ever reaching `finish`).
 *
 * Call from `onCreate`. No-op below API 34, where [overrideNextTransition] does the work instead.
 */
fun Activity.overrideOwnTransitions(@AnimRes closeExit: Int = 0) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
    overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, closeExit)
}

/**
 * Sets the animation for the transition just started by a `startActivity` or `finish` on the line
 * above. Call immediately after that line.
 *
 * No-op from API 34, where the screen being opened or closed has already claimed its own
 * animations in `onCreate`.
 */
@Suppress("DEPRECATION")
fun Activity.overrideNextTransition(@AnimRes enter: Int = 0, @AnimRes exit: Int = 0) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    overridePendingTransition(enter, exit)
}
