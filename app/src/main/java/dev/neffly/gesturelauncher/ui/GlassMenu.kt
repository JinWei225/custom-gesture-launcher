package dev.neffly.gesturelauncher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import dev.neffly.gesturelauncher.R
import kotlin.math.roundToInt

/**
 * The launcher's long-press menu: a short list of glyph-and-label rows on a rounded glass card
 * that unfolds from the press, in the user's font.
 *
 * One menu for every long-press in the app — a drawer row, a gesture, a note — so they
 * all look like the same launcher rather than like whichever platform widget was nearest to hand.
 * The card takes the surface it opens over: the drawer's light glass, or the dock's dark glass on
 * the pages drawn straight over the wallpaper.
 */
object GlassMenu {

    /** One row. [tintIcon] is off for an icon that is a picture of its own — an app shortcut's —
     *  rather than a glyph to be drawn in the text colour. */
    class Item(
        val label: CharSequence,
        val icon: Drawable?,
        val tintIcon: Boolean = true,
        val destructive: Boolean = false,
        val onClick: () -> Unit
    )

    /** An [Item] from resources. */
    fun item(
        anchor: View,
        @StringRes label: Int,
        @DrawableRes icon: Int,
        destructive: Boolean = false,
        onClick: () -> Unit
    ): Item = Item(
        anchor.context.getString(label),
        ContextCompat.getDrawable(anchor.context, icon),
        destructive = destructive,
        onClick = onClick
    )

    /**
     * Opens the menu below [anchor], or above it when there is no room below, its [gravity] edge
     * inset by [xOffsetDp] from the anchor's.
     *
     * Placed by hand rather than with showAsDropDown: that one, finding no room below a note at
     * the bottom of its list, scrolls the list to make some and then lets the screen edge cut
     * the last row off. The keyboard, if it is up, is left up — the menu takes focus for the
     * Back key but tells the window manager it has no use for the input method, so the box that
     * was being typed in keeps it, and the page doesn't jump.
     */
    fun show(
        anchor: View,
        items: List<Item>,
        overWallpaper: Boolean = false,
        gravity: Int = Gravity.START,
        xOffsetDp: Int = 0
    ) {
        val context = anchor.context
        val content = LayoutInflater.from(context)
            .inflate(R.layout.menu_glass, null) as LinearLayout
        content.background = ContextCompat.getDrawable(
            context, if (overWallpaper) R.drawable.bg_glass_popup_dark else R.drawable.bg_glass_popup
        )
        val text = if (overWallpaper) {
            ContextCompat.getColor(context, R.color.wallpaper_overlay_text)
        } else {
            themeColor(context, com.google.android.material.R.attr.colorOnSurface)
        }
        val danger = if (overWallpaper) {
            ContextCompat.getColor(context, R.color.wallpaper_overlay_danger)
        } else {
            themeColor(context, com.google.android.material.R.attr.colorError)
        }

        val popup = PopupWindow(
            content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true
        )
        for (item in items) {
            val row = LayoutInflater.from(context).inflate(R.layout.item_glass_menu, content, false)
            val colour = if (item.destructive) danger else text
            row.findViewById<TextView>(R.id.menuLabel).apply {
                this.text = item.label
                setTextColor(colour)
            }
            row.findViewById<ImageView>(R.id.menuIcon).apply {
                setImageDrawable(item.icon)
                ImageViewCompat.setImageTintList(this, if (item.tintIcon) ColorStateList.valueOf(colour) else null)
                visibility = if (item.icon == null) View.GONE else View.VISIBLE
            }
            row.setOnClickListener {
                popup.dismiss()
                item.onClick()
            }
            content.addView(row)
        }
        FontEngine.applyTo(content)

        // The card is the background; the window itself paints nothing and casts no shadow —
        // the platform's shadow renders through a translucent background as a hard rectangle.
        popup.setBackgroundDrawable(null)
        popup.elevation = 0f
        popup.isOutsideTouchable = true
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED

        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        content.measure(unspecified, unspecified)
        val width = content.measuredWidth
        val height = content.measuredHeight
        popup.width = width
        popup.height = height

        // Everything in window coordinates: the anchor's place, and the part of the window not
        // under the keyboard or the system bars. From the insets rather than the "visible display
        // frame", which on this OEM's keyboard stops short of its toolbar.
        val root = anchor.rootView
        val covered = ViewCompat.getRootWindowInsets(anchor)
            ?.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars())
            ?: Insets.NONE
        val room = Rect(covered.left, covered.top, root.width - covered.right, root.height - covered.bottom)
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        val overlap = dp(context, 6)
        val inset = dp(context, xOffsetDp)
        val x = (if (gravity == Gravity.END) at[0] + anchor.width - width - inset else at[0] + inset)
            .coerceIn(room.left, (room.right - width).coerceAtLeast(room.left))
        val below = at[1] + anchor.height - overlap
        val fitsBelow = below + height <= room.bottom
        val y = if (fitsBelow) below else (at[1] - height + overlap).coerceAtLeast(room.top)
        popup.animationStyle = if (fitsBelow) {
            R.style.Animation_GestureLauncher_GlassMenu
        } else {
            R.style.Animation_GestureLauncher_GlassMenu_Up
        }
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    private fun themeColor(context: Context, attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
