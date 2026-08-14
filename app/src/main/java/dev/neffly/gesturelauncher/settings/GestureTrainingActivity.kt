package dev.neffly.gesturelauncher.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.GestureAction
import dev.neffly.gesturelauncher.data.GestureMapping
import dev.neffly.gesturelauncher.data.GestureStore
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.data.SPoint
import dev.neffly.gesturelauncher.drawer.AppInfo
import dev.neffly.gesturelauncher.drawer.AppListAdapter
import dev.neffly.gesturelauncher.drawer.AppRepository
import dev.neffly.gesturelauncher.ui.BaseActivity
import dev.neffly.gesturelauncher.ui.GestureCanvasView
import dev.neffly.gesturelauncher.ui.StrokePreviewView
import dev.neffly.gesturelauncher.ui.showWithFont
import dev.neffly.gesturelauncher.unistroke.GestureTemplate
import dev.neffly.gesturelauncher.unistroke.OneDollarRecognizer
import dev.neffly.gesturelauncher.unistroke.Pt
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wizard for adding/editing a gesture:
 *  - NEW: pick an app, then draw the shape 3x.
 *  - REDRAW: app fixed, redraw 3x to replace the stored strokes.
 *  - CHANGE_APP: strokes fixed, pick a new app to rebind.
 */
class GestureTrainingActivity : BaseActivity() {

    private lateinit var pickerContainer: View
    private lateinit var drawContainer: View
    private lateinit var appList: RecyclerView
    private lateinit var searchInput: TextInputEditText
    private lateinit var appAdapter: AppListAdapter

    private lateinit var canvas: GestureCanvasView
    private lateinit var stepText: TextView
    private lateinit var dotsText: TextView

    private var mode: String = MODE_NEW
    private var editingId: String? = null
    private var editingMapping: GestureMapping? = null

    private var selPackage: String? = null
    private var selComponent: String? = null
    private var selLabel: String? = null

    /** What a brand-new gesture will do — set from intent extras before [showPicker]/[showDraw]
     *  runs. Irrelevant for REDRAW (the action never changes on redraw, [doSave] copies it from
     *  [editingMapping]) and for CHANGE_APP (always [GestureAction.LAUNCH_APP] by construction). */
    private var pendingAction: GestureAction = GestureAction.LAUNCH_APP
    private var pendingUrl: String? = null

