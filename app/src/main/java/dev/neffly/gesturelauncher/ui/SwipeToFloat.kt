package dev.neffly.gesturelauncher.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.drawer.AppListAdapter
import dev.neffly.gesturelauncher.launch.FloatingWindow
import dev.neffly.gesturelauncher.search.SearchResult

/**
 * Swipe a result row to the right to open it in a floating window.
 *
 * Attached to both the drawer's list and the quick-search card's, so the gesture means the same
 * thing wherever results are shown. A row only swipes if swiping it would do something:
 * [FloatingWindow.canFloat] rules out alphabet headers, section labels, results with no window of
 * their own, and devices with no freeform support at all. Everywhere else the row stays put rather
 * than sliding and snapping back, so the list never offers an action it won't perform.
 *
 * The row always comes back. Unlike a delete swipe there is nothing to remove: the swipe is a way
 * of *opening* the row, so it is rebound in place. [onFloat] is where the screen opens the result
 * and does whatever closing it needs.
 */
class SwipeToFloat(
    private val adapter: AppListAdapter,
    private val onFloat: (SearchResult) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var icon: Drawable? = null

    override fun getSwipeDirs(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return 0
        val result = adapter.resultAt(position) ?: return 0
        return if (FloatingWindow.canFloat(recyclerView.context, result)) ItemTouchHelper.RIGHT else 0
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        val result = adapter.resultAt(position)
        // Put the row back before anything else: ItemTouchHelper has left it swiped off-screen,
        // and if the launch fails the list must not be left with a hole where the row was.
        adapter.notifyItemChanged(position)
        result?.let(onFloat)
    }

    /**
     * Paints what the swipe reveals: the float icon and its label on a tinted band, fading in over
     * the first part of the travel so a stray horizontal nudge doesn't flash a full-strength panel.
     */
    override fun onChildDraw(
        canvas: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val row = viewHolder.itemView
        if (dX > 0f) {
            val glyph = icon ?: ContextCompat.getDrawable(row.context, R.drawable.ic_float_window)
                ?.also { icon = it }
            val accent = MaterialColors.getColor(row, com.google.android.material.R.attr.colorPrimary)
            val progress = (dX / (row.width * REVEAL_SPAN)).coerceIn(0f, 1f)

            backgroundPaint.color = ColorUtils.setAlphaComponent(accent, (BAND_ALPHA * progress).toInt())
            val inset = row.resources.displayMetrics.density * BAND_INSET_DP
            val radius = row.resources.displayMetrics.density * BAND_RADIUS_DP
            canvas.drawRoundRect(
                RectF(row.left.toFloat(), row.top + inset, row.left + dX, row.bottom - inset),
                radius,
                radius,
                backgroundPaint
            )

            glyph?.let { drawGlyph(canvas, row, it, accent, dX, progress) }
        }
        super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    /** Icon pinned near the leading edge, clipped to whatever the swipe has actually uncovered so
     *  it slides out from under the row rather than appearing whole at zero travel. */
    private fun drawGlyph(
        canvas: Canvas,
        row: View,
        glyph: Drawable,
        accent: Int,
        dX: Float,
        progress: Float
    ) {
        val density = row.resources.displayMetrics.density
        val size = (density * ICON_DP).toInt()
        val left = row.left + (density * ICON_MARGIN_DP).toInt()
        if (left + size > row.left + dX) return
        val top = row.top + (row.height - size) / 2
        glyph.setBounds(left, top, left + size, top + size)
        glyph.setTint(ColorUtils.setAlphaComponent(accent, (255 * progress).toInt()))
        glyph.draw(canvas)
    }

    private companion object {
        /** Travel, as a fraction of row width, over which the reveal reaches full strength. */
        const val REVEAL_SPAN = 0.35f
        const val BAND_ALPHA = 56f
        const val BAND_INSET_DP = 4f
        const val BAND_RADIUS_DP = 16f
        const val ICON_DP = 24f
        const val ICON_MARGIN_DP = 20f
    }
}
