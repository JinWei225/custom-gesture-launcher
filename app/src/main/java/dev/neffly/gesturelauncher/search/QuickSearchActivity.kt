package dev.neffly.gesturelauncher.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.drawer.AppInfo
import dev.neffly.gesturelauncher.drawer.AppListAdapter
import dev.neffly.gesturelauncher.drawer.AppRepository
import dev.neffly.gesturelauncher.launch.FloatingWindow
import dev.neffly.gesturelauncher.settings.SettingsHubActivity
import dev.neffly.gesturelauncher.ui.BaseActivity
import dev.neffly.gesturelauncher.ui.Glass
import dev.neffly.gesturelauncher.ui.MaxHeightRecyclerView
import dev.neffly.gesturelauncher.ui.SwipeToFloat
import dev.neffly.gesturelauncher.ui.overrideOwnTransitions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The floating search bar, shown over whatever app is in front.
 *
 * Reached by long-pressing the power button, which is only possible via the digital-assistant role:
 * Android never delivers KEYCODE_POWER to an app, so ACTION_ASSIST (declared in the manifest) is
 * the supported hook. Also reachable from a home-screen gesture.
 *
 * A translucent activity rather than a SYSTEM_ALERT_WINDOW overlay: no extra permission, keyboard
 * focus and IME insets work the way they do everywhere else, and ACTION_ASSIST arrives as an
 * activity intent anyway. Extending [BaseActivity] is what gives it the user's imported typeface
 * and font-size multiplier for free.
 *
 * Results come from the same [SearchController] the app drawer uses, so ranking, aliases, file
 * search and the web row behave identically in both places.
 */
class QuickSearchActivity : BaseActivity() {

    private lateinit var card: MaterialCardView
    private lateinit var searchLayout: TextInputLayout
    private lateinit var searchInput: TextInputEditText
    private lateinit var resultList: MaxHeightRecyclerView
    private lateinit var cardDivider: View
    private lateinit var cardGlow: View
    private lateinit var emptyLabel: TextView
    private lateinit var adapter: AppListAdapter
    private lateinit var search: SearchController

    private var renderedQuery: String? = null

    /** Whether the user has dragged the list since the query last changed — see [renderResults]. */
    private var userScrolled = false

    /** Top of whatever currently obstructs the bottom of the screen — the keyboard when one is
     *  up, the navigation bar otherwise. Zero until the first insets pass. */
    private var obstructionTopPx = 0

    /** Highest the card may be placed: clear of the status bar, from the same insets pass. */
    private var minCardTopPx = 0

    /**
     * This window alone ignores the launcher's font-size multiplier.
     *
     * Everywhere else that setting is doing what it was asked to: making the launcher's own pages
     * easier to read. Here it works against the design. The card is a small panel floating over
     * someone else's app with a hard ceiling on its height, so the multiplier doesn't make it
     * bigger, it makes it hold fewer results — at 1.25 a list that showed five apps shows three,
     * and the whole point of the window is choosing from what it offers.
     *
     * The device's own text-size setting still applies. Opting out here means skipping our
     * multiplier, not overriding the accessibility preference underneath it: someone who has made
     * all text larger system-wide meant it, and this window is not the place to argue.
     */
    override val appliesFontScale: Boolean get() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The assistant role outlives the toggle — someone who turns the feature off but leaves us
        // as their assistant must get their power button back, not an empty search bar.
        if (!Prefs.quickSearchEnabled(this)) {
            finish()
            return
        }
        overrideOwnTransitions()
        // Edge-to-edge is what makes the IME inset actually reach the listener below — the same
        // reason AppDrawerActivity sets it (see the note there).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_quick_search)

