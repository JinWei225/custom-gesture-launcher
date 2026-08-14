package dev.neffly.gesturelauncher.ui

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * A RecyclerView that grows with its content up to a ceiling, then scrolls inside it.
 *
 * RecyclerView ignores android:maxHeight, so `wrap_content` alone would let the floating quick
 * search card grow past the screen. Clamping the height spec to AT_MOST here is what lets the card
 * hug two results and cap out on twenty.
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
        val spec = if (maxHeightPx > 0) {
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        } else {
            heightSpec
        }
        super.onMeasure(widthSpec, spec)
    }
}
