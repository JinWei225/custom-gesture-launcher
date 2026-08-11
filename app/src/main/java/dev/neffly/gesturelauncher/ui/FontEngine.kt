package dev.neffly.gesturelauncher.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.Log
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.textfield.TextInputLayout
import dev.neffly.gesturelauncher.data.FontStore
import dev.neffly.gesturelauncher.data.Prefs

/**
 * The user's chosen typeface, loaded once per process and stamped onto text as it's created.
 *
 * The obvious implementation is a LayoutInflater.Factory2 (the Calligraphy/ViewPump approach), and
 * it was rejected: AppCompat's `delegate.createView` returns null for every fully-qualified name —
 * which is all our Material widgets plus TextClock — so the factory only actually covers anything
 * if you add a reflective view-instantiation fallback, and that fallback has to dodge
 * fragment/include/merge and can only ever run inside inflate(). It also has to be installed before
 * super.onCreate() or it silently costs AppCompat's widget substitution. For a codebase this size,
 * walking the handful of places views are created is complete coverage at a fraction of the risk.
 *
 * Those places are: [BaseActivity.setContentView], the two RecyclerView adapters'
 * onCreateViewHolder, [showWithFont] for dialogs, [applyTo] over a [Menu] for popup/toolbar menus,
 * MainActivity's programmatically built event rows, and AlphabetIndexView's raw Paint.
 *
 * Every entry point is a no-op when no custom font is set, so the default path is byte-for-byte
 * what the app did before this existed.
 */
object FontEngine {

    private const val TAG = "FontEngine"

    @Volatile
    private var typeface: Typeface? = null

    /** Bumped whenever text appearance changes — the typeface here, or the scale in
     *  [Prefs.fontScale] — so live activities can notice in onResume and recreate. Cheaper and
     *  less error-prone than tracking every started activity. */
    @Volatile
    var version: Int = 0
        private set

    /** Marks every live screen as stale without touching the typeface — for the font-size setting,
     *  which is applied through the activity's Configuration rather than through a Typeface. */
    fun notifyScaleChanged() {
        version++
    }

    fun typeface(): Typeface? = typeface

    fun isCustom(): Boolean = typeface != null

    /**
     * Loads the installed font, if any. Called synchronously from App.onCreate, before any window
     * exists — the same reasoning as the setDefaultNightMode call beside it: doing this later, on a
     * background thread, would race the first setContentView and show a visible font swap on the
     * home screen. It costs one read of the prefs file that call already touched, and for users
     * with no custom font that's the entire cost.
     */
    fun init(context: Context) {
        if (Prefs.fontName(context) == null) return
        // Same safety-net philosophy as the rest of Prefs: if the app is crash-looping, stop doing
        // the unusual thing. A font that parses at import but faults during rasterisation would
        // otherwise take down every screen with no way back in.
        if (Prefs.shouldEnterSafeMode(context)) {
            Log.w(TAG, "in safe mode; ignoring the custom font")
            return
        }
        typeface = runCatching { Typeface.createFromFile(FontStore.file(context)) }
            .onFailure { Log.w(TAG, "could not load the custom font", it) }
            .getOrNull()
            ?.takeIf { it != Typeface.DEFAULT }
    }

    /** Swaps the active typeface (null = system font) and marks live activities as stale. */
    fun set(newTypeface: Typeface?) {
        typeface = newTypeface
        version++
    }

    /** One view, no recursion — for callers that build a single TextView themselves. */
    fun applyToSelf(view: View) {
        val tf = typeface ?: return
        when (view) {
            // The layout draws its own hint/error/counter text, so setting the child EditText
            // alone leaves those in the system font.
            is TextInputLayout -> view.typeface = tf
            // setTypeface(tf, style) rather than the property setter: it preserves
            // android:textStyle="bold" (the clock, the drawer's letter bubble) by synthesizing
            // bold when the user's file is a single weight.
            is TextView -> view.setTypeface(tf, view.typeface?.style ?: Typeface.NORMAL)
        }
    }

    /** [view] and everything under it. */
    fun applyTo(view: View) {
        if (typeface == null) return
        applyToSelf(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyTo(view.getChildAt(i))
        }
    }

    /**
     * Menu item titles. Menus can't be reached by walking a view tree from the activity — their
     * rows are inflated into a separate popup window by the popup's own inflater — so the typeface
     * rides along in the title text itself as a span.
     */
    fun applyTo(menu: Menu) {
        val tf = typeface ?: return
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val title = item.title ?: continue
            item.title = SpannableString(title).apply {
                setSpan(TypefaceSpan(tf), 0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            }
            item.subMenu?.let { applyTo(it) }
        }
    }

    /** android.text.style.TypefaceSpan only accepts a Typeface from API 28; minSdk here is 26. */
    private class TypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) = applyTypeface(paint)
        override fun updateMeasureState(paint: TextPaint) = applyTypeface(paint)
        private fun applyTypeface(paint: TextPaint) {
            // Keep whatever style the paint already had, so a bold menu title stays bold.
            paint.typeface = Typeface.create(typeface, paint.typeface?.style ?: Typeface.NORMAL)
        }
    }
}
