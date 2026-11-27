package dev.neffly.gesturelauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
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

    /** Called when a deliberate touch ends on a pie with nothing in it, so the owner can say how
     *  to fill it. */
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
            // The ring can't be aimed without sight, so screen readers get one action per app.
            accessibilityActionIds.forEach { ViewCompat.removeAccessibilityAction(this, it) }
            accessibilityActionIds = value.mapIndexed { index, item ->
                ViewCompat.addAccessibilityAction(this, context.getString(R.string.pie_open_app, item.label)) { _, _ ->
                    onPick?.invoke(index)
                    true
                }
            }
        }

    private var accessibilityActionIds: List<Int> = emptyList()

    private val density = resources.displayMetrics.density
    private val edgePx = EDGE_DP * density
    private val deadZonePx = DEAD_ZONE_DP * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
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

    // The quick-search card's glass, unblurred (see the pie_* note in colors.xml); every colour
    // follows the light or dark theme. Each paint's alpha is scaled by the open animation, so the
    // colour's own alpha is kept to scale from.
    private val outerPaint = fill(R.color.glass_veil_flat)
    private val outerStrokePaint = stroke(R.color.glass_stroke)
    private val innerPaint = fill(R.color.pie_inner)
    private val innerStrokePaint = stroke(R.color.pie_inner_stroke)
    private val highlightPaint = fill(R.color.pie_highlight)
    /** The card's soft outer shadow (bg_card_glow), as rings fading outwards from the edge. */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = GLOW_RING_DP * density
        color = Color.BLACK
    }
    private val paints = listOf(outerPaint, outerStrokePaint, innerPaint, innerStrokePaint, highlightPaint)
    private val baseAlphas = paints.map { it.alpha }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.wallpaper_overlay_text)
        textAlign = Paint.Align.CENTER
        textSize = LABEL_SP * resources.displayMetrics.scaledDensity
        setShadowLayer(10f, 0f, 0f, ContextCompat.getColor(context, R.color.wallpaper_overlay_shadow))
    }

    init {
        isClickable = true
        contentDescription = context.getString(R.string.pie_description)
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = ContextCompat.getColor(context, color)
    }

    private fun stroke(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = ContextCompat.getColor(context, color)
        style = Paint.Style.STROKE
        strokeWidth = density
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                if (items.isNotEmpty() && inReach(event.x)) {
                    // A sideways slide would otherwise be taken by the home screen's pager. Only
                    // once a ring is open: anywhere else a sideways swipe should turn the page.
                    parent?.requestDisallowInterceptTouchEvent(true)
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
                    if (isDeliberate(event)) performClick()
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

    /** An empty pie's only click: saying how to fill it. Also what TalkBack's double-tap reaches. */
    override fun performClick(): Boolean {
        super.performClick()
        if (items.isEmpty()) onEmpty?.invoke()
        return true
    }

    private fun inReach(x: Float) = x >= edgePx && x <= width - edgePx

    /**
     * Whether a touch on an empty pie was an attempt to open it — a press held or slid, away from
     * the edges — rather than a stray tap. Same idea as the gesture canvas ignoring strokes too
     * short to be real attempts. A sideways swipe never gets here: the pager takes it.
     */
    private fun isDeliberate(up: MotionEvent): Boolean =
        inReach(downX) && (
            up.eventTime - up.downTime >= ViewConfiguration.getLongPressTimeout() ||
                hypot(up.x - downX, up.y - downY) > touchSlop
            )

    private fun show(x: Float, y: Float) {
        val fullIcon = iconDp * density
        val fullGap = fullIcon * GAP_RATIO
        val fullRadius = PieGeometry.radius(items.size, fullIcon, fullGap, fullIcon * MIN_RADIUS_RATIO)
        val fit = PieGeometry.fit(
            x, y, width.toFloat(), height.toFloat(),
            // The glow is the outermost thing drawn, so it is what has to fit.
            reach = outerRadius(fullRadius, fullIcon, fullGap) + GLOW_ALPHAS.size * GLOW_RING_DP * density,
            minScale = MIN_SCALE
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

        paints.forEachIndexed { i, paint -> paint.alpha = (baseAlphas[i] * progress).roundToInt() }
        val outer = outerRadius(r, icon, gap)
        val ring = glowPaint.strokeWidth
        GLOW_ALPHAS.forEachIndexed { i, alpha ->
            glowPaint.alpha = (alpha * progress).roundToInt()
            canvas.drawCircle(centerX, centerY, outer + ring * (i + 0.5f), glowPaint)
        }
        canvas.drawCircle(centerX, centerY, outer, outerPaint)
        canvas.drawCircle(centerX, centerY, outer, outerStrokePaint)
        val inner = innerRadius(r)
        canvas.drawCircle(centerX, centerY, inner, innerPaint)
        canvas.drawCircle(centerX, centerY, inner, innerStrokePaint)

        val iconAlpha = (progress * 255).roundToInt()
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
            val above = centerY - outer - labelPaint.textSize / 2
            val y = if (above - labelPaint.textSize > 0) above else centerY + outer + labelPaint.textSize * 1.5f
            canvas.drawText(item.label, centerX, y, labelPaint)
        }
    }

    companion object {
        const val DEFAULT_ICON_DP = 48
        const val MIN_ICON_DP = 32
        const val MAX_ICON_DP = 72
        /** The settings slider's step: every stored size is one of these. */
        const val ICON_STEP_DP = 4

        /** [dp] as a size the settings slider can show: in range, and on a step. */
        fun snapIconDp(dp: Int): Int {
            val steps = ((dp - MIN_ICON_DP) / ICON_STEP_DP.toFloat()).roundToInt()
            return (MIN_ICON_DP + steps * ICON_STEP_DP).coerceIn(MIN_ICON_DP, MAX_ICON_DP)
        }

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
        private const val GLOW_RING_DP = 2f
        /** Innermost ring first — the same steps as bg_card_glow. */
        private val GLOW_ALPHAS = intArrayOf(0x20, 0x16, 0x0E, 0x08)
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
