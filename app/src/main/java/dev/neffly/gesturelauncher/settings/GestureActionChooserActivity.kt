package dev.neffly.gesturelauncher.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.google.android.material.appbar.MaterialToolbar
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.GestureAction
import dev.neffly.gesturelauncher.ui.SlidePanelActivity
import dev.neffly.gesturelauncher.ui.overrideNextTransition
import dev.neffly.gesturelauncher.ui.overrideOwnTransitions

/**
 * First step when adding a brand-new gesture: "what should this gesture do?" Reached from
 * [GestureSettingsActivity]'s "+" button. Slides in from the right and back out on close — the
 * same translucent-window + self-driven-animation technique [SettingsHubActivity] uses (see its
 * doc comment for why: OEM skins otherwise replace the requested transition with their own
 * "app opening" zoom). Each row hands off to the next screen without finishing itself, so Back
 * unwinds the whole add-gesture chain naturally.
 */
class GestureActionChooserActivity : SlidePanelActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideOwnTransitions()
        setContentView(R.layout.activity_gesture_action_chooser)

        slideIn(findViewById(R.id.chooserRoot), savedInstanceState)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.pickAppRow).setOnClickListener {
            goTo(GestureTrainingActivity.newGestureIntent(this, GestureAction.LAUNCH_APP))
        }
        findViewById<View>(R.id.openDrawerRow).setOnClickListener {
            goTo(GestureTrainingActivity.newGestureIntent(this, GestureAction.OPEN_DRAWER))
        }
        findViewById<View>(R.id.quickSearchRow).setOnClickListener {
            goTo(GestureTrainingActivity.newGestureIntent(this, GestureAction.QUICK_SEARCH))
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
        overrideNextTransition()
    }
}
