package dev.neffly.gesturelauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import dev.neffly.gesturelauncher.R

/**
 * Full-screen transparent canvas that captures a stroke session (down -> move -> up, optionally
 * repeated as pen-lift sub-strokes within [multiStrokeGapMillis]) and draws a light trail for
 * feedback. Once the session is finalized it hands the concatenated points to [onStroke]; the
 * owner decides what to do (recognize + launch on the home screen, or collect for training).
 *
 * Deliberately dependency-free of the recognizer so a matching bug can't crash the capture layer.
 */
class GestureCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Invoked once a session finalizes, with the collected points and the point-count of each
     *  sub-stroke (pen lift) it was drawn with — e.g. [15, 20] for a two-part stroke, summing to
     *  the point list's size. An empty session is not delivered. */
    var onStroke: ((List<PointF>, List<Int>) -> Unit)? = null

    /** When > 0, the drawn trail auto-clears after this delay (used on the home screen). */
    var autoClearMillis: Long = 0L

    /** When > 0, a pen-lift within this window starts a new sub-stroke of the same session
     *  instead of finalizing immediately. 0 (default) keeps every stroke single-part and
     *  instant-on-lift, exactly like before multi-stroke support existed. */
    var multiStrokeGapMillis: Long = 0L

    /** When > 0, a pen-lift that brings the session to this many sub-strokes finalizes
     *  immediately instead of waiting out [multiStrokeGapMillis] — no trained gesture has more
     *  parts, so there's nothing further to wait for. Set by the owner to the maximum sub-stroke
     *  count across its templates. */
    var maxExpectedSubStrokes: Int = 0

    private val points = ArrayList<PointF>()
    private val path = Path()
    private val subStrokeLengths = ArrayList<Int>()
    private var finalizePending = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.wallpaper_overlay_text)
        alpha = STROKE_ALPHA
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Wider dark stroke drawn under the white core — replaces the old setShadowLayer halo.
     *  A blur shadow forces LAYER_TYPE_SOFTWARE, i.e. a full-screen CPU rasterization on every
     *  invalidate while drawing; two plain strokes stay fully hardware-accelerated. */
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.scrim)
        strokeWidth = 22f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Colour of the drawn stroke. Defaults to the wallpaper-overlay white, which is right for
     *  the home screen — the only place this view is drawn straight over the wallpaper.
     *
     *  The two settings screens that host it (gesture training and sensitivity) put it on a
     *  themed card instead, where white-on-near-white is invisible in light mode, so they
     *  override this with colorPrimary. */
    var strokeColor: Int
        get() = paint.color
        set(value) {
            paint.color = value
            // Assigning .color resets the alpha channel, so re-apply it afterwards.
            paint.alpha = STROKE_ALPHA
            invalidate()
        }

    /** Colour of the wider stroke drawn beneath [strokeColor]. Its job is contrast against an
     *  unknown wallpaper, so on a themed surface — where the background is known — callers set
     *  it to transparent rather than stacking a dark halo under a dark stroke. */
    var haloColor: Int
        get() = haloPaint.color
        set(value) {
            haloPaint.color = value
            invalidate()
        }

    init {
        isFocusable = true
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(clearRunnable)
                removeCallbacks(finalizeRunnable)
                if (finalizePending) {
                    // Pen lifted and came back down within the gap window: continue this session
                    // as a new sub-stroke rather than starting over.
                    finalizePending = false
                } else {
                    points.clear()
                    path.reset()
                    subStrokeLengths.clear()
                }
                subStrokeLengths.add(1)
                points.add(PointF(event.x, event.y))
                path.moveTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Android batches fast touch samples into one event; the intermediate samples
                // arrive as "historical" points. Folding them in costs nothing and gives the
                // recognizer (and the trail) the full-resolution stroke.
                for (h in 0 until event.historySize) {
                    val hx = event.getHistoricalX(h)
                    val hy = event.getHistoricalY(h)
                    points.add(PointF(hx, hy))
                    path.lineTo(hx, hy)
                    subStrokeLengths[subStrokeLengths.size - 1]++
                }
                points.add(PointF(event.x, event.y))
                path.lineTo(event.x, event.y)
                subStrokeLengths[subStrokeLengths.size - 1]++
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val complete =
                    maxExpectedSubStrokes > 0 && subStrokeLengths.size >= maxExpectedSubStrokes
                if (multiStrokeGapMillis > 0 && !complete) {
                    finalizePending = true
                    postDelayed(finalizeRunnable, multiStrokeGapMillis)
                } else {
                    finalizeSession()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                clearStroke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private val finalizeRunnable = Runnable {
        finalizePending = false
        finalizeSession()
    }

    // Deliberately NOT named finalize(): a no-arg method with that name shadows
    // java.lang.Object.finalize and invites subtle GC-related surprises.
    private fun finalizeSession() {
        if (points.isNotEmpty()) {
            onStroke?.invoke(ArrayList(points), ArrayList(subStrokeLengths))
        }
        if (autoClearMillis > 0) {
            postDelayed(clearRunnable, autoClearMillis)
        }
    }

    private val clearRunnable = Runnable { clearStroke() }

    fun clearStroke() {
        removeCallbacks(finalizeRunnable)
        finalizePending = false
        points.clear()
        path.reset()
        subStrokeLengths.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!path.isEmpty) {
            canvas.drawPath(path, haloPaint)
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        const val MULTI_STROKE_GAP_MILLIS = 400L

        /** Slight transparency on the stroke so it reads as ink over the backdrop rather than
         *  as an opaque cut-out. */
        private const val STROKE_ALPHA = 210
    }
}
