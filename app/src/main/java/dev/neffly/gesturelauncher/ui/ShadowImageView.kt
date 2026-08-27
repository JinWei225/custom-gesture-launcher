package dev.neffly.gesturelauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import dev.neffly.gesturelauncher.R

/**
 * An ImageView carrying the same soft dark halo the home screen's clock gets from
 * `android:shadowRadius`.
 *
 * Those shadow attributes belong to TextView and have no ImageView counterpart, so an icon placed
 * beside the clock — the battery indicator — sat on the wallpaper with nothing behind it while the
 * text next to it had a shadow. Same white tint, same wallpaper, so it needs the same backing.
 *
 * The halo is built with [Bitmap.extractAlpha], which blurs the image's own alpha channel and hands
 * back the result as a mask. That mask is then filled with the shadow colour and drawn underneath
 * the image. The obvious shortcut — `Paint.setShadowLayer` on the `drawBitmap` call — does not work
 * and was tried on device: a blur mask filter applies to a draw's *coverage*, and an image's
 * coverage is its rectangle, so a shadow layer either does nothing or blurs a box. extractAlpha is
 * the path that actually reads the silhouette.
 *
 * The blur radius and colour are deliberately the literal values the clock's XML uses, so the two
 * shadows stay indistinguishable: BlurMaskFilter and `android:shadowRadius` convert a radius to a
 * Gaussian sigma the same way.
 *
 * The host layout must leave padding around the image for the halo to land in — a parent clips its
 * children, and the blur spreads roughly [SHADOW_RADIUS_PX] beyond the glyph.
 */
class ShadowImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** Fills the extracted alpha mask, which carries no colour of its own. */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.wallpaper_overlay_shadow)
    }

    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(SHADOW_RADIUS_PX, BlurMaskFilter.Blur.NORMAL)
    }

    /** The blurred silhouette and where it sits relative to this view's origin — the mask is
     *  larger than the image it came from, so the offset is negative on both axes. Kept between
     *  draws so nothing is allocated on the drawing path; dropped by [invalidate]. */
    private var shadow: Bitmap? = null
    private val shadowOffset = IntArray(2)

    /** Overridden rather than the various setImage* entry points: ImageView reaches its drawable
     *  through several paths (setImageDrawable, setImageResource, setImageLevel, tinting), and
     *  invalidate() is the one they all share. Dropping the reference is all this costs — the mask
     *  is only rebuilt if the view actually draws again. */
    override fun invalidate() {
        shadow = null
        super.invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shadow = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        shadow = null
    }

    override fun onDraw(canvas: Canvas) {
        val mask = shadow ?: buildShadow()
        if (mask != null) {
            canvas.drawBitmap(
                mask,
                shadowOffset[0].toFloat(),
                shadowOffset[1].toFloat(),
                shadowPaint
            )
        }
        super.onDraw(canvas)
    }

    /**
     * Kept out of [onDraw] so the allocations aren't on the drawing path even lexically. Drawing
     * through super into a same-size bitmap reproduces ImageView's own scale type, padding, tint
     * and drawable level exactly, which hand-drawing the drawable would not — the matrix that
     * positions a fitCenter drawable is ImageView's private one, not the public `imageMatrix`.
     *
     * WrongCall is suppressed because the rule is about calling a *child's* onDraw instead of its
     * draw(); this is our own super, and draw() is not an option here — it would re-enter this
     * class's onDraw and recurse forever.
     */
    @SuppressLint("WrongCall")
    private fun buildShadow(): Bitmap? {
        if (width <= 0 || height <= 0 || drawable == null) return null
        val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        super.onDraw(Canvas(rendered))
        val mask = rendered.extractAlpha(blurPaint, shadowOffset)
        rendered.recycle()
        shadow = mask
        return mask
    }

    private companion object {
        /** Raw pixels, matching `android:shadowRadius="10"` on the clock — that attribute is
         *  unscaled too, so converting to dp here would put the two shadows out of step. */
        const val SHADOW_RADIUS_PX = 10f
    }
}
