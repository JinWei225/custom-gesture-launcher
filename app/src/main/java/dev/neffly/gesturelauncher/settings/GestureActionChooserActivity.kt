package dev.neffly.gesturelauncher.settings

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.google.android.material.appbar.MaterialToolbar
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.GestureAction
import dev.neffly.gesturelauncher.ui.BaseActivity

/**
 * First step when adding a brand-new gesture: "what should this gesture do?" Reached from
 * [GestureSettingsActivity]'s "+" button. Slides in from the right and back out on close — the
 * same translucent-window + self-driven-animation technique [SettingsHubActivity] uses (see its
 * doc comment for why: OEM skins otherwise replace the requested transition with their own
 * "app opening" zoom). Each row hands off to the next screen without finishing itself, so Back
 * unwinds the whole add-gesture chain naturally.
 */
class GestureActionChooserActivity : BaseActivity() {

    private lateinit var chooserRoot: View
    private var isClosing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        setContentView(R.layout.activity_gesture_action_chooser)

        chooserRoot = findViewById(R.id.chooserRoot)
        chooserRoot.translationX = resources.displayMetrics.widthPixels.toFloat()
        chooserRoot.animate()
            .translationX(0f)
            .setDuration(SLIDE_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.pickAppRow).setOnClickListener {
            goTo(GestureTrainingActivity.newGestureIntent(this, GestureAction.LAUNCH_APP))
        }
        findViewById<View>(R.id.openDrawerRow).setOnClickListener {
            goTo(GestureTrainingActivity.newGestureIntent(this, GestureAction.OPEN_DRAWER))
        }
        findViewById<View>(R.id.openUrlRow).setOnClickListener {
            goTo(Intent(this, GestureUrlEntryActivity::class.java))
        }
    }

    /** Chains forward to the next screen, suppressing the OS's default cross-activity transition
     *  the same way this screen's own open/close is suppressed above (on U+; the destination
     *  activity's own onCreate handles that side on U+, this call covers pre-U+). */
    private fun goTo(intent: Intent) {
        startActivity(intent)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(0, 0)
        }
    }

    override fun finish() {
        if (isClosing || isFinishing) { super.finish(); return }
        isClosing = true
        chooserRoot.animate()
            .translationX(resources.displayMetrics.widthPixels.toFloat())
            .setDuration(SLIDE_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { super.finish() }
            .start()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val SLIDE_DURATION_MS = 260L
    }
}
