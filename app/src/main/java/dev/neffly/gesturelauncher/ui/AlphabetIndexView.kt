package dev.neffly.gesturelauncher.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

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
        textSize = 26f
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

    private fun dim(color: Int): Int = (color and 0x00FFFFFF) or 0x50000000

    private fun currentTextColorTint(): Int {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) Color.WHITE else Color.BLACK
    }
}
