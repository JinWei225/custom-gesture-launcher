package dev.neffly.gesturelauncher.ui

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * The blank part of the dock beside the search button. A drag there pulls the pages along with
 * the finger and lets go of them as a launcher does, and a double tap locks the screen.
 *
 * The pages are moved through the pager's fake drag rather than by letting the pager take the
 * touch: on the home page the pager takes no touch at all, since a horizontal drag there is a
 * stroke for the canvas, and this strip is the one place on that page where a drag means "turn
 * the page" instead. (Lint wants a performClick behind a touch listener; a single tap does
 * nothing here, so there is no click to perform.)
 */
@SuppressLint("ClickableViewAccessibility")
class DockStrip(strip: View, private val pager: ViewPager2, private val onDoubleTap: () -> Unit) {

    private var dragging = false

    /** Set once a touch has shown itself to be vertical: it is left alone for its whole life
     *  rather than being picked up as a page drag when it later wanders sideways. */
    private var vertical = false

    private val detector = GestureDetector(strip.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            vertical = false
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (vertical) return false
            // onScroll only starts past the touch slop, so a tap never nudges the pages. A touch
            // that is heading up or down when it first moves isn't a page turn — it may well be
            // a swipe up from the navigation bar that slipped into this window.
            if (!dragging) {
                if (e1 != null && abs(e2.y - e1.y) > abs(e2.x - e1.x)) {
                    vertical = true
                    return false
                }
                dragging = pager.beginFakeDrag()
            }
            // distanceX is last-minus-current: a finger moving right gives a negative value, and
            // a positive fake drag moves the content right, toward the previous page.
            if (dragging) pager.fakeDragBy(-distanceX)
            return dragging
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            strip.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onDoubleTap()
            return true
        }
    })

    init {
        strip.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (dragging) pager.endFakeDrag()
                dragging = false
            }
            true
        }
    }
}
