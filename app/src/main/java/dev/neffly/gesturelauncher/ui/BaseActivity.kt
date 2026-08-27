package dev.neffly.gesturelauncher.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.neffly.gesturelauncher.data.Prefs

/**
 * Applies the user's font (see [FontEngine]) to every screen, and recreates a screen that's still
 * showing the old one.
 *
 * Every UI activity extends this. [dev.neffly.gesturelauncher.crash.SafeModeActivity] deliberately
 * does not: it's the escape hatch shown after repeated crashes, so it must render with no
 * dependency on a user-supplied font file.
 */
abstract class BaseActivity : AppCompatActivity() {

    private var fontVersion = FontEngine.version

    /**
     * Whether this screen follows the user's font-size multiplier.
     *
     * True everywhere except the floating quick-search window, which is a fixed panel over someone
     * else's app rather than a page of ours — see the note on its override.
     *
     * A getter rather than a stored property on purpose: [attachBaseContext] runs during the
     * framework's `Activity.attach`, and a getter cannot be caught out by initialisation order the
     * way a backing field could.
     */
    protected open val appliesFontScale: Boolean get() = true

    /**
     * Applies the user's font-size multiplier by overriding this activity's Configuration, which
     * makes every `sp` dimension in the app scale at once — layouts, dialogs, menus, the clock.
     * Doing it here rather than by walking views and multiplying textSize means nothing can be
     * missed, and `dp` sizes (icons, paddings, the drawer button) correctly stay put.
     *
     * It is also the only place a screen can opt out of the multiplier wholesale, which is why
     * [appliesFontScale] lives here: skipping the override leaves the device's own configuration
     * untouched, so such a screen still honours the system text size while ignoring ours.
     */
    override fun attachBaseContext(newBase: Context) {
        val scale = if (appliesFontScale) Prefs.fontScale(newBase) else 1f
        if (scale == 1f) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration)
        // Multiplied, not replaced: the device's own accessibility text-size setting still counts.
        config.fontScale *= scale
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fontVersion = FontEngine.version
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applyFontToContent()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        applyFontToContent()
    }

    override fun onResume() {
        super.onResume()
        // The settings hub recreates itself when the font changes, but everything below it in the
        // stack is still built against the old typeface — MainActivity especially, which is
        // singleTask and survives indefinitely underneath.
        if (fontVersion != FontEngine.version) recreate()
    }

    /** Catches views a widget builds internally rather than inflating — most visibly
     *  MaterialToolbar's title, which is created inside Toolbar.setTitle(). */
    private fun applyFontToContent() {
        if (!FontEngine.isCustom()) return
        FontEngine.applyTo(findViewById<View>(android.R.id.content))
    }
}

/**
 * [AlertDialog.Builder.show] plus the user's font. A dialog's own title, message and buttons are
 * built by AlertController from its own themed context, so they aren't reachable from the
 * activity's content view.
 *
 * The font is applied on every layout pass rather than once on show, because a dialog built with
 * setItems/setSingleChoiceItems fills its list from an adapter during layout — which happens after
 * onShow — so a single pass would style the title and buttons but leave the rows behind. Re-running
 * it is cheap and idempotent: TextView.setTypeface no-ops when the typeface is already the one
 * being set, so this can't drive a layout loop.
 */
fun AlertDialog.Builder.showWithFont(): AlertDialog = create().also { dialog ->
    if (!FontEngine.isCustom()) {
        dialog.show()
        return@also
    }
    // The decor view is only reached from inside the listeners: asking for it before show() would
    // force the window to install its decor early, ahead of the setup Dialog does on its way up.
    var observer: ViewTreeObserver? = null
    val onLayout = ViewTreeObserver.OnGlobalLayoutListener {
        dialog.window?.decorView?.let(FontEngine::applyTo)
    }
    dialog.setOnShowListener {
        val decor = dialog.window?.decorView ?: return@setOnShowListener
        FontEngine.applyTo(decor)
        observer = decor.viewTreeObserver.also { it.addOnGlobalLayoutListener(onLayout) }
    }
    dialog.setOnDismissListener {
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(onLayout)
    }
    dialog.show()
}
