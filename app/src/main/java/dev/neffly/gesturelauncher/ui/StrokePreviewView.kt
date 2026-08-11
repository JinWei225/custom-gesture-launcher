package dev.neffly.gesturelauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * Renders a saved stroke as a small thumbnail, scaled to fit the view bounds with padding.
 * Used in the gesture-settings rows and the training confirm preview.
 */
class StrokePreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var stroke: List<PointF> = emptyList()
    private var subStrokeStarts: Set<Int> = setOf(0)
    private val path = Path()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.GRAY
    }

    /** [subStrokeLengths] is the point-count of each pen-lift sub-stroke (see GestureCanvasView);
     *  empty means "single unbroken stroke". */
    fun setStroke(points: List<PointF>, subStrokeLengths: List<Int> = emptyList()) {
        stroke = points
        subStrokeStarts = subStrokeStartIndices(subStrokeLengths)
        rebuildPath()
        invalidate()
    }

    private fun subStrokeStartIndices(lengths: List<Int>): Set<Int> {
        if (lengths.isEmpty()) return setOf(0)
        val starts = LinkedHashSet<Int>()
        var index = 0
        for (length in lengths) {
            starts.add(index)
            index += length
        }
        return starts
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPath()
    }

    private fun rebuildPath() {
        path.reset()
        if (stroke.size < 2 || width == 0 || height == 0) return

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in stroke) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val pad = 8f
        val srcW = (maxX - minX).takeIf { it != 0f } ?: 1f
        val srcH = (maxY - minY).takeIf { it != 0f } ?: 1f
        val scale = minOf((width - 2 * pad) / srcW, (height - 2 * pad) / srcH)
        val offsetX = (width - srcW * scale) / 2f
        val offsetY = (height - srcH * scale) / 2f

        stroke.forEachIndexed { i, p ->
            val x = offsetX + (p.x - minX) * scale
            val y = offsetY + (p.y - minY) * scale
            if (i in subStrokeStarts) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Take the theme's on-surface colour and force it to PREVIEW_ALPHA, so the thumbnail
        // reads as a muted sketch rather than competing with the row's actual text. Resolved
        // per draw rather than cached, so a theme change is picked up on the next invalidate.
        val tint = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface, Color.GRAY
        )
        paint.color = (tint and 0x00FFFFFF) or PREVIEW_ALPHA
        if (!path.isEmpty) canvas.drawPath(path, paint)
    }

    companion object {
        /** ~60% opacity, in the top byte of an ARGB int. */
        private const val PREVIEW_ALPHA = 0x99000000.toInt()
    }
}
