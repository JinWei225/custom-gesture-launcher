package dev.neffly.gesturelauncher.ui

import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * A settings screen that slides in from the right edge and back out on close.
 *
 * The motion is driven by the panel's own view rather than by a window transition, for the reason
 * [overrideOwnTransitions] gives: OEM skins substitute their own "app opening" zoom for anything
 * that leaves the window's animation slots empty, and an animation inside our own view hierarchy is
 * the one thing they can't intercept.
 *
 * Three screens do this — the settings hub, the add-gesture chooser and the URL entry form — and
 * they did it by copy before this class existed. That is how two of the three came to be missing
 * the recreate guard [slideIn] applies, and all three the [onStop] completion below.
 */
abstract class SlidePanelActivity : BaseActivity() {

    private var panel: View? = null
    private var isClosing = false

    /**
     * Starts [panel] off the right edge and brings it in. Call from onCreate, after setContentView.
     *
     * [savedInstanceState] is what separates a genuine open from a recreate: changing the theme or
     * the font size rebuilds the activity, and replaying the entry slide there reads as the panel
     * re-opening itself rather than as the screen it already was.
     */
    protected fun slideIn(panel: View, savedInstanceState: Bundle?) {
        this.panel = panel
        if (savedInstanceState != null) return
        panel.translationX = resources.displayMetrics.widthPixels.toFloat()
        panel.animate()
            .translationX(0f)
            .setDuration(SLIDE_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Slides the panel back out, then finishes for real. */
    override fun finish() {
        val panel = panel
        if (isClosing || isFinishing || panel == null) {
            super.finish()
            return
        }
        isClosing = true
        panel.animate()
            .translationX(resources.displayMetrics.widthPixels.toFloat())
            .setDuration(SLIDE_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { super.finish() }
            .start()
        overrideNextTransition()
    }

    /**
     * Completes a close the window outran. An animation on a view that has been detached never
     * delivers its end action, so pressing Home mid-slide would otherwise leave this activity
     * un-finished on the back stack behind a panel frozen half off-screen.
     */
    override fun onStop() {
        super.onStop()
        if (isClosing && !isFinishing) super.finish()
    }

    private companion object {
        /** Matched by @anim/drawer_slide_out, which covers the closes that never reach finish(). */
        const val SLIDE_DURATION_MS = 260L
    }
}
