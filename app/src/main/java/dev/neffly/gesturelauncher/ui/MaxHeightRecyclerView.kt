package dev.neffly.gesturelauncher.ui

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * A RecyclerView that grows with its content up to a ceiling, then scrolls inside it — and, when
 * it does hit the ceiling, stops at the last row that fits whole.
 *
 * RecyclerView ignores android:maxHeight, so `wrap_content` alone would let the floating quick
 * search card grow past the screen. Clamping the height spec to AT_MOST here is what lets the card
 * hug a short result list and cap out on a long one.
 *
 * The whole-row trim lives here, in measurement, rather than in the activity: every measure
 * starts again from [maxHeightPx], so rows that arrive after the list was last trimmed — the file
 * section lands a beat after the apps — get the room they need. Trimming from outside, off the
 * rows already on screen, could only ever shrink the list: the rows on screen are the ones that
 * fit the previous trim, so their total never exceeds it.
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    /** Ceiling in pixels. 0 or less means unbounded. */
    var maxHeightPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (maxHeightPx <= 0) {
            super.onMeasure(widthSpec, heightSpec)
            return
        }
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
        // Under an AT_MOST spec the layout manager has just laid the rows out to fill the ceiling,
        // so its children are exactly the rows that fit — the last of them possibly only in part.
        // Rows are read off the layout rather than assumed because they aren't uniform: a row with
        // a subtitle stands taller than the minimum an app row sits at.
        //
        // The layout manager's children, not the view group's: a row on its way out — removed by
        // the last keystroke, still fading — stays in the view tree as a hidden child until its
        // animation ends, and a measure in that window puts the live rows *after* it. Summing
        // heights across all children would count the ghost, and the list would come up short.
        val layout = layoutManager ?: return
        val floor = measuredHeight - paddingBottom
        var fit = paddingTop
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i) ?: continue
            val bottom = layout.getDecoratedBottom(child) + (child.layoutParams as LayoutParams).bottomMargin
            if (bottom in (fit + 1)..floor) fit = bottom
        }
        if (fit in (paddingTop + 1) until floor) {
            setMeasuredDimension(measuredWidth, fit + paddingBottom)
        }
    }
}
