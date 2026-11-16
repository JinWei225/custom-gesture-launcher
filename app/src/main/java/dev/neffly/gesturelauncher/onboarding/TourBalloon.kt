package dev.neffly.gesturelauncher.onboarding

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.skydoves.balloon.ArrowOrientation
import com.skydoves.balloon.ArrowPositionRules
import com.skydoves.balloon.Balloon
import com.skydoves.balloon.BalloonAnimation
import com.skydoves.balloon.overlay.BalloonOverlayCircle
import com.skydoves.balloon.overlay.BalloonOverlayRoundRect
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.ui.FontEngine
import kotlin.math.max
import kotlin.math.min

/** What one pop-up says, and the one or two buttons under it. */
class TourCard(
    val title: CharSequence,
    val body: CharSequence,
    val primary: Action,
    val secondary: Action? = null,
    /** "2 of 7" for a tour step; null for a hint on its own. */
    val counter: CharSequence? = null,
) {
    class Action(val label: CharSequence, val onClick: () -> Unit)
}

/** Where the pop-up sits relative to the view it is about. */
enum class Placement { ABOVE, BELOW, CENTER }

/**
 * What happens to the rest of the screen while the pop-up is up. Anything but [NONE] dims it and
 * takes its touches, so only the pop-up's buttons (and Back, which the caller handles) move on;
 * [NONE] leaves the screen as it is and lets a touch elsewhere close the pop-up.
 */
enum class Backdrop { NONE, DIM, SPOT_CIRCLE, SPOT_ROUND_RECT }

/**
 * The launcher's guide pop-up: a dark glass card pointing at a view, in the user's font. One look
 * for the home-screen tour and for the hints that come after it, wherever they open.
 */
object TourBalloon {

    fun show(
        activity: AppCompatActivity,
        anchor: View,
        placement: Placement,
        backdrop: Backdrop,
        card: TourCard,
    ): Balloon {
        val density = activity.resources.displayMetrics.density
        // The width covers the margins too, so the card stays a gutter clear of both screen edges.
        val widthDp = min(activity.resources.configuration.screenWidthDp, MAX_WIDTH_DP + 2 * GUTTER_DP)
        val modal = backdrop != Backdrop.NONE
        val balloon = Balloon.Builder(activity)
            .setLayout(R.layout.tour_card)
            .setWidth(widthDp)
            .setBackgroundDrawableResource(R.drawable.bg_tour_card)
            // The popup is pushed against the screen edge when its anchor sits in a corner; the
            // margin keeps the card itself off that edge.
            .setMarginHorizontal(GUTTER_DP)
            .setIsVisibleArrow(placement != Placement.CENTER)
            // In the card's outline colour: the fill's own near-black vanishes against the dim.
            .setArrowColorResource(R.color.wallpaper_glass_stroke)
            .setArrowSize(ARROW_DP)
            .setArrowOrientation(if (placement == Placement.ABOVE) ArrowOrientation.BOTTOM else ArrowOrientation.TOP)
            .setArrowPositionRules(ArrowPositionRules.ALIGN_ANCHOR)
            .setBalloonAnimation(BalloonAnimation.FADE)
            .setLifecycleOwner(activity)
            // Balloon's default is a focusable window, which swallows the touch that closes it —
            // a tap on the row a hint points at would only close the hint — and takes Back away
            // from the activity, where the tour handles it.
            .setFocusable(false)
            .setDismissWhenClicked(false)
            .setDismissWhenTouchOutside(!modal)
            .setDismissWhenOverlayClicked(false)
            .setIsVisibleOverlay(modal)
            .setOverlayColorResource(R.color.tour_scrim)
            .apply {
                when (backdrop) {
                    Backdrop.NONE -> {}
                    // A cut-out of no size: the whole screen dims evenly.
                    Backdrop.DIM -> setOverlayShape(BalloonOverlayCircle(0f))
                    Backdrop.SPOT_CIRCLE -> {
                        setOverlayShape(BalloonOverlayCircle(max(anchor.width, anchor.height) / 2f + SPOT_PADDING_DP * density))
                    }
                    Backdrop.SPOT_ROUND_RECT -> {
                        setOverlayPadding(SPOT_PADDING_DP.toFloat())
                        setOverlayShape(BalloonOverlayRoundRect(SPOT_RADIUS_DP * density, SPOT_RADIUS_DP * density))
                    }
                }
            }
            .build()

        val content = balloon.getContentView()
        content.findViewById<TextView>(R.id.tourCounter).apply {
            text = card.counter
            visibility = if (card.counter == null) View.GONE else View.VISIBLE
        }
        content.findViewById<TextView>(R.id.tourTitle).text = card.title
        content.findViewById<TextView>(R.id.tourBody).text = card.body
        bind(content.findViewById(R.id.tourPrimary), card.primary, balloon)
        bind(content.findViewById(R.id.tourSecondary), card.secondary, balloon)
        FontEngine.applyTo(content)

        when (placement) {
            Placement.ABOVE -> balloon.showAlignTop(anchor)
            Placement.BELOW -> balloon.showAlignBottom(anchor)
            Placement.CENTER -> balloon.showAtCenter(anchor)
        }
        return balloon
    }

    /** Closes the pop-up before acting, so whatever the button opens isn't drawn under it. */
    private fun bind(button: MaterialButton, action: TourCard.Action?, balloon: Balloon) {
        if (action == null) {
            button.visibility = View.GONE
            return
        }
        button.text = action.label
        button.setOnClickListener {
            balloon.dismiss()
            action.onClick()
        }
    }

    /** Clear of the screen edges on a phone. */
    private const val GUTTER_DP = 16
    /** Keeps the card readable rather than stretched on a tablet. */
    private const val MAX_WIDTH_DP = 360
    private const val ARROW_DP = 12
    private const val SPOT_PADDING_DP = 6
    private const val SPOT_RADIUS_DP = 20
}
