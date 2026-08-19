package dev.neffly.gesturelauncher.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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

    /** Vertical space between the card's field and the keyboard, from the last insets pass. */
    private var availableListPx = 0

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
            onSettingsClick = { openSettings() }
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

    /** Sits the card in the upper third, above where the keyboard will come up. */
    private fun positionCard() {
        val top = (resources.displayMetrics.heightPixels * CARD_TOP_FRACTION).toInt()
        // The glow wrapper is what is positioned and animated: the card rides inside it, so its
        // shadow can never drift out of step with it.
        cardGlow.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = top }
    }

    /**
     * Keeps the expanded card clear of both the system bars and the keyboard.
     *
     * The window doesn't resize for the IME, so the result list's own ceiling is what stops it
     * growing underneath the keyboard: whatever vertical space is left between the card's top edge
     * and the top of the IME, capped at a fraction of the screen so it never dominates the app
     * behind it either. The space is remembered rather than applied here, because the floor the
     * ceiling is raised to depends on what is currently in the list — see [applyListCeiling].
     */
    private fun applyInsets() {
        val scrim = findViewById<View>(R.id.quickSearchScrim)
        val screenHeight = resources.displayMetrics.heightPixels
        val cardTop = (screenHeight * CARD_TOP_FRACTION).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(scrim) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBar = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val obstructionTop = screenHeight - maxOf(ime, navBar)
            // The field may not have been measured yet the first time insets land; a dp estimate
            // is close enough for one frame, and the next pass corrects it.
            val fieldHeight = searchLayout.height.takeIf { it > 0 } ?: dp(this, 64)
            availableListPx = obstructionTop - cardTop - fieldHeight - cardChromePx(this)
            applyListCeiling()
            insets
        }
    }

    /** Fits the list into whatever the keyboard leaves, but never below [minListPx]. */
    private fun applyListCeiling() {
        val ceiling = minOf(
            (resources.displayMetrics.heightPixels * MAX_CARD_FRACTION).toInt(),
            availableListPx
        )
        resultList.maxHeightPx = ceiling.coerceAtLeast(minListPx())
    }

    /**
     * Height the first [MIN_RESULTS] results need, section labels above them included.
     *
     * Measured from the rows on screen rather than assumed from the layouts' dp, because
     * BaseActivity applies the user's font-size multiplier: a row declared at 60dp comes out
     * noticeably taller once that is turned up, and a floor computed from the declared value
     * clips the last row it was supposed to guarantee.
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
            resultList.post { applyListCeiling() }
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

        /** Black shadow instead of the platform's grey, for a deeper edge against app content. */
        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()

        /** The card's own vertical padding plus a breathing gap above the keyboard. */
        private fun cardChromePx(context: Context) = dp(context, 64)

        /** Results the card is guaranteed to show, however little room the keyboard leaves. The
         *  space above the IME on a tall phone works out at barely two rows, and two is not enough
         *  to choose from — the third is usually where the file or web hit lands. Below this floor
         *  the list scrolls inside the card rather than shrinking further. */
        private const val MIN_RESULTS = 3

        private fun entryOffsetPx(context: Context) = dp(context, 12)

        /** Opens the floating search — used by the gesture dispatcher. */
        fun intent(context: Context): Intent =
            Intent(context, QuickSearchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
