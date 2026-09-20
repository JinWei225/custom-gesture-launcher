package dev.neffly.gesturelauncher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.neffly.gesturelauncher.crash.SafeModeActivity
import dev.neffly.gesturelauncher.data.CalendarRepository
import dev.neffly.gesturelauncher.data.DayEvent
import dev.neffly.gesturelauncher.data.GestureMapping
import dev.neffly.gesturelauncher.data.GestureStore
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.drawer.AppDrawerActivity
import dev.neffly.gesturelauncher.search.QuickSearchActivity
import dev.neffly.gesturelauncher.accessibility.LauncherAccessibilityService
import dev.neffly.gesturelauncher.settings.openAccessibilitySettings
import dev.neffly.gesturelauncher.ui.BaseActivity
import dev.neffly.gesturelauncher.ui.DockStrip
import dev.neffly.gesturelauncher.ui.PageDotsView
import dev.neffly.gesturelauncher.ui.showWithFont
import dev.neffly.gesturelauncher.widgets.WidgetPage
import dev.neffly.gesturelauncher.notes.NotesPage
import dev.neffly.gesturelauncher.ui.StaticPagesAdapter
import androidx.viewpager2.widget.ViewPager2
import android.view.LayoutInflater
import dev.neffly.gesturelauncher.data.anyMultiStroke
import dev.neffly.gesturelauncher.data.maxExpectedSubStrokes
import dev.neffly.gesturelauncher.data.toPt
import dev.neffly.gesturelauncher.data.toTemplates
import dev.neffly.gesturelauncher.launch.UnrequestedHomeLaunch
import dev.neffly.gesturelauncher.ui.FontEngine
import dev.neffly.gesturelauncher.ui.GestureCanvasView
import dev.neffly.gesturelauncher.ui.overrideNextTransition
import dev.neffly.gesturelauncher.unistroke.GestureTemplate
import dev.neffly.gesturelauncher.unistroke.OneDollarRecognizer
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

/**
 * Home screen: three pages over the system wallpaper, under the home page's dock.
 *
 * The middle page is home proper — a gesture canvas confined to its lower ~70%, with a
 * non-drawable clock + today's-events zone above. Left of it is the widgets page, right of it the
 * notes page. The dock is the search button in one corner and, beside it, a strip that turns the
 * pages when dragged and locks the screen when double-tapped ([DockStrip]); on the two side pages
 * a swipe anywhere turns them, and on the home page a horizontal swipe is a stroke, so the pager
 * takes no touch there. The dock is rendered independently of the recognizer.
 */
