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
        // so the children are exactly the rows that fit — the last of them possibly only in part.
        // Rows are measured rather than assumed because they aren't uniform: a row with a subtitle
        // stands taller than the minimum an app row sits at.
        val layout = layoutManager ?: return
        val room = measuredHeight - paddingTop - paddingBottom
        var used = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val params = child.layoutParams as LayoutParams
            val height = layout.getDecoratedMeasuredHeight(child) + params.topMargin + params.bottomMargin
            if (used + height > room) break
            used += height
        }
        if (used in 1 until room) {
            setMeasuredDimension(measuredWidth, used + paddingTop + paddingBottom)
        }
    }
}