        card = findViewById(R.id.quickSearchCard)
        cardGlow = findViewById(R.id.quickSearchCardGlow)
        // The card is the glass here, not the window: setCardBackgroundColor rather than a
        // background, so its corners and hairline stroke survive.
        Glass.frost(window, card) { veil -> card.setCardBackgroundColor(veil) }
        searchLayout = findViewById(R.id.searchLayout)
        searchInput = findViewById(R.id.searchInput)
        resultList = findViewById(R.id.resultList)
        cardDivider = findViewById(R.id.cardDivider)
        emptyLabel = findViewById(R.id.emptyLabel)

        findViewById<View>(R.id.quickSearchScrim).setOnClickListener { finish() }
        // On the EditText, not the layout — the borderless field style has no floating label.
        searchInput.hint = getString(SearchEngine.hint(this))
        // DIP, not SP: the field is fixed outright, where the rows below merely opt out of the
        // launcher's multiplier (see appliesFontScale). The bar is chrome with a height the card's
        // whole layout is measured against, so it holds still even for the device's own text-size
        // setting; the results are content, and content follows that setting.
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, FIELD_TEXT_SIZE_DP)

        resultList.layoutManager = LinearLayoutManager(this)
        resultList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) userScrolled = true
            }
        })
        adapter = AppListAdapter(
            scope = lifecycleScope,
            onClick = { app -> AppRepository.launch(this, app); finish() },
            onFileClick = { hit -> FileSearcher.open(this, hit); finish() },
            onWebClick = { web -> WebSearch.open(this, web.query, web.url); finish() },
            onSettingsClick = { openSettings() },
            onCalculationClick = { calculation -> open(calculation) }
        )
        resultList.adapter = adapter
        // Same gesture as the drawer's list: swipe a result right to open it floating. This window
        // closes itself either way, so there is no extra teardown beyond finish().
        ItemTouchHelper(
            SwipeToFloat(adapter) { result -> FloatingWindow.open(this, result); finish() }
        ).attachToRecyclerView(resultList)

        search = SearchController(this, lifecycleScope) { query, results ->
            renderResults(query, results)
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                search.onQueryChanged(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        searchInput.setOnEditorActionListener { _, actionId, event ->
            val isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (actionId == EditorInfo.IME_ACTION_UNSPECIFIED &&
                    event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isSearchAction) {
                if (!searchInput.text.isNullOrBlank()) {
                    adapter.firstResult()?.let { open(it) }
                }
                true
            } else {
                false
            }
        }

        positionCard()
        applyInsets()
        loadApps()
        showKeyboard()
        if (savedInstanceState == null) animateIn()
    }

    /** Sits the card in the upper third, above where the keyboard will come up, and narrows it on
     *  a display too wide for a full-bleed search bar to be a sensible shape. */
    private fun positionCard() {
        // The glow wrapper is what is positioned and animated: the card rides inside it, so its
        // shadow can never drift out of step with it. The resting top margin is the starting
        // point only — [layoutCard] moves it up once the insets say what is in the way.
        cardGlow.updateLayoutParams<FrameLayout.LayoutParams> {
            topMargin = (resources.displayMetrics.heightPixels * CARD_TOP_FRACTION).toInt()
            // A full-width fraction is left as the layout's match_parent rather than converted to
            // a pixel width, so the wrapper's horizontal margins still apply — pinning it to the
            // display width instead would push its glow off both edges. See values/fractions.xml.
            val fraction = resources.getFraction(R.fraction.quick_search_card_width, 1, 1)
            if (fraction < 1f) width = (resources.displayMetrics.widthPixels * fraction).toInt()
        }
    }

    /**
     * Records what is obstructing the screen, then re-places the card for it.
     *
     * The window doesn't resize for the IME, so nothing moves on its own: the keyboard simply
     * appears over the bottom of the screen and it is up to [layoutCard] to work around it.
     */
    private fun applyInsets() {
        val scrim = findViewById<View>(R.id.quickSearchScrim)
        ViewCompat.setOnApplyWindowInsetsListener(scrim) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            obstructionTopPx = resources.displayMetrics.heightPixels - maxOf(ime, bars.bottom)
            minCardTopPx = bars.top + dp(this, MIN_CARD_TOP_MARGIN_DP)
            layoutCard()
            insets
        }
    }

    /**
     * Places the card, and sizes its list for the room that leaves.
     *
     * The card sits at [CARD_TOP_FRACTION] when it can, which is the position the design wants: far
     * enough down that the app behind stays recognisable above it. When the space between there and
     * the keyboard won't hold [MIN_RESULTS], the card slides up the screen instead of the list
     * shrinking — up to [minCardTopPx], just under the status bar.
     *
     * That is the difference between a soft keyboard and a hardware one, without either being
     * named: with a physical keyboard there is no IME inset, the obstruction is only the navigation
     * bar, the space is ample and the card never leaves its resting place. A tablet in landscape is
     * the opposite extreme — a short screen under a tall keyboard — and there the card rides near
     * the top so the results still fit. Both fall out of the same arithmetic.
     */
    private fun layoutCard() {
        // Nothing sensible to compute until the first insets pass has said what is in the way.
        if (obstructionTopPx <= 0) return
        val screenHeight = resources.displayMetrics.heightPixels
        // The field may not have been measured yet the first time insets land; a dp estimate is
        // close enough for one frame, and the next pass corrects it.
        val fieldHeight = searchLayout.height.takeIf { it > 0 } ?: dp(this, 64)
        val chrome = cardChromePx(this)

        val top = (screenHeight * CARD_TOP_FRACTION).toInt()
            .coerceAtMost(obstructionTopPx - fieldHeight - chrome - wantedListPx())
            .coerceAtLeast(minCardTopPx)
        // Assigned only on a real change: this runs from an insets listener, and re-requesting
        // layout every pass with the value it already had is how that becomes a loop.
        val params = cardGlow.layoutParams as FrameLayout.LayoutParams
        if (params.topMargin != top) {
            params.topMargin = top
            cardGlow.layoutParams = params
        }

        val available = (obstructionTopPx - top - fieldHeight - chrome).coerceAtLeast(0)
        // MAX_CARD_FRACTION keeps a long list from dominating the app behind the card; the floor
        // stops that cap cutting below the results the card promises. Both are clamped to the space
        // actually available, so the card can never grow back under the keyboard it just moved to
        // avoid — a result behind the IME can be neither read nor tapped, so it is not worth having.
        val ceiling = minOf((screenHeight * MAX_CARD_FRACTION).toInt(), available)
        resultList.maxHeightPx = snapToWholeRows(ceiling.coerceAtLeast(minListPx().coerceAtMost(available)))
    }

    /**
     * Trims [ceiling] to the tallest run of rows that fits inside it whole.
     *
     * Without this the list stops mid-row: the card's bottom edge slices a result in half, which
     * reads as a rendering fault rather than as "there is more below". Rows are measured rather
     * than assumed because they aren't uniform — a file or web row carrying a subtitle stands
     * taller than the minimum an app row sits at.
     *
     * Stable across passes rather than oscillating: once the list is exactly as tall as the rows
     * that fit, the same rows still fit, so the next measure returns the same number. Before
     * anything is laid out there is nothing to walk and [ceiling] passes through unchanged, which
     * [renderResults] corrects on the pass after the rows land.
     */
    private fun snapToWholeRows(ceiling: Int): Int {
        var used = 0
        for (i in 0 until resultList.childCount) {
            val height = resultList.getChildAt(i)?.height ?: continue
            if (height <= 0) continue
            if (used + height > ceiling) break
            used += height
        }
        return if (used > 0) used else ceiling
    }

    /**
     * Height [MIN_RESULTS] rows and a section label want, for positioning purposes.
     *
     * An estimate from the theme rather than the measured [minListPx], and deliberately so: the
     * card is positioned when the keyboard appears, which is before a single result has been laid
     * out. Measuring would place the card for an empty list and then move it the moment rows
     * arrived, which is the card jumping under the reader's hands as they type. The row height
     * comes from the same theme attribute that sizes the rows, so the two cannot drift.
     */
    private fun wantedListPx(): Int {
        val rowHeight = TypedValue().let { out ->
            theme.resolveAttribute(R.attr.searchRowMinHeight, out, true)
            TypedValue.complexToDimensionPixelSize(out.data, resources.displayMetrics)
        }
        return MIN_RESULTS * rowHeight + dp(this, SECTION_LABEL_DP)
    }

    /**
     * Height the first [MIN_RESULTS] results need, section labels above them included.
     *
     * Measured from the rows on screen rather than assumed from the layouts' dp. A row's real
     * height still moves under this window even though it opts out of the launcher's font-size
     * multiplier: the device's own text-size setting scales it, and so does an imported typeface
     * whose metrics differ from the system font's. A floor computed from the declared 60dp would
     * clip the last row it exists to guarantee.
     *
     * Before anything is laid out there is nothing to measure and this is zero, which is correct
     * rather than merely safe: with no rows on screen the ceiling is whatever the keyboard leaves,
     * and [renderResults] runs this again once the rows it just submitted have been laid out.
     */
    private fun minListPx(): Int {
        val (results, chrome) = adapter.leadingRowCounts(MIN_RESULTS)
        var rowHeight = 0
        var chromeHeight = 0
        for (i in 0 until resultList.childCount) {
            val child = resultList.getChildAt(i) ?: continue
            if (child.height <= 0) continue
            val holder = resultList.getChildViewHolder(child)
            if (holder is AppListAdapter.SectionVH || holder is AppListAdapter.HeaderVH) {
                chromeHeight = maxOf(chromeHeight, child.height)
            } else {
                rowHeight = maxOf(rowHeight, child.height)
            }
        }
        return results * rowHeight + chrome * chromeHeight
    }

    private fun animateIn() {
        cardGlow.alpha = 0f
        cardGlow.translationY = entryOffsetPx(this).toFloat()
        cardGlow.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ENTRY_DURATION_MS)
            .start()
    }

    private fun showKeyboard() {
        searchInput.requestFocus()
        searchInput.post {
            WindowInsetsControllerCompat(window, searchInput).show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun loadApps() {
        // The disk snapshot is the difference between a full list on the first frame and an empty
        // one after the OS hibernated us — and this screen is opened cold far more often than the
        // drawer is. Then reconcile against a real scan off the main thread.
        search.apps = AppRepository.cachedOrPrime(this)
        if (!AppRepository.needsScan()) return
        lifecycleScope.launch {
            val apps: List<AppInfo> =
                withContext(Dispatchers.IO) { AppRepository.load(this@QuickSearchActivity) }
            search.apps = apps
            search.refresh()
        }
    }

    /**
     * The scroll reset is keyed on whether the user has dragged, not on whether the query changed.
     * The file section lands after the apps do, inserting rows *above* whatever LinearLayoutManager
     * is anchored to — which silently leaves the list parked at the bottom, showing the web row
     * instead of the best match. Anything the user hasn't scrolled themselves goes back to the top.
     */
    private fun renderResults(query: String, results: List<SearchResult>) {
        if (renderedQuery != query) userScrolled = false
        renderedQuery = query
        adapter.submitResults(results) {
            if (!userScrolled) resultList.scrollToPosition(0)
            // The floor is measured off the rows themselves, so it can only be recomputed once
            // this batch has been laid out.
            resultList.post { layoutCard() }
        }
        resultList.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
        // "No results" only once something has been typed — an untouched box is a bare search bar,
        // not an empty state.
        emptyLabel.visibility =
            if (results.isEmpty() && query.isNotBlank()) View.VISIBLE else View.GONE
        // The hairline only earns its place once there are two things to separate.
        cardDivider.visibility =
            if (resultList.isVisible || emptyLabel.isVisible) View.VISIBLE else View.GONE
    }

    private fun open(result: SearchResult) {
        when (result) {
            is SearchResult.App -> AppRepository.launch(this, result.app)
            is SearchResult.File -> FileSearcher.open(this, result.hit)
            is SearchResult.Web -> WebSearch.open(this, result.query, result.url)
            is SearchResult.Settings -> { openSettings(); return }
            // Copies and closes, unlike the drawer, which stays open. This window is a panel over
            // whatever app the number is wanted in, so tapping the row means "give me that and get
            // out of the way"; someone who only wanted to read the answer never taps it at all.
            is SearchResult.Calculation -> Calculator.copy(this, result)
        }
        finish()
    }

    /** NEW_TASK because this window lives in its own task (see the manifest) — without it the hub
     *  would be stacked into that throwaway task and vanish when this activity finishes. */
    private fun openSettings() {
        startActivity(
            Intent(this, SettingsHubActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    /** Nothing here is worth keeping across a trip away — switching apps is the whole point, and a
     *  stale query waiting behind the next app would be a surprise. */
    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::search.isInitialized) search.cancel()
    }

    companion object {
        /** Where the card's top edge sits, as a fraction of screen height. */
        private const val CARD_TOP_FRACTION = 0.20f

        /** Ceiling on the result list, as a fraction of screen height — the app underneath has to
         *  stay recognisable even with a long list open. */
        private const val MAX_CARD_FRACTION = 0.55f

        private const val ENTRY_DURATION_MS = 180L

        /** Gap between the status bar and the card, once the card has been pushed as high as it
         *  goes. Enough that it reads as floating rather than docked to the top edge, and no more:
         *  every dp here is taken from the results on a screen tight enough to need it. */
        private const val MIN_CARD_TOP_MARGIN_DP = 6

        /** Rough height of one section label, for [wantedListPx]. A result list always carries at
         *  least one, and being a little generous only makes the card sit slightly higher. */
        private const val SECTION_LABEL_DP = 44

        /** Size of the field's text, in dp — see the note where it is applied. Smaller than the
         *  body-large default the field used to inherit: at that size the bar read as a page
         *  heading rather than as the compact overlay it is. */
        private const val FIELD_TEXT_SIZE_DP = 15f

        /** Black shadow instead of the platform's grey, for a deeper edge against app content. */
        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()

        /**
         * Space below the result list that the card still needs: 22dp of real structure — the
         * card's 10dp bottom padding and the glow wrapper's 12dp — plus a 16dp gap so the card
         * doesn't sit flush against the keyboard.
         *
         * This was 64dp, which was guesswork: measured on device only 22dp of it was ever the
         * card, leaving 42dp of dead air under the list. On a tall screen that went unnoticed, but
         * on a tablet in landscape — where the whole budget between the status bar and the keyboard
         * is about six rows' worth — it was the difference between three results and four.
         */
        private fun cardChromePx(context: Context) = dp(context, 38)

        /** Results the card is guaranteed to show, however little room the keyboard leaves. Below
         *  this floor the list scrolls inside the card rather than shrinking further.
         *
         *  Four rather than three because this window's rows are compact (48dp — see
         *  searchRowMinHeight in themes.xml): measured on device, four of them occupy 624px where
         *  three of the old 60dp rows took 585px, so the fourth result costs about 6% of the card's
         *  height rather than a third of it. Three was the floor while rows were roomy, on the
         *  reasoning that two is not enough to choose from; the same reasoning buys one more now
         *  that a row is cheaper. */
        private const val MIN_RESULTS = 4

        private fun entryOffsetPx(context: Context) = dp(context, 12)

        /** Opens the floating search — used by the gesture dispatcher. */
        fun intent(context: Context): Intent =
            Intent(context, QuickSearchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
