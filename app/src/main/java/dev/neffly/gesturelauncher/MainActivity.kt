package dev.neffly.gesturelauncher

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
import dev.neffly.gesturelauncher.settings.ProfileDialog
import dev.neffly.gesturelauncher.ui.GestureCanvasView
import dev.neffly.gesturelauncher.unistroke.GestureTemplate
import dev.neffly.gesturelauncher.unistroke.OneDollarRecognizer
import dev.neffly.gesturelauncher.unistroke.Pt
import java.util.Calendar
import java.util.Date

/**
 * Home screen: the system wallpaper behind a gesture canvas that is confined to the lower ~70% of
 * the screen. The top ~30% is a non-drawable widget zone (clock + today's events). An always-present
 * drawer button is rendered independently of the recognizer.
 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var canvas: GestureCanvasView
    private lateinit var eventsContainer: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var recognitionHint: TextView

    /** Preprocessed templates + id->mapping lookup, rebuilt whenever gestures may have changed. */
    private var templates: List<GestureTemplate> = emptyList()
    private var mappingsById: Map<String, GestureMapping> = emptyMap()

    private val requestCalendar =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) refreshEvents() else openCalendar()
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

    private val calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshEvents(force = true)
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

        // A launcher home screen stays put on Back. Registered via the dispatcher (not an
        // onBackPressed override) so predictive back on Android 14+ sees it too.
        onBackPressedDispatcher.addCallback(this) { /* no-op */ }

        canvas = findViewById(R.id.gestureCanvas)
        canvas.autoClearMillis = 180L
        canvas.onStroke = { points, subStrokes -> onHomeStroke(points, subStrokes.size) }

        emptyHint = findViewById(R.id.emptyHint)
        recognitionHint = findViewById(R.id.recognitionHint)
        eventsContainer = findViewById(R.id.eventsContainer)

        if (Prefs.profileName(this) == null) {
            ProfileDialog.show(this, null) { name -> Prefs.setProfileName(this, name) }
        }

        // Keep widgets below the status bar and the canvas above the navigation bar.
        val column = findViewById<View>(R.id.contentColumn)
        ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        findViewById<View>(R.id.clockWidget).setOnClickListener { openClock() }
        eventsContainer.setOnClickListener {
            if (hasCalendarPermission()) openCalendar()
            else requestCalendar.launch(Manifest.permission.READ_CALENDAR)
        }

        findViewById<ImageButton>(R.id.drawerButton).setOnClickListener {
            startActivity(Intent(this, AppDrawerActivity::class.java))
            // The drawer animates its own content in; suppress the OS's default cross-activity
            // transition so it can't fight with (or get replaced by an OEM "app open" animation
            // instead of) that self-driven slide. On Android 14+ the drawer's own
            // overrideActivityTransition(..., 0, 0) call handles this side of the pair.
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overridePendingTransition(0, 0)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasCalendarPermission()) {
            // Refresh when an event is added/changed from another app while home is visible.
            contentResolver.registerContentObserver(
                CalendarContract.CONTENT_URI, true, calendarObserver
            )
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { contentResolver.unregisterContentObserver(calendarObserver) }
    }

    override fun onResume() {
        super.onResume()
        rebuildTemplates()
        refreshEvents()
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
        templates = mappings.flatMap { m ->
            m.templates.mapIndexed { idx, stroke ->
                GestureTemplate(
                    m.id,
                    stroke.map { Pt(it.x.toDouble(), it.y.toDouble()) },
                    m.subStrokeLengths.getOrNull(idx)?.size?.coerceAtLeast(1) ?: 1
                )
            }
        }
        // Only pay the pen-lift gap-timeout once a multi-stroke gesture actually exists, and
        // finalize early once a session reaches the largest trained sub-stroke count — there's
        // nothing more to wait for beyond it.
        canvas.multiStrokeGapMillis =
            if (mappings.any { it.isMultiStroke }) GestureCanvasView.MULTI_STROKE_GAP_MILLIS else 0L
        canvas.maxExpectedSubStrokes = mappings.maxOfOrNull { m ->
            m.subStrokeLengths.maxOfOrNull { it.size } ?: 1
        } ?: 0
        emptyHint.visibility = if (mappings.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onHomeStroke(points: List<android.graphics.PointF>, subStrokeCount: Int) {
        if (templates.isEmpty()) return
        val pts = points.map { Pt(it.x.toDouble(), it.y.toDouble()) }
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

    private fun renderEvents(events: List<DayEvent>) {
        if (events.isEmpty()) {
            renderRows(listOf(getString(R.string.no_events_today)))
            return
        }
        val timeFmt = DateFormat.getTimeFormat(this)
        val maxRows = 3
        val rows = events.take(maxRows).map { e ->
            val time = if (e.allDay) getString(R.string.all_day) else timeFmt.format(Date(e.begin))
            "$time   ${e.title}"
        }.toMutableList()
        if (events.size > maxRows) rows.add(getString(R.string.more_events, events.size - maxRows))
        renderRows(rows)
    }

    private fun renderRows(lines: List<String>) {
        eventsContainer.removeAllViews()
        for (line in lines) {
            val tv = TextView(this).apply {
                text = line
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setShadowLayer(10f, 0f, 0f, 0xB0000000.toInt())
                setPadding(0, 6, 0, 6)
                gravity = Gravity.CENTER_VERTICAL
            }
            eventsContainer.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
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
        private const val EVENTS_TTL_MILLIS = 5 * 60_000L
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