    private val strokes = ArrayList<List<SPoint>>()
    private val strokeSubLengths = ArrayList<List<Int>>()
    private var allApps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gesture_training)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_NEW
        editingId = intent.getStringExtra(EXTRA_ID)
        editingId?.let { id -> editingMapping = GestureStore.all(this).firstOrNull { it.id == id } }

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        pickerContainer = findViewById(R.id.pickerContainer)
        drawContainer = findViewById(R.id.drawContainer)
        appList = findViewById(R.id.appList)
        searchInput = findViewById(R.id.searchInput)
        canvas = findViewById(R.id.captureCanvas)
        stepText = findViewById(R.id.stepText)
        dotsText = findViewById(R.id.dotsText)

        canvas.autoClearMillis = 0L // keep the trail until the next stroke / redo
        canvas.multiStrokeGapMillis = GestureCanvasView.MULTI_STROKE_GAP_MILLIS // tolerate pen lifts
        // The canvas defaults to the home screen's white-over-wallpaper ink. Here it sits on a
        // themed card, where white would be invisible in light mode, so draw in the accent and
        // drop the halo — it exists to survive an unknown wallpaper, which isn't the case here.
        // colorPrimary is fully qualified because nonTransitiveRClass keeps library attrs out
        // of this module's R.
        canvas.strokeColor =
            MaterialColors.getColor(canvas, com.google.android.material.R.attr.colorPrimary)
        canvas.haloColor = Color.TRANSPARENT
        findViewById<Button>(R.id.redoButton).setOnClickListener { redoAttempt() }

        setupPicker()

        pendingAction = intent.getStringExtra(EXTRA_ACTION)
            ?.let { runCatching { GestureAction.valueOf(it) }.getOrNull() }
            ?: GestureAction.LAUNCH_APP
        pendingUrl = intent.getStringExtra(EXTRA_URL)

        when (mode) {
            MODE_REDRAW -> {
                val m = editingMapping ?: run { finish(); return }
                selPackage = m.packageName; selComponent = m.componentName; selLabel = m.label
                showDraw()
            }
            MODE_NEW -> if (pendingAction == GestureAction.LAUNCH_APP) {
                showPicker()
            } else {
                // Nothing to pick for these actions — go straight to drawing the shape.
                selPackage = ""; selComponent = ""
                selLabel = intent.getStringExtra(EXTRA_LABEL)
                    ?: when (pendingAction) {
                        GestureAction.OPEN_DRAWER -> getString(R.string.gesture_action_open_drawer)
                        GestureAction.QUICK_SEARCH -> getString(R.string.gesture_action_quick_search)
                        else -> pendingUrl.orEmpty()
                    }
                showDraw()
            }
            else -> showPicker() // CHANGE_APP
        }
    }

    // --- app picker -------------------------------------------------------

    private fun setupPicker() {
        appList.layoutManager = LinearLayoutManager(this)
        appAdapter = AppListAdapter(scope = lifecycleScope, onClick = { app -> onAppPicked(app) })
        appList.adapter = appAdapter
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                appAdapter.submit(AppRepository.filter(allApps, s?.toString().orEmpty()))
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        refreshApps()
    }

    private fun refreshApps() {
        // Gesture mappings store a bare componentName with no profile, so the picker only offers
        // personal-profile apps — a work-profile pick couldn't be launched later.
        fun show(apps: List<AppInfo>) {
            allApps = apps.filter { it.user == Process.myUserHandle() }
            appAdapter.submit(AppRepository.filter(allApps, searchInput.text?.toString().orEmpty()))
        }
        // Same instant-then-reconcile shape as the drawer: render the cache (or the disk snapshot
        // after a process kill) immediately, and only pay for a scan when one is actually needed.
        AppRepository.cachedOrPrime(this).takeIf { it.isNotEmpty() }?.let { show(it) }
        if (!AppRepository.needsScan()) return
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { AppRepository.load(this@GestureTrainingActivity) }
            show(apps)
        }
    }

    // May be invoked from the LauncherApps callback thread — hop to the main thread first.
    private val onAppsChanged: () -> Unit = { runOnUiThread { refreshApps() } }

    override fun onStart() {
        super.onStart()
        AppRepository.addListener(onAppsChanged)
    }

    override fun onStop() {
        super.onStop()
        AppRepository.removeListener(onAppsChanged)
    }

    private fun onAppPicked(app: AppInfo) {
        selPackage = app.packageName
        selComponent = app.componentName.flattenToString()
        selLabel = app.label

        if (mode == MODE_CHANGE_APP) {
            val m = editingMapping ?: run { finish(); return }
            GestureStore.update(
                this,
                m.copy(packageName = app.packageName, componentName = selComponent!!, label = app.label)
            )
            toast(R.string.gesture_saved)
            finish()
        } else {
            showDraw()
        }
    }

    // --- draw step --------------------------------------------------------

    private fun showPicker() {
        pickerContainer.visibility = View.VISIBLE
        drawContainer.visibility = View.GONE
        title = getString(R.string.pick_app)
    }

    private fun showDraw() {
        // Drop the search keyboard so it can't cover the drawing pad.
        searchInput.clearFocus()
        WindowInsetsControllerCompat(window, searchInput).hide(WindowInsetsCompat.Type.ime())

        pickerContainer.visibility = View.GONE
        drawContainer.visibility = View.VISIBLE
        strokes.clear()
        strokeSubLengths.clear()
        canvas.clearStroke()
        canvas.onStroke = { pts, subStrokeLengths -> onAttemptStroke(pts, subStrokeLengths) }
        updateStepUi()
    }

    private fun updateStepUi() {
        val filled = strokes.size
        val next = (filled + 1).coerceAtMost(REQUIRED)
        stepText.text = getString(R.string.draw_step, next, REQUIRED)
        dotsText.text = "●".repeat(filled) + "○".repeat(REQUIRED - filled)
    }

    private fun redoAttempt() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1)
            strokeSubLengths.removeAt(strokeSubLengths.size - 1)
        }
        canvas.clearStroke()
        updateStepUi()
    }

    private fun onAttemptStroke(points: List<PointF>, subStrokeLengths: List<Int>) {
        // Already have all attempts (e.g. confirm dialog was cancelled) — use Redo to change them.
        if (strokes.size >= REQUIRED) { canvas.clearStroke(); return }
        val pts = points.toPt()
        if (!OneDollarRecognizer.isStrokeUsable(pts)) {
            toast(R.string.stroke_too_short)
            canvas.clearStroke()
            return
        }
        // Consistency check against the first attempt.
        if (strokes.isNotEmpty()) {
            val first = GestureTemplate("first", strokes[0].toPt(), strokeSubLengths[0].size)
            val score = OneDollarRecognizer.recognize(pts, listOf(first), subStrokeLengths.size).score
            if (score < CONSISTENCY_MIN) {
                AlertDialog.Builder(this)
                    .setMessage(R.string.strokes_differ)
                    .setPositiveButton(R.string.redo_attempt) { _, _ -> canvas.clearStroke() }
                    .setNegativeButton(R.string.keep) { _, _ -> acceptStroke(points, subStrokeLengths) }
                    .showWithFont()
                return
            }
        }
        acceptStroke(points, subStrokeLengths)
    }

    private fun acceptStroke(points: List<PointF>, subStrokeLengths: List<Int>) {
        strokes.add(points.map { SPoint(it.x, it.y) })
        strokeSubLengths.add(subStrokeLengths)
        updateStepUi()
        if (strokes.size >= REQUIRED) confirmAndSave()
        // Otherwise leave the trail; the next touch-down clears it for the next attempt.
    }

    // --- confirm & save ---------------------------------------------------

    private fun confirmAndSave() {
        val collisionLabel = detectCollision()
        val view = layoutInflater.inflate(R.layout.dialog_confirm_gesture, null)
        view.findViewById<StrokePreviewView>(R.id.confirmPreview)
            .setStroke(strokes[0].map { PointF(it.x, it.y) }, strokeSubLengths[0])
        val message = if (collisionLabel != null) {
            getString(R.string.collision_warning, collisionLabel)
        } else {
            getString(R.string.save_prompt, selLabel.orEmpty())
        }
        view.findViewById<TextView>(R.id.confirmMessage).text = message

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton(if (collisionLabel != null) R.string.save_anyway else R.string.save) { _, _ -> doSave() }
            .setNegativeButton(R.string.cancel, null) // stays at 3 strokes; user can Redo
            .showWithFont()
    }

    /** Returns the label of an existing gesture the new strokes strongly match, or null. */
    private fun detectCollision(): String? {
        val others = GestureStore.all(this).filter { it.id != editingId }
        if (others.isEmpty()) return null
        val templates = others.flatMap { m ->
            m.templates.mapIndexed { idx, stroke ->
                GestureTemplate(
                    m.id,
                    stroke.toPt(),
                    m.subStrokeLengths.getOrNull(idx)?.size?.coerceAtLeast(1) ?: 1
                )
            }
        }
        val threshold = Prefs.matchThreshold(this)
        var bestScore = 0.0
        var bestId: String? = null
        for ((idx, stroke) in strokes.withIndex()) {
            val r = OneDollarRecognizer.recognize(
                stroke.toPt(), templates, strokeSubLengths[idx].size.coerceAtLeast(1)
            )
            if (r.score > bestScore) { bestScore = r.score; bestId = r.name }
        }
        return if (bestScore >= threshold && bestId != null) {
            others.firstOrNull { it.id == bestId }?.label
        } else null
    }

    private fun doSave() {
        val pkg = selPackage ?: return
        val comp = selComponent ?: return
        val label = selLabel ?: return
        val isMultiStroke = strokeSubLengths.any { it.size > 1 }
        when (mode) {
            MODE_REDRAW -> {
                val m = editingMapping ?: return
                GestureStore.update(
                    this,
                    m.copy(
                        templates = strokes.toList(),
                        isMultiStroke = isMultiStroke,
                        subStrokeLengths = strokeSubLengths.toList()
                    )
                )
            }
            else -> {
                GestureStore.add(
                    this,
                    GestureMapping(
                        id = UUID.randomUUID().toString(),
                        packageName = pkg,
                        componentName = comp,
                        label = label,
                        templates = strokes.toList(),
                        isMultiStroke = isMultiStroke,
                        subStrokeLengths = strokeSubLengths.toList(),
                        action = pendingAction,
                        url = pendingUrl
                    )
                )
            }
        }
        toast(R.string.gesture_saved)
        finish()
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun List<PointF>.toPt(): List<Pt> = map { Pt(it.x.toDouble(), it.y.toDouble()) }

    @JvmName("spointToPt")
    private fun List<SPoint>.toPt(): List<Pt> = map { Pt(it.x.toDouble(), it.y.toDouble()) }

    companion object {
        private const val REQUIRED = 3
        private const val CONSISTENCY_MIN = 0.5

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ID = "mapping_id"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_URL = "url"
        private const val EXTRA_LABEL = "label"
        private const val MODE_NEW = "new"
        private const val MODE_REDRAW = "redraw"
        private const val MODE_CHANGE_APP = "change_app"

        /** [action] defaults to [GestureAction.LAUNCH_APP] (today's existing app-picker flow).
         *  [url]/[label] are only meaningful for [GestureAction.OPEN_URL] — [label] is what the
         *  draw step and the saved gesture list will display (typically the URL's host). */
        fun newGestureIntent(
            context: Context,
            action: GestureAction = GestureAction.LAUNCH_APP,
            url: String? = null,
            label: String? = null
        ): Intent =
            Intent(context, GestureTrainingActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_NEW)
                .putExtra(EXTRA_ACTION, action.name)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_LABEL, label)

        fun redrawIntent(context: Context, mappingId: String): Intent =
            Intent(context, GestureTrainingActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_REDRAW).putExtra(EXTRA_ID, mappingId)

        fun changeAppIntent(context: Context, mappingId: String): Intent =
            Intent(context, GestureTrainingActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_CHANGE_APP).putExtra(EXTRA_ID, mappingId)
    }
}
