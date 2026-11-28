package dev.neffly.gesturelauncher.onboarding

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.inSpans
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.GestureStore
import dev.neffly.gesturelauncher.data.LaunchStyle
import dev.neffly.gesturelauncher.data.PieStore
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.settings.GestureActionChooserActivity
import dev.neffly.gesturelauncher.settings.GestureSettingsActivity
import dev.neffly.gesturelauncher.settings.PieAppsActivity
import dev.neffly.gesturelauncher.settings.SettingsHubActivity
import dev.neffly.gesturelauncher.ui.overrideNextTransition

/**
 * The walk through the home screen everyone gets once: a pop-up at a time, each pointing at the
 * control it explains, ending on the screen where the first gesture is made.
 *
 * Built from the settings at the moment it starts, so it only talks about the pages that are on
 * and skips the floating-search step for someone who already uses it. The floating-search step's
 * button opens settings, and the tour picks up at the next step when home comes back.
 */
class HomeTour(
    private val activity: AppCompatActivity,
    private val canvasFrame: View,
    private val searchButton: View,
    private val dockStrip: View,
    private val clockPanel: View,
    /** Brings the pager back to the home page, where every anchor is. */
    showHomePage: () -> Unit,
) {

    private val tour = Tour(activity, Hint.HOME, beforeStep = showHomePage)

    val isShowing: Boolean get() = tour.isShowing

    /** Starts the tour if it's due, or picks it up where a step's button left it. Posted, so the
     *  anchors have been laid out by the time it measures them. */
    fun onResume() {
        if (tour.isShowing || (!tour.isPending && !tour.isDue)) return
        canvasFrame.post {
            if (tour.isShowing) return@post
            if (!tour.resumeIfPending(buildSteps()) && tour.isDue) tour.start(buildSteps())
        }
    }

    fun relayout() = tour.relayout()

    private fun buildSteps(): List<Tour.Step> = buildList {
        val skip = Tour.Choice(R.string.tour_skip, Tour.Move.End)
        val next = Tour.Choice(R.string.tour_next, Tour.Move.Next)

        add(Tour.Step(canvasFrame, Placement.CENTER, Backdrop.DIM,
            text(R.string.tour_welcome_title), text(R.string.tour_welcome_body),
            Tour.Choice(R.string.tour_start, Tour.Move.Next), skip))

        val pie = Prefs.launchStyle(activity) == LaunchStyle.PIE
        add(Tour.Step(canvasFrame, Placement.CENTER, Backdrop.SPOT_ROUND_RECT,
            text(if (pie) R.string.tour_pie_title else R.string.tour_draw_title),
            text(if (pie) R.string.tour_pie_body else R.string.tour_draw_body), next, skip))

        add(Tour.Step(searchButton, Placement.ABOVE, Backdrop.SPOT_CIRCLE,
            text(R.string.tour_search_title),
            withEmphasis(R.string.tour_search_body, R.string.tour_search_hold), next, skip))

        val pages = when {
            Prefs.widgetsPage(activity) && Prefs.notesPage(activity) -> R.string.tour_pages_both
            Prefs.widgetsPage(activity) -> R.string.tour_pages_widgets
            Prefs.notesPage(activity) -> R.string.tour_pages_notes
            else -> null
        }
        val voice = Prefs.voiceEnabled(activity)
        val dockBody = when {
            pages != null && voice -> "${text(pages)} ${text(R.string.tour_voice_body)}"
            pages != null -> text(pages)
            else -> text(R.string.tour_voice_body)
        }
        if (pages != null || voice) {
            add(Tour.Step(dockStrip, Placement.ABOVE, Backdrop.SPOT_ROUND_RECT,
                text(if (pages != null) R.string.tour_pages_title else R.string.tour_voice_title),
                dockBody, next, skip))
        }

        add(Tour.Step(clockPanel, Placement.BELOW, Backdrop.SPOT_ROUND_RECT,
            text(R.string.tour_clock_title), text(R.string.tour_clock_body), next, skip))

        if (!Prefs.quickSearchEnabled(activity)) {
            add(Tour.Step(searchButton, Placement.ABOVE, Backdrop.SPOT_CIRCLE,
                text(R.string.tour_anywhere_title),
                withEmphasis(R.string.tour_anywhere_body, R.string.tour_anywhere_hold),
                Tour.Choice(R.string.tour_set_up, Tour.Move.Open(resume = true) {
                    activity.startActivity(
                        Intent(activity, SettingsHubActivity::class.java)
                            .putExtra(SettingsHubActivity.EXTRA_HIGHLIGHT, SettingsHubActivity.HIGHLIGHT_QUICK_SEARCH)
                    )
                    activity.overrideNextTransition()
                }),
                Tour.Choice(R.string.tour_not_now, Tour.Move.Next)))
        }

        if (pie) {
            val empty = PieStore.all(activity).isEmpty()
            add(Tour.Step(canvasFrame, Placement.CENTER, Backdrop.DIM,
                text(if (empty) R.string.tour_first_pie_title else R.string.tour_pie_apps_title),
                text(if (empty) R.string.tour_first_pie_body else R.string.tour_pie_apps_body),
                Tour.Choice(if (empty) R.string.pie_add_apps else R.string.tour_open, Tour.Move.Open(resume = false) {
                    activity.startActivity(Intent(activity, PieAppsActivity::class.java))
                }),
                Tour.Choice(if (empty) R.string.tour_later else R.string.tour_done, Tour.Move.End)))
        } else if (GestureStore.all(activity).isEmpty()) {
            add(Tour.Step(canvasFrame, Placement.CENTER, Backdrop.DIM,
                text(R.string.tour_first_gesture_title), text(R.string.tour_first_gesture_body),
                Tour.Choice(R.string.tour_add_gesture, Tour.Move.Open(resume = false) {
                    activity.startActivity(Intent(activity, GestureActionChooserActivity::class.java))
                    activity.overrideNextTransition()
                }),
                Tour.Choice(R.string.tour_later, Tour.Move.End)))
        } else {
            add(Tour.Step(canvasFrame, Placement.CENTER, Backdrop.DIM,
                text(R.string.tour_gestures_title), text(R.string.tour_gestures_body),
                Tour.Choice(R.string.tour_open, Tour.Move.Open(resume = false) {
                    activity.startActivity(Intent(activity, GestureSettingsActivity::class.java))
                }),
                Tour.Choice(R.string.tour_done, Tour.Move.End)))
        }
    }

    private fun text(@StringRes id: Int): CharSequence = activity.getText(id)

    private fun withEmphasis(@StringRes body: Int, @StringRes emphasis: Int): CharSequence =
        activity.withEmphasis(body, emphasis)
}

/** A tip's body, then a bold line set apart under it: the one thing it must not let slip. */
fun Context.withEmphasis(@StringRes body: Int, @StringRes emphasis: Int): CharSequence =
    SpannableStringBuilder(getText(body))
        .append("\n\n")
        .inSpans(StyleSpan(Typeface.BOLD)) { append(getText(emphasis)) }
