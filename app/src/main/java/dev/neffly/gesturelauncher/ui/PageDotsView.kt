package dev.neffly.gesturelauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import dev.neffly.gesturelauncher.R
import kotlin.math.abs

/**
 * The home screen's page indicator: one dot per page, the current page's brightest, drawn in the
 * dock's strip so the dots also mark where a drag turns the pages. The emphasis follows the
 * scroll rather than jumping at the end of it, so the dots move with the pages under them.
 *
 * Drawn straight over the wallpaper like the clock, with the same dark halo, which needs a
 * software layer — a shadow layer only reaches hardware-drawn text.
 */
class PageDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var count = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Page index plus the fraction scrolled toward the next one, as the pager reports it. */
    var position = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.wallpaper_overlay_text)
        setShadowLayer(
            SHADOW_RADIUS_DP * density, 0f, 0f,
            ContextCompat.getColor(context, R.color.wallpaper_overlay_shadow)
        )
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        if (count == 0) return
        val gap = GAP_DP * density
        val cx = width / 2f - gap * (count - 1) / 2f
        val cy = height / 2f
        for (i in 0 until count) {
            // 1 on the current page, falling to 0 a page away.
            val weight = (1f - abs(i - position)).coerceIn(0f, 1f)
            paint.alpha = (255 * (REST_ALPHA + (1f - REST_ALPHA) * weight)).toInt()
            canvas.drawCircle(cx + i * gap, cy, (REST_RADIUS_DP + GROWTH_DP * weight) * density, paint)
        }
    }

    private companion object {
        const val GAP_DP = 14f
        const val REST_RADIUS_DP = 3f
        const val GROWTH_DP = 1f
        const val REST_ALPHA = 0.45f
        const val SHADOW_RADIUS_DP = 4f
    }
}
