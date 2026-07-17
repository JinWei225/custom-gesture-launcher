package dev.neffly.gesturelauncher.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.GestureAction
import dev.neffly.gesturelauncher.data.GestureStore

/**
 * Collects a URL for an "Open a URL" gesture, then either:
 *  - **New** (reached from [GestureActionChooserActivity]): hands off to
 *    [GestureTrainingActivity] to draw the shape, same self-driven-slide technique as the other
 *    settings overlay screens.
 *  - **Edit** (reached from the gesture list's "Edit URL" menu item, [EXTRA_MAPPING_ID] set):
 *    updates the existing mapping's URL directly and closes — no redraw, matching how "Change
 *    app" already works for [GestureAction.LAUNCH_APP] mappings.
 */
class GestureUrlEntryActivity : AppCompatActivity() {

    private lateinit var urlEntryRoot: View
    private lateinit var urlInput: TextInputEditText
    private var isClosing = false
    private var editingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        setContentView(R.layout.activity_gesture_url_entry)

        urlEntryRoot = findViewById(R.id.urlEntryRoot)
        urlEntryRoot.translationX = resources.displayMetrics.widthPixels.toFloat()
        urlEntryRoot.animate()
            .translationX(0f)
            .setDuration(SLIDE_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        urlInput = findViewById(R.id.urlInput)
        editingId = intent.getStringExtra(EXTRA_MAPPING_ID)
        editingId?.let { id ->
            val mapping = GestureStore.all(this).firstOrNull { it.id == id }
            urlInput.setText(mapping?.url.orEmpty())
        }

        findViewById<View>(R.id.saveButton).setOnClickListener { onSave() }
    }

    private fun onSave() {
        val typed = urlInput.text?.toString()?.trim().orEmpty()
        if (typed.isEmpty()) {
            Toast.makeText(this, R.string.gesture_url_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = normalizeUrl(typed)

        val id = editingId
        if (id != null) {
            val mapping = GestureStore.all(this).firstOrNull { it.id == id } ?: return finish()
            GestureStore.update(this, mapping.copy(url = normalized, label = hostLabel(normalized)))
            Toast.makeText(this, R.string.gesture_saved, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        startActivity(
            GestureTrainingActivity.newGestureIntent(
                this, GestureAction.OPEN_URL, url = normalized, label = hostLabel(normalized)
            )
        )
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(0, 0)
        }
        // Unlike the chooser (which stays on the back stack so Back returns to it), this screen
        // has nothing left to collect once the URL is in — finish so Back from the draw step
        // lands on the chooser directly instead of bouncing through this now-stale form again.
        finish()
    }

    private fun normalizeUrl(raw: String): String =
        if (Uri.parse(raw).scheme == null) "https://$raw" else raw

    private fun hostLabel(url: String): String = Uri.parse(url).host ?: url

    override fun finish() {
        if (isClosing || isFinishing) { super.finish(); return }
        isClosing = true
        urlEntryRoot.animate()
            .translationX(resources.displayMetrics.widthPixels.toFloat())
            .setDuration(SLIDE_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { super.finish() }
            .start()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val SLIDE_DURATION_MS = 260L
        private const val EXTRA_MAPPING_ID = "mapping_id"

        fun editIntent(context: Context, mappingId: String): Intent =
            Intent(context, GestureUrlEntryActivity::class.java)
                .putExtra(EXTRA_MAPPING_ID, mappingId)
    }
}