class MainActivity : BaseActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var dock: View
    private lateinit var dockStrip: PageDotsView
    private lateinit var searchButton: ImageButton
    private lateinit var widgetPage: WidgetPage
    private lateinit var notesPage: NotesPage

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var canvas: GestureCanvasView
    private lateinit var eventsContainer: LinearLayout

    /** Every view on this screen that draws touch feedback — see [clearFrozenTapFeedback]. */
    private lateinit var tappableWidgets: List<View>
    private lateinit var emptyHint: TextView
    private lateinit var recognitionHint: TextView
    private lateinit var batteryIcon: ImageView
    private lateinit var batteryLevel: TextView

    /** Preprocessed templates + id->mapping lookup, rebuilt whenever gestures may have changed. */
    private var templates: List<GestureTemplate> = emptyList()
    private var mappingsById: Map<String, GestureMapping> = emptyMap()

    private val requestCalendar =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The permission dialog only pauses this screen, so onStart — where the observer is
            // normally hooked up — won't run again until the next Home press; without this the
            // list would show once and then stop following calendar edits.
            if (granted) { watchCalendar(); refreshEvents() } else openCalendar()
        }

    private val heartbeat = Runnable {
        // Ran 10s without crashing -> clear the crash counter so one-offs don't accumulate.
        Prefs.resetCrashCount(this)
    }

    // Today's-events cache: the home screen resumes on every unlock/Home press, and requerying
    // the calendar provider each time is the hottest lifecycle path on the device. Cached with a
    // short TTL + day stamp; the ContentObserver below invalidates on actual calendar changes.
    private var eventsCache: List<DayEvent>? = null
    private var eventsCacheAtMillis = 0L
    private var eventsCacheDay = -1L

    /** The lines currently in [eventsContainer], so a resume that would draw the same three rows
     *  again doesn't rebuild the views — this runs on every Home press. */
    private var renderedRows: List<String> = emptyList()

    /** Last battery state drawn, as (percent, charging). ACTION_BATTERY_CHANGED also fires for
     *  voltage and temperature, every few seconds on a charger, and none of those move the
     *  indicator. */
    private var renderedBattery: Pair<Int, Boolean>? = null

    /** ACTION_BATTERY_CHANGED is only delivered to receivers registered at runtime, so the
     *  indicator beside the date is driven from here rather than from the manifest. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showBattery(intent)
    }

    private var calendarWatched = false

    /** A sync touches the provider once per row it writes, so a burst of these arrives for one
     *  logical change; the forced re-query is coalesced to the end of the burst. */
    private val calendarChanged = Runnable { refreshEvents(force = true) }

    private val calendarObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(calendarChanged)
            handler.postDelayed(calendarChanged, CALENDAR_DEBOUNCE_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Safety net: after repeated crashes, don't render the gesture canvas at all.
        if (Prefs.shouldEnterSafeMode(this)) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        // A launcher home screen stays put on Back — but a side page goes back to home first.
        // Registered via the dispatcher (not an onBackPressed override) so predictive back on
        // Android 14+ sees it too.
        onBackPressedDispatcher.addCallback(this) {
            if (pager.currentItem != PAGE_HOME) pager.setCurrentItem(PAGE_HOME, true)
        }

        val inflater = LayoutInflater.from(this)
        pager = findViewById(R.id.homePager)
        dock = findViewById(R.id.homeDock)
        val widgets = inflater.inflate(R.layout.page_widgets, pager, false)
        val home = inflater.inflate(R.layout.page_home, pager, false)
        val notes = inflater.inflate(R.layout.page_notes, pager, false)
        val pages = listOf(widgets, home, notes)
        // Built after setContentView, so the pass BaseActivity makes over the content view never
        // saw them; without this the clock, the battery and the notes keep the system font.
        pages.forEach { FontEngine.applyTo(it) }
        setUpPager(pages)
        widgetPage = WidgetPage(this, widgets)
        notesPage = NotesPage(this, notes)

        canvas = home.findViewById(R.id.gestureCanvas)
        canvas.autoClearMillis = 180L
        canvas.bottomDeadZone = dp(CANVAS_DEAD_ZONE_DP)
        canvas.onStroke = { points, subStrokes -> onHomeStroke(points, subStrokes.size) }

        emptyHint = home.findViewById(R.id.emptyHint)
        recognitionHint = home.findViewById(R.id.recognitionHint)
        eventsContainer = home.findViewById(R.id.eventsContainer)
        batteryIcon = home.findViewById(R.id.batteryIcon)
        batteryLevel = home.findViewById(R.id.batteryLevel)

        // Keep the pages below the status bar and above the navigation bar — or above the
        // keyboard the notes page opens, which is when the note box, and only it, sits on the
        // keyboard: the dock stays put underneath it, and the room the notes page keeps clear
        // for the dock is given back to the list. The dock itself stays clear of the bar by a
        // fixed gap whichever navigation mode the device is in.
        val root = findViewById<View>(R.id.homeRoot)
        val notesDockClearance = notes.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val keyboard = ime > bars.bottom
            pager.updatePadding(top = bars.top, bottom = if (keyboard) ime else bars.bottom)
            notes.updatePadding(bottom = if (keyboard) dp(NOTES_KEYBOARD_GAP_DP) else notesDockClearance)
            val dockMargin = bars.bottom + dp(DOCK_GAP_DP)
            val params = dock.layoutParams as FrameLayout.LayoutParams
            if (params.bottomMargin != dockMargin) {
                params.bottomMargin = dockMargin
                dock.layoutParams = params
            }
            insets
        }

        // The two lines of the clock widget go to the two apps they are actually about: the date
        // (and the events under it) to the calendar, the time to the clock. The column that stacks
        // them is no longer a single target — see the layout.
        val dateRow = home.findViewById<View>(R.id.dateRow)
        val clockTime = home.findViewById<View>(R.id.clockTime)
        // The date opens the calendar outright. The events list below it is the one that asks for
        // the permission, because the permission is what fills *it* — being made to grant calendar
        // access just to open the calendar app would be a toll on the wrong gate.
        dateRow.setOnClickListener { openCalendar() }
        clockTime.setOnClickListener { openClock() }
        eventsContainer.setOnClickListener {
            if (hasCalendarPermission()) openCalendar()
            else requestCalendar.launch(Manifest.permission.READ_CALENDAR)
        }

        // Tap searches, long-press browses. The compact floating window is the same one the power
        // button opens, so search behaves identically wherever it's reached from; the full drawer
        // stays one long-press away for browsing A-Z and for the per-app actions (alias, uninstall,
        // shortcuts) that only live there. The drawer is also the guaranteed way in when the
        // overlay is switched off, which is why that case falls back to it rather than doing
        // nothing — this button must never be a dead end.
        searchButton = findViewById(R.id.searchButton)
        searchButton.setOnClickListener {
            if (Prefs.quickSearchEnabled(this)) openQuickSearch() else openDrawer()
        }
        searchButton.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openDrawer()
            true
        }
        dockStrip = findViewById(R.id.dockStrip)
        dockStrip.count = PAGE_COUNT
        dockStrip.position = PAGE_HOME.toFloat()
        DockStrip(dockStrip, pager) { lockScreen() }
        placeSearch()
        showDock(pager.currentItem, animate = false)

        tappableWidgets = listOf(dateRow, clockTime, eventsContainer, searchButton)
    }

    /**
     * Puts the search disc in the corner the setting names and runs the strip over the rest of
     * the dock, so the drag-and-double-tap area is always the part beside the button and never
     * under it. Applied on every resume: the setting is changed on a screen over this one.
     */
    private fun placeSearch() {
        val left = Prefs.searchOnLeft(this)
        val gravity = if (left) Gravity.START else Gravity.END
        val params = searchButton.layoutParams as FrameLayout.LayoutParams
        if (params.gravity == gravity) return
        params.gravity = gravity
        searchButton.layoutParams = params
        val inset = searchButton.layoutParams.width + dp(STRIP_GAP_DP)
        dockStrip.updateLayoutParams<FrameLayout.LayoutParams> {
            marginStart = if (left) inset else 0
            marginEnd = if (left) 0 else inset
        }
    }

    /**
     * Three pages, all kept alive: the widgets keep updating and the note box keeps its draft
     * while another page is in front. Swiping only ever leaves a side page — on the home page a
     * horizontal drag is a stroke for the canvas, which the pager must never take first.
     */
    private fun setUpPager(pages: List<View>) {
        pager.adapter = StaticPagesAdapter(pages)
        pager.offscreenPageLimit = pages.size - 1
        pager.setCurrentItem(PAGE_HOME, false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                dockStrip.position = position + positionOffset
            }

            override fun onPageSelected(position: Int) {
                pager.isUserInputEnabled = position != PAGE_HOME
                showDock(position, animate = true)
                if (position != PAGE_NOTES) hideKeyboard()
            }
        })
        pager.isUserInputEnabled = false
    }

    /**
     * The strip and its dots stay on every page — they are how the pages are told apart. The
     * search disc belongs to the home page: on a side page it would only sit over the widgets
     * and notes, so it fades out — and stops taking touches — there and comes back on return.
     */
    private fun showDock(page: Int, animate: Boolean) {
        val home = page == PAGE_HOME
        searchButton.animate().cancel()
        if (!animate) {
            searchButton.alpha = if (home) 1f else 0f
            searchButton.visibility = if (home) View.VISIBLE else View.INVISIBLE
            return
        }
        if (home) searchButton.visibility = View.VISIBLE
        searchButton.animate()
            .alpha(if (home) 1f else 0f)
            .setDuration(DOCK_FADE_MS)
            .withEndAction { if (!home) searchButton.visibility = View.INVISIBLE }
            .start()
    }

    /** Asked of the window, not of the focused view: by the time a page change reports in, the
     *  note box can already have lost focus while the keyboard it opened is still up. */
    private fun hideKeyboard() {
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        currentFocus?.clearFocus()
    }

    /** Home, pressed while on a side page, comes back to the home page — as any launcher does. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::pager.isInitialized && pager.currentItem != PAGE_HOME) pager.setCurrentItem(PAGE_HOME, true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        // A widget's configuration screen can only report back this way — see WidgetPage.
        widgetPage.onActivityResult(requestCode, resultCode)
    }

    /**
     * Locks through the accessibility service, and when that isn't possible says why rather than
     * doing nothing: a double tap that silently fails reads as broken, where one that names the
     * grant it needs — and opens the screen that gives it — is one tap from working.
     */
    private fun lockScreen() {
        if (LauncherAccessibilityService.lockScreen()) return
        if (!LauncherAccessibilityService.canLock) {
            Toast.makeText(this, R.string.lock_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.lock_setup_title)
            .setMessage(R.string.lock_setup_message)
            .setPositiveButton(R.string.lock_setup_open) { _, _ -> openAccessibilitySettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .showWithFont()
    }


    /**
     * Drops any touch feedback that the tap which left this screen froze part-way.
     *
     * A ripple's exit is a RenderThread animation. When the tap starts another activity, this
     * window is hidden before that animation can finish, and coming back through recents shows the
     * drawable still parked in its pressed state — which is why a tap on the clock left a disc
     * sitting over the widget until something else was tapped to clear it. Clearing the pressed
     * flag and jumping the drawables to their current state is exactly what that extra tap did.
     *
     * Run on resume and again when the window takes focus, rather than on pause: those are the two
     * moments before the frame the artifact would otherwise be visible in, and they are not the
     * same moment. onResume can land while the window is still focusless — coming back from the
     * quick-search overlay is the case that showed it — and feedback that settles in that gap
     * outlives the clear there. Doing both is idempotent and costs a walk of four views.
     */
    private fun clearFrozenTapFeedback() {
        for (view in tappableWidgets) {
            view.isPressed = false
            view.jumpDrawablesToCurrentState()
        }
    }

    private fun openDrawer() {
        startActivity(Intent(this, AppDrawerActivity::class.java))
        // The drawer animates its own content in; suppress the OS's default cross-activity
        // transition so it can't fight with (or get replaced by an OEM "app open" animation
        // instead of) that self-driven slide. On Android 14+ the drawer's own
        // overrideActivityTransition(..., 0, 0) call handles this side of the pair.
        overrideNextTransition()
    }

    private fun openQuickSearch() {
        startActivity(QuickSearchActivity.intent(this))
        overrideNextTransition()
    }

    override fun onStart() {
        super.onStart()
        // The system launcher starts HOME right after a floating window opens; when it does, give
        // the app underneath its screen back. See UnrequestedHomeLaunch.
        if (UnrequestedHomeLaunch.consume()) moveTaskToBack(true)
        // Sticky broadcast: registering hands back the current battery state immediately, so the
        // indicator is already correct on the first frame instead of blank until the next change.
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { showBattery(it) }
        watchCalendar()
        widgetPage.onStart()
    }

    override fun onStop() {
        super.onStop()
        widgetPage.onStop()
        unwatchCalendar()
        runCatching { unregisterReceiver(batteryReceiver) }
    }

    /** Refreshes when an event is added/changed from another app while home is visible. A no-op
     *  without the permission, and idempotent, so it can be called from onStart and from the
     *  permission grant alike. */
    private fun watchCalendar() {
        if (calendarWatched || !hasCalendarPermission()) return
        contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, calendarObserver)
        calendarWatched = true
    }

    private fun unwatchCalendar() {
        handler.removeCallbacks(calendarChanged)
        if (!calendarWatched) return
        runCatching { contentResolver.unregisterContentObserver(calendarObserver) }
        calendarWatched = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) clearFrozenTapFeedback()
    }

    override fun onResume() {
        super.onResume()
        clearFrozenTapFeedback()
        placeSearch()
        rebuildTemplates()
        refreshEvents()
        notesPage.refresh()
        // Start (or restart) the "healthy" heartbeat.
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, 10_000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(heartbeat)
    }

    // --- gestures ---------------------------------------------------------

    private fun rebuildTemplates() {
        val mappings = GestureStore.all(this)
        mappingsById = mappings.associateBy { it.id }
        templates = mappings.toTemplates()
        // Only pay the pen-lift gap-timeout once a multi-stroke gesture actually exists, and
        // finalize early once a session reaches the largest trained sub-stroke count — there's
        // nothing more to wait for beyond it.
        canvas.multiStrokeGapMillis =
            if (mappings.anyMultiStroke()) GestureCanvasView.MULTI_STROKE_GAP_MILLIS else 0L
        canvas.maxExpectedSubStrokes = mappings.maxExpectedSubStrokes()
        emptyHint.visibility = if (mappings.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onHomeStroke(points: List<android.graphics.PointF>, subStrokeCount: Int) {
        if (templates.isEmpty()) return
        val pts = points.toPt()
        // Not long/deliberate enough to be a real attempt — stay silent (no hint, no haptic), the
        // trail just auto-clears. Only strokes past this floor are treated as "the user tried to
        // draw something" for the purposes of the not-recognized hint below.
        if (!OneDollarRecognizer.isStrokeUsable(pts)) return

        val result = OneDollarRecognizer.recognize(pts, templates, subStrokeCount)
        val threshold = Prefs.matchThreshold(this)
        val mapping = result.name?.let { mappingsById[it] }
        if (mapping != null && result.score >= threshold) {
            hideRecognitionHint()
            if (Prefs.hapticFeedback(this)) {
                // Instant confirmation that something is happening — the app window (or whatever
                // else the gesture does) can take a beat to appear.
                canvas.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        HapticFeedbackConstants.CONFIRM
                    } else {
                        HapticFeedbackConstants.VIRTUAL_KEY
                    }
                )
            }
            GestureActionDispatcher.perform(this, mapping)
        } else {
            showRecognitionHint()
        }
    }

    private fun showRecognitionHint() {
        recognitionHint.text = getString(R.string.gesture_not_recognized)
        recognitionHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideHintRunnable)
        handler.postDelayed(hideHintRunnable, RECOGNITION_HINT_MILLIS)
    }

    private fun hideRecognitionHint() {
        handler.removeCallbacks(hideHintRunnable)
        recognitionHint.visibility = View.GONE
    }

    private val hideHintRunnable = Runnable { recognitionHint.visibility = View.GONE }

    // --- calendar widget --------------------------------------------------

    private fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun refreshEvents(force: Boolean = false) {
        if (!hasCalendarPermission()) {
            renderRows(listOf(getString(R.string.enable_calendar)))
            return
        }
        val now = System.currentTimeMillis()
        val today = dayStamp()
        val cached = eventsCache
        if (!force && cached != null && eventsCacheDay == today &&
            now - eventsCacheAtMillis < EVENTS_TTL_MILLIS
        ) {
            renderEvents(cached)
            return
        }
        lifecycleScope.launch {
            val events = withContext(Dispatchers.IO) { CalendarRepository.todaysEvents(this@MainActivity) }
            eventsCache = events
            eventsCacheAtMillis = System.currentTimeMillis()
            eventsCacheDay = today
            renderEvents(events)
        }
    }

    /** Year*1000 + day-of-year — cheap "is it still the same day" stamp for the events cache. */
    private fun dayStamp(): Long {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Shows what is left of the day, not the whole of it.
     *
     * The widget holds three rows, so without this an afternoon glance is three rows of things that
     * already happened while the next meeting sits below the fold. An event still running counts as
     * ahead — it is the one most worth seeing — and an all-day event spans the day by definition, so
     * neither is filtered out.
     *
     * Filtered here rather than in the query so the five-minute cache stays useful: every resume
     * re-renders against the current time without going back to the calendar provider.
     */
    private fun renderEvents(events: List<DayEvent>) {
        val now = System.currentTimeMillis()
        val remaining = events.filter { it.allDay || it.end > now }
        if (remaining.isEmpty()) {
            // A day whose events are all behind us isn't a day with no events — saying so would be
            // wrong for anyone checking after their last meeting.
            val empty = if (events.isEmpty()) R.string.no_events_today else R.string.no_events_left
            renderRows(listOf(getString(empty)))
            return
        }
        val timeFmt = DateFormat.getTimeFormat(this)
        val maxRows = 3
        val rows = remaining.take(maxRows).map { e ->
            val time = if (e.allDay) getString(R.string.all_day) else timeFmt.format(Date(e.begin))
            "$time   ${e.title}"
        }.toMutableList()
        if (remaining.size > maxRows) {
            rows.add(getString(R.string.more_events, remaining.size - maxRows))
        }
        renderRows(rows)
    }

    /**
     * Builds the today's-events rows: an accent tick, then the line itself. The first row is the
     * next thing happening and is drawn brightest, with the rest stepped back — the same ordering
     * cue the clock design uses, and cheaper to read at a glance than three identical lines.
     */
    private fun renderRows(lines: List<String>) {
        if (lines == renderedRows && eventsContainer.childCount == lines.size) return
        renderedRows = lines
        eventsContainer.removeAllViews()
        lines.forEachIndexed { index, line ->
            val leading = index == 0
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            val tick = View(this).apply {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_event_bar)
                alpha = if (leading) 0.7f else 0.4f
            }
            row.addView(tick, LinearLayout.LayoutParams(dp(3), dp(14)))

            val tv = TextView(this).apply {
                text = line
                // White-on-shadow in both themes, matching the clock above — these rows are
                // drawn straight over the wallpaper, so they can't follow the app theme.
                // See the note on wallpaper_overlay_text in res/values/colors.xml.
                setTextColor(
                    ContextCompat.getColor(this@MainActivity, R.color.wallpaper_overlay_text)
                )
                alpha = if (leading) 0.82f else 0.62f
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setShadowLayer(
                    10f, 0f, 0f,
                    ContextCompat.getColor(this@MainActivity, R.color.wallpaper_overlay_shadow)
                )
                gravity = Gravity.CENTER_VERTICAL
                // Built in code, so it never passes through the activity's pass over its content
                // view — without this the event rows keep the system font while the clock above
                // them changes.
                FontEngine.applyToSelf(this)
            }
            row.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10) }
            )

            eventsContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // --- battery indicator ------------------------------------------------

    /** Renders the charge from an ACTION_BATTERY_CHANGED intent; hides the pair if it has none. */
    private fun showBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            renderedBattery = null
            batteryIcon.visibility = View.GONE
            batteryLevel.visibility = View.GONE
            return
        }
        val percent = (level * 100f / scale).roundToInt().coerceIn(0, 100)
        // EXTRA_STATUS rather than EXTRA_PLUGGED: a phone can be plugged in and not charging
        // (dock, full battery, charge-limit modes), and the bolt should mean "current is going
        // in". FULL counts — that is what a charger reports once it stops topping up.
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val state = percent to charging
        // setText always relayouts the row, even for the same text — so unchanged is skipped here.
        if (state == renderedBattery) return
        renderedBattery = state

        batteryIcon.visibility = View.VISIBLE
        batteryLevel.visibility = View.VISIBLE
        // The fill layer is clipped horizontally over the battery's cavity: level 10000 = full.
        // setImageResource is a no-op when the drawable is already the one asked for, so this
        // runs on every battery broadcast without re-inflating anything. The level goes on after,
        // since swapping the drawable is what the level applies to.
        batteryIcon.setImageResource(
            if (charging) R.drawable.battery_indicator_charging else R.drawable.battery_indicator
        )
        batteryIcon.setImageLevel(percent * 100)
        batteryLevel.text = getString(R.string.battery_level, percent)
        // The icon is decorative (see the layout), so the state it carries has to reach a screen
        // reader through the label beside it.
        batteryLevel.contentDescription =
            if (charging) getString(R.string.battery_level_charging, percent) else null
    }

    private fun openClock() {
        // 1) Standard "show alarms" intent (works on most devices).
        val showAlarms = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(showAlarms); true }.getOrDefault(false)) return

        // 2) Fallback: launch a known clock package directly (visible via the LAUNCHER query).
        //    Covers OEMs (e.g. Xiaomi/HyperOS "com.android.deskclock") where the intent above is
        //    blocked by package visibility.
        for (pkg in CLOCK_PACKAGES) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch != null && runCatching { startActivity(launch); true }.getOrDefault(false)) return
        }
        Toast.makeText(this, R.string.no_clock_app, Toast.LENGTH_SHORT).show()
    }

    private fun openCalendar() {
        val uri = ContentUris.appendId(
            CalendarContract.CONTENT_URI.buildUpon().appendPath("time"),
            System.currentTimeMillis()
        ).build()
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    companion object {
        /** Page order in the pager: widgets, home, notes. */
        private const val PAGE_HOME = 1
        private const val PAGE_NOTES = 2
        private const val PAGE_COUNT = 3
        private const val DOCK_FADE_MS = 180L
        /** Between the dock and the navigation bar (or the gesture strip). */
        private const val DOCK_GAP_DP = 24
        /** Between the note box and the keyboard, while the keyboard is up. */
        private const val NOTES_KEYBOARD_GAP_DP = 8
        /** The band at the canvas's bottom edge where a swipe up from the navigation bar lands —
         *  see GestureCanvasView.bottomDeadZone. Wide enough for a fast swipe's first sample. */
        private const val CANVAS_DEAD_ZONE_DP = 32
        /** Between the search disc and the strip, so a drag that starts on the disc's edge is a
         *  tap on it rather than a page turn. */
        private const val STRIP_GAP_DP = 8

        private const val EVENTS_TTL_MILLIS = 5 * 60_000L
        private const val CALENDAR_DEBOUNCE_MILLIS = 500L
        private const val RECOGNITION_HINT_MILLIS = 1200L

        /** Known clock/alarm packages, tried in order when ACTION_SHOW_ALARMS can't be dispatched. */
        private val CLOCK_PACKAGES = listOf(
            "com.android.deskclock",        // AOSP / Xiaomi HyperOS
            "com.google.android.deskclock", // Google Clock
            "com.sec.android.app.clockpackage", // Samsung
            "com.oneplus.deskclock",
            "com.coloros.alarmclock",       // Oppo/Realme
            "com.miui.clock"
        )
    }
}
