package dev.neffly.gesturelauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import dev.neffly.gesturelauncher.data.Prefs

/**
 * Thin side strip of "#, A..Z" glyphs for fast-scrolling a long, alphabetically sorted list.
 * Dragging (or tapping) across it reports the touched glyph via [onLetterSelected]; letters with
 * no matching entry in the current list are rendered dimmed and are not selectable.
 */
class AlphabetIndexView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var onLetterSelected: ((Char) -> Unit)? = null

    /** Fires on every touch position while dragging — including dimmed/inactive letters, unlike
     *  [onLetterSelected] — so a caller can show "you're here" feedback (e.g. a big center-screen
     *  bubble) regardless of whether that letter actually has anything to jump to. Fires with
     *  `null` on release. */
    var onLetterTouched: ((Char?) -> Unit)? = null

    // '#' last: it's the catch-all for digits/symbols/non-Latin labels, and in plain string sort
    // order those mostly land after 'Z' (e.g. CJK code points), not before 'A'.
    private val glyphs: List<Char> = ('A'..'Z') + listOf('#')
    private var activeLetters: Set<Char> = emptySet()
    private var lastReported: Char? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = BASE_TEXT_SIZE_PX
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Set here rather than in init so a font change picked up by an activity recreate lands on
        // the rebuilt view, and so a detach/reattach across a config change can't lose it.
        paint.typeface = FontEngine.typeface() ?: Typeface.DEFAULT
        applyTextSize()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTextSize()
    }

    /**
     * These glyphs go straight onto a Canvas in raw pixels, so unlike every TextView in the app
     * they don't follow the sp scaling the font-size setting works through — the multiplier has to
     * be applied by hand, or the index stays tiny while everything around it grows.
     *
     * Clamped to the strip's own width, which is only known after layout, so a large setting (or a
     * wide imported font) can't spill out of this narrow fixed-width column.
     */
    private fun applyTextSize() {
        val scaled = BASE_TEXT_SIZE_PX * Prefs.fontScale(context)
        paint.textSize = if (width > 0) scaled.coerceAtMost(width * MAX_WIDTH_FRACTION) else scaled
    }

    fun setActiveLetters(letters: Set<Char>) {
        activeLetters = letters
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (height == 0) return true
                val index = ((event.y / height) * glyphs.size).toInt().coerceIn(0, glyphs.size - 1)
                val letter = glyphs[index]
                onLetterTouched?.invoke(letter)
                if (letter in activeLetters && letter != lastReported) {
                    lastReported = letter
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onLetterSelected?.invoke(letter)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastReported = null
                onLetterTouched?.invoke(null)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height == 0 || glyphs.isEmpty()) return
        val rowHeight = height.toFloat() / glyphs.size
        val cx = width / 2f
        val baseColor = currentTextColorTint()
        glyphs.forEachIndexed { i, letter ->
            paint.color = if (letter in activeLetters) baseColor else dim(baseColor)
            val cy = rowHeight * i + rowHeight / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letter.toString(), cx, cy, paint)
        }
    }

    /** Letters with no apps under them are drawn at [DIM_ALPHA] so the index still shows the
     *  full alphabet without implying those rows are reachable. */
    private fun dim(color: Int): Int = (color and 0x00FFFFFF) or DIM_ALPHA

    /** The theme's on-surface colour, resolved per draw so a theme change lands on the next
     *  invalidate rather than being baked in at construction. */
    private fun currentTextColorTint(): Int = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOnSurface, Color.GRAY
    )

    companion object {
        /** ~31% opacity, in the top byte of an ARGB int. */
        private const val DIM_ALPHA = 0x50000000

        /** Glyph size at a 1.0 font scale. Raw pixels, matching how this view has always drawn. */
        private const val BASE_TEXT_SIZE_PX = 26f

        /** Largest share of the strip's width a glyph may occupy. */
        private const val MAX_WIDTH_FRACTION = 0.8f
    }
}
