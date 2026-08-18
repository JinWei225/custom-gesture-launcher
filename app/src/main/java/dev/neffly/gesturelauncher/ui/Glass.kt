package dev.neffly.gesturelauncher.ui

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.content.ContextCompat
import dev.neffly.gesturelauncher.R
import java.util.function.Consumer

/**
 * The frosted-glass treatment the drawer and the quick-search window share.
 *
 * Android can only genuinely blur what is behind a window from API 31, and even there the effect
 * is a system-controlled privilege: it's off while battery saver is on, off on devices that never
 * enable cross-window blur, and can be revoked at runtime. So this is deliberately not "apply a
 * blur and hope" — [frost] asks for the blur, then paints a veil chosen to match the answer:
 *
 *  - blur granted  -> the light veil ([R.color.glass_veil]); the wallpaper stays readable as
 *                     colour and shape behind the content, which is the whole point of the look.
 *  - blur refused  -> the heavy veil ([R.color.glass_veil_flat]); a sharp wallpaper behind list
 *                     text is a legibility problem, not a style, so cover wins over effect.
 *
 * The listener keeps the two in step: the system toggles cross-window blur while a window is up
 * (entering battery saver is the common one), and without this the drawer would be left showing a
 * thin veil over a suddenly un-blurred wallpaper.
 */
object Glass {

    /** Matches the mock's "balanced" rung on its subtle/balanced/bold ladder. */
    private const val BLUR_RADIUS_PX = 60

    /**
     * Frosts [window] and hands the matching veil colour to [paintVeil], now and again whenever
     * the system's answer changes. The caller applies it — a plain view takes it as a background
     * colour, a MaterialCardView as its card colour (assigning a background there would throw
     * away the shape drawable that draws its corners and stroke).
     *
     * [anchor] only scopes the listener's lifetime; it is normally the view being painted.
     * Safe to call on any API level — below 31 it just yields the heavy veil, once.
     */
    fun frost(window: Window, anchor: View, paintVeil: (Int) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            paintVeil(veil(anchor, blurred = false))
            return
        }
        // FLAG_BLUR_BEHIND is what makes blurBehindRadius take effect; the radius alone is inert.
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = BLUR_RADIUS_PX }

        val manager = anchor.context.getSystemService(WindowManager::class.java)
        if (manager == null) {
            paintVeil(veil(anchor, blurred = false))
            return
        }
        paintVeil(veil(anchor, blurred = manager.isCrossWindowBlurEnabled))

        // The listener holds the view, which holds the activity, and WindowManager outlives both —
        // so it is tied to the anchor's own attach/detach rather than left to be collected.
        val listener = Consumer<Boolean> { enabled -> paintVeil(veil(anchor, enabled)) }
        anchor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                manager.addCrossWindowBlurEnabledListener(v.context.mainExecutor, listener)
            }

            override fun onViewDetachedFromWindow(v: View) {
                manager.removeCrossWindowBlurEnabledListener(listener)
            }
        })
        if (anchor.isAttachedToWindow) {
            manager.addCrossWindowBlurEnabledListener(anchor.context.mainExecutor, listener)
        }
    }

    private fun veil(view: View, blurred: Boolean): Int = ContextCompat.getColor(
        view.context,
        if (blurred) R.color.glass_veil else R.color.glass_veil_flat
    )
}
