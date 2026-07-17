package dev.neffly.gesturelauncher.settings

import android.content.ComponentName
import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.GestureMapping
import dev.neffly.gesturelauncher.data.GestureStore
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.ui.GestureCanvasView
import dev.neffly.gesturelauncher.unistroke.GestureTemplate
import dev.neffly.gesturelauncher.unistroke.OneDollarRecognizer
import dev.neffly.gesturelauncher.unistroke.Pt

/**
 * Merges the recognition-sensitivity slider with the gesture test/scratch area on one screen:
 * drag the slider (applied live, no separate Save step) then immediately redraw a gesture to see
 * whether it now passes — instead of bouncing between a separate dialog and a separate test
 * screen to find the right threshold.
 */
class GestureSensitivityActivity : AppCompatActivity() {

    private lateinit var canvas: GestureCanvasView
    private lateinit var resultView: TextView
    private lateinit var sensitivityValue: TextView

    private var templates: List<GestureTemplate> = emptyList()
    private var mappingsById: Map<String, GestureMapping> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gesture_sensitivity)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        resultView = findViewById(R.id.testResult)
        canvas = findViewById(R.id.testCanvas)
        canvas.autoClearMillis = 0L // persist the trail so the result stays legible next to it
        canvas.onStroke = { points, subStrokes -> onTestStroke(points, subStrokes.size) }

        sensitivityValue = findViewById(R.id.sensitivityValue)
        val sensitivitySeek = findViewById<SeekBar>(R.id.sensitivitySeek)
        // Map threshold 0.60..0.95 to progress 0..35.
        sensitivitySeek.progress =
            ((Prefs.matchThreshold(this) * 100).toInt() - 60).coerceIn(0, sensitivitySeek.max)
        renderThreshold(sensitivitySeek.progress)
        sensitivitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                renderThreshold(progress)
                if (fromUser) Prefs.setMatchThreshold(this@GestureSensitivityActivity, (progress + 60) / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun renderThreshold(progress: Int) {
        sensitivityValue.text = "${progress + 60}%"
    }

    override fun onResume() {
        super.onResume()
        rebuildTemplates()
    }

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
        canvas.multiStrokeGapMillis =
            if (mappings.any { it.isMultiStroke }) GestureCanvasView.MULTI_STROKE_GAP_MILLIS else 0L
    }

    private fun onTestStroke(points: List<PointF>, subStrokeCount: Int) {
        if (templates.isEmpty()) {
            showResult(getString(R.string.test_no_gestures), null)
            return
        }
        val pts = points.map { Pt(it.x.toDouble(), it.y.toDouble()) }
        if (!OneDollarRecognizer.isStrokeUsable(pts)) {
            showResult(getString(R.string.stroke_too_short), null)
            return
        }
        val result = OneDollarRecognizer.recognize(pts, templates, subStrokeCount)
        val threshold = Prefs.matchThreshold(this)
        val name = result.name
        if (name == null) {
            showResult(getString(R.string.test_no_match), null)
            return
        }
        val mapping = mappingsById[name]
        val label = mapping?.label ?: name
        val percent = (result.score * 100).toInt()
        val pass = result.score >= threshold
        val detail = if (pass) {
            getString(R.string.test_pass)
        } else {
            getString(R.string.test_fail, (threshold * 100).toInt())
        }
        var resultText = getString(R.string.test_result, label, percent, detail)
        if (mapping != null && isComponentStale(mapping.componentName)) {
            resultText += getString(R.string.test_alias_refreshed)
        }
        showResult(resultText, pass)
    }

    /**
     * True when the mapping's frozen componentName (captured at training time) no longer
     * resolves, but its package is still installed — i.e. the app swapped its enabled
     * launcher-activity alias since training (seasonal icon apps like Duolingo do this). Home
     * screen launches already fall back to the package's current launcher activity in this case
     * (see AppRepository.launch), so this isn't a failure — just worth surfacing for transparency.
     */
    private fun isComponentStale(componentName: String): Boolean {
        val comp = ComponentName.unflattenFromString(componentName) ?: return false
        val componentResolves = runCatching { packageManager.getActivityInfo(comp, 0) }.isSuccess
        if (componentResolves) return false
        return packageManager.getLaunchIntentForPackage(comp.packageName) != null
    }

    private fun showResult(text: String, pass: Boolean?) {
        resultView.text = text
        resultView.setTextColor(
            when (pass) {
                true -> Color.parseColor("#4CAF50")
                false -> Color.parseColor("#F44336")
                null -> Color.GRAY
            }
        )
    }
}
