package dev.neffly.gesturelauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import dev.neffly.gesturelauncher.R
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The pie launch style's touch surface: a finger going down opens a ring of [items] centred on it,
 * sliding toward one selects it, and lifting launches the selection through [onPick]. Lifting
 * inside the small dead zone at the centre picks nothing, which is how a ring is dismissed.
 *
 * The first item sits at the top and the rest follow clockwise, so the user's order in settings is
 * the order round the ring. The ring stays on the finger: where it wouldn't fit, the whole pie is
 * drawn smaller instead of being moved. A touch in the strip along either side edge opens nothing
 * — that is where the system's back gesture lives, and a pie there would have no room.
 */
class PieMenuView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    class Item(val label: String, val icon: Drawable?)

    /** Called with the index into [items] the finger was lifted on. */
    var onPick: ((Int) -> Unit)? = null

    /** Called when a touch ends on a pie with nothing in it, so the owner can say how to fill it. */
    var onEmpty: (() -> Unit)? = null

    /** Whether moving onto a new item ticks. */
    var haptics: Boolean = true

    /** Icon size at full scale, in dp — the user's setting. The ring's size follows from it. */
    var iconDp: Int = DEFAULT_ICON_DP
        set(value) {
            field = value
            dismiss()
        }

    var items: List<Item> = emptyList()
        set(value) {
            // Own copies: the icons come from the shared IconCache, and the bounds and alpha set
            // here while drawing must not leak into the lists that show the same drawables.
            field = value.map { Item(it.label, it.icon?.constantState?.newDrawable(resources)?.mutate() ?: it.icon) }
            dismiss()
        }

    private val density = resources.displayMetrics.density
    private val edgePx = EDGE_DP * density
    private val deadZonePx = DEAD_ZONE_DP * density

    private var open = false
    private var centerX = 0f
    private var centerY = 0f
    /** Every size below is at this scale — under 1 where the ring had to shrink to fit. */
    private var icon = 0f
    private var gap = 0f
    private var radius = 0f
    private var selected = -1
    private var progress = 0f
    private var animator: ValueAnimator? = null

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scrim)
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.wallpaper_overlay_text)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.wallpaper_overlay_text)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.wallpaper_overlay_text)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.wallpaper_overlay_text)
        textAlign = Paint.Align.CENTER
        textSize = LABEL_SP * resources.displayMetrics.scaledDensity
        setShadowLayer(10f, 0f, 0f, ContextCompat.getColor(context, R.color.wallpaper_overlay_shadow))
    }

    init {
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A sideways slide would otherwise be taken by the home screen's pager.
                parent?.requestDisallowInterceptTouchEvent(true)
                if (items.isNotEmpty() && event.x >= edgePx && event.x <= width - edgePx) {
                    show(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!open) return true
                val index = PieGeometry.indexAt(event.x - centerX, event.y - centerY, items.size, deadZone())
                if (index != selected) {
                    selected = index
                    if (index >= 0 && haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (items.isEmpty()) {
                    onEmpty?.invoke()
                } else {
                    val pick = selected
                    dismiss()
                    if (pick >= 0) onPick?.invoke(pick)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dismiss()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun show(x: Float, y: Float) {
        val fullIcon = iconDp * density
        val fullGap = fullIcon * GAP_RATIO
        val fullRadius = PieGeometry.radius(items.size, fullIcon, fullGap, fullIcon * MIN_RADIUS_RATIO)
        val fit = PieGeometry.fit(
            x, y, width.toFloat(), height.toFloat(),
            reach = outerRadius(fullRadius, fullIcon, fullGap), minScale = MIN_SCALE
        )
        centerX = fit.centerX
        centerY = fit.centerY
        icon = fullIcon * fit.scale
        gap = fullGap * fit.scale
        radius = fullRadius * fit.scale
        selected = -1
        open = true
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OPEN_MILLIS
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** The dark outer layer's edge: it holds the icon band, including a selected icon's growth. */
    private fun outerRadius(ring: Float, icon: Float, gap: Float) = ring + icon * SELECTED_SCALE / 2 + gap

    /** The inner layer's edge: just inside the icon band, so the two layers read as a ring. */
    private fun innerRadius(ring: Float) = (ring - icon / 2 - gap).coerceAtLeast(0f)

    /** Never larger than the inner layer, or a shrunken pie would need a long slide to pick anything. */
    private fun deadZone() = minOf(deadZonePx, innerRadius(radius))

    private fun dismiss() {
        animator?.cancel()
        open = false
        selected = -1
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!open || items.isEmpty()) return
        val r = radius * (OPEN_START_SCALE + (1 - OPEN_START_SCALE) * progress)

        outerPaint.alpha = (OUTER_ALPHA * progress).roundToInt()
        canvas.drawCircle(centerX, centerY, outerRadius(r, icon, gap), outerPaint)
        innerPaint.alpha = (INNER_ALPHA * progress).roundToInt()
        edgePaint.alpha = (EDGE_ALPHA * progress).roundToInt()
        val inner = innerRadius(r)
        canvas.drawCircle(centerX, centerY, inner, innerPaint)
        canvas.drawCircle(centerX, centerY, inner, edgePaint)

        val iconAlpha = (progress * 255).roundToInt()
        highlightPaint.alpha = (HIGHLIGHT_ALPHA * progress).roundToInt()
        items.forEachIndexed { index, item ->
            val angle = PieGeometry.angleOf(index, items.size)
            val x = centerX + r * cos(angle).toFloat()
            val y = centerY + r * sin(angle).toFloat()
            val isSelected = index == selected
            val half = icon * (if (isSelected) SELECTED_SCALE else 1f) / 2
            if (isSelected) canvas.drawCircle(x, y, half + gap / 2, highlightPaint)
            item.icon?.let { drawable ->
                drawable.setBounds((x - half).roundToInt(), (y - half).roundToInt(), (x + half).roundToInt(), (y + half).roundToInt())
                drawable.alpha = iconAlpha
                drawable.draw(canvas)
            }
        }

        items.getOrNull(selected)?.let { item ->
            // Above the pie rather than in its middle, where the finger is; below it when the pie
            // is at the top of the screen.
            val outer = outerRadius(r, icon, gap)
            val above = centerY - outer - labelPaint.textSize / 2
            val y = if (above - labelPaint.textSize > 0) above else centerY + outer + labelPaint.textSize * 1.5f
            canvas.drawText(item.label, centerX, y, labelPaint)
        }
    }

    companion object {
        const val DEFAULT_ICON_DP = 48
        const val MIN_ICON_DP = 32
        const val MAX_ICON_DP = 72

        /** Spacing between neighbouring icons, as a share of the icon size. */
        private const val GAP_RATIO = 0.2f
        /** A pie with few apps keeps at least this radius, as a multiple of the icon size. */
        private const val MIN_RADIUS_RATIO = 1.35f
        /** How far a pie may shrink to fit beside an edge before it is moved off the finger. */
        private const val MIN_SCALE = 0.55f
        private const val EDGE_DP = 28f
        private const val DEAD_ZONE_DP = 28f
        private const val LABEL_SP = 16f
        private const val SELECTED_SCALE = 1.25f
        private const val OPEN_START_SCALE = 0.6f
        private const val OPEN_MILLIS = 140L
        private const val OUTER_ALPHA = 150
        private const val INNER_ALPHA = 36
        private const val EDGE_ALPHA = 70
        private const val HIGHLIGHT_ALPHA = 70
    }
}

/** The ring's arithmetic, kept free of views so it can be tested on its own. */
object PieGeometry {

    /** Where a pie goes, and how much smaller than full size it is drawn. */
    data class Fit(val centerX: Float, val centerY: Float, val scale: Float)

    /** Angle in radians of item [index] of [count]: the first at the top, then clockwise on screen. */
    fun angleOf(index: Int, count: Int): Double = -PI / 2 + index * 2 * PI / count

    /** Smallest radius at which [count] icons of [itemPx] sit [gapPx] apart, but never below [minPx]. */
    fun radius(count: Int, itemPx: Float, gapPx: Float, minPx: Float): Float =
        maxOf(minPx, (count * (itemPx + gapPx) / (2 * PI)).toFloat())

    /**
     * Places a pie that reaches [reach] from its centre at a touch at ([x], [y]) in a [width] x
     * [height] area. It stays centred on the touch and is scaled down until it fits; only once it
     * is at [minScale] and still too big is it moved, just far enough to stay in view.
     */
    fun fit(x: Float, y: Float, width: Float, height: Float, reach: Float, minScale: Float): Fit {
        val room = minOf(x, width - x, y, height - y)
        val scale = (room / reach).coerceIn(minScale, 1f)
        val r = reach * scale
        fun place(value: Float, size: Float) = if (size <= r * 2) size / 2 else value.coerceIn(r, size - r)
        return Fit(place(x, width), place(y, height), scale)
    }

    /**
     * Which of [count] items a finger at ([dx], [dy]) from the centre points at, or -1 inside the
     * [deadZonePx] — the nearest item by angle, however far out the finger has gone.
     */
    fun indexAt(dx: Float, dy: Float, count: Int, deadZonePx: Float): Int {
        if (count <= 0 || hypot(dx, dy) < deadZonePx) return -1
        val fromTop = atan2(dy.toDouble(), dx.toDouble()) + PI / 2
        val step = 2 * PI / count
        val normalized = (fromTop % (2 * PI) + 2 * PI) % (2 * PI)
        return (normalized / step).roundToInt() % count
    }
}
