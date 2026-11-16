package dev.neffly.gesturelauncher.onboarding

import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.skydoves.balloon.Balloon
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.Prefs

/** The guided tours, each shown once until the settings row clears them all. */
enum class Hint { HOME, QUICK_SEARCH, WIDGETS, NOTES }

/**
 * A run of [TourBalloon] pop-ups, one at a time, each pointing at the control it explains.
 *
 * Finishing it, skipping it (its Skip button or Back) or a button that leaves for another screen
 * for good marks its [hint] seen. A button that leaves *and comes back* — [Move.Open] with
 * `resume` — has the owner call [resumeIfPending] on return, and it picks up at the next step.
 * Anything else that closes it, such as the activity going away, leaves it unseen, so it simply
 * runs again next time.
 */
class Tour(
    private val activity: AppCompatActivity,
    private val hint: Hint,
    /** Run before each step: whatever puts its anchors on screen. */
    private val beforeStep: () -> Unit = {},
    /** Run once the tour is over, however it ended. */
    private val onEnd: () -> Unit = {},
) {

    sealed interface Move {
        data object Next : Move
        data object End : Move
        /** Opens another screen; [resume] carries on at the next step on return, else it ends. */
        class Open(val resume: Boolean, val open: () -> Unit) : Move
    }

    class Choice(@StringRes val label: Int, val move: Move)

    class Step(
        val anchor: View,
        val placement: Placement,
        val backdrop: Backdrop,
        val title: CharSequence,
        val body: CharSequence,
        val primary: Choice,
        val secondary: Choice? = null,
    )

    private var steps: List<Step> = emptyList()
    private var index = 0
    private var balloon: Balloon? = null
    /** The step to show when the owner comes back from a screen a step opened. */
    private var pendingIndex: Int? = null

    /** Back skips the tour while a step is up, rather than reaching the screen's own handling. */
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            balloon?.dismiss()
            end()
        }
    }.also { activity.onBackPressedDispatcher.addCallback(activity, it) }

    val isDue: Boolean get() = !Prefs.hintSeen(activity, hint.name)

    val isShowing: Boolean get() = balloon?.isShowing == true

    /** True between a step's button leaving for another screen and [resumeIfPending]. */
    val isPending: Boolean get() = pendingIndex != null

    fun start(steps: List<Step>) {
        if (isShowing || steps.isEmpty()) return
        this.steps = steps
        pendingIndex = null
        show(0)
    }

    /** Picks the tour up where a step's button left it; false if nothing was waiting. */
    fun resumeIfPending(): Boolean {
        val resumeAt = pendingIndex ?: return false
        pendingIndex = null
        show(resumeAt)
        return true
    }

    /** The anchors moved (rotation, a window resize): put the current step back over them. */
    fun relayout() {
        if (!isShowing) return
        balloon?.dismiss()
        steps[index].anchor.post { show(index) }
    }

    private fun show(position: Int) {
        if (position >= steps.size) {
            end()
            return
        }
        index = position
        beforeStep()
        val step = steps[position]
        balloon = TourBalloon.show(
            activity,
            step.anchor,
            step.placement,
            step.backdrop,
            TourCard(
                title = step.title,
                body = step.body,
                primary = action(step.primary),
                secondary = step.secondary?.let(::action),
                // A lone tip has no "1 of 1" to tell.
                counter = if (steps.size > 1) activity.getString(R.string.tour_counter, position + 1, steps.size) else null,
            ),
        )
        back.isEnabled = true
    }

    private fun action(choice: Choice) = TourCard.Action(activity.getText(choice.label)) {
        when (val move = choice.move) {
            Move.Next -> show(index + 1)
            Move.End -> end()
            is Move.Open -> {
                if (move.resume) {
                    pendingIndex = index + 1
                    back.isEnabled = false
                    balloon = null
                } else {
                    end()
                }
                move.open()
            }
        }
    }

    private fun end() {
        Prefs.markHintSeen(activity, hint.name)
        back.isEnabled = false
        balloon = null
        pendingIndex = null
        onEnd()
    }
}
