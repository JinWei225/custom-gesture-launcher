package dev.neffly.gesturelauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * An [AppWidgetHostView] that can be long-pressed as a whole.
 *
 * A widget's own buttons take the touches that land on them, so a long-click listener on the host
 * never fires over the parts of a widget that do anything. This watches the touch from
 * [onInterceptTouchEvent] instead — where every touch passes first — and once the press has held
 * still for the long-press timeout, claims it: the widget underneath sees a cancel rather than a
 * click, and [performLongClick] runs. A press that moves first is a scroll or a tap for the widget
 * and is left alone. This is the same arrangement the platform launcher uses.
 */
class WidgetHostView(context: Context) : AppWidgetHostView(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false

    private val longPress = Runnable {
        longPressed = true
        performLongClick()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        track(event)
        return longPressed
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Consumed either way: a press on an empty part of the widget has to keep arriving here for
        // the long-press to fire, and once claimed the rest of the gesture is ours to swallow. The
        // page still scrolls from a drag that starts on a widget — the scroll view intercepts a
        // drag whoever is holding it, and the cancel that arrives then is handled below.
        track(event)
        return true
    }

    /** Arms the long-press on a press and disarms it on a move, a lift or a cancel. Both entry
     *  points feed through here: which one a touch arrives by depends on whether a child of the
     *  widget took it. */
    private fun track(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                longPressed = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
    }

    /** A child that takes over the gesture — a list inside the widget starting to scroll — is
     *  not being long-pressed, and no more of its touches will pass through here to say so. */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept) removeCallbacks(longPress)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }
}
