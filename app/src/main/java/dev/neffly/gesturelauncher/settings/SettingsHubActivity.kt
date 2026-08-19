package dev.neffly.gesturelauncher.settings

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.BackupData
import dev.neffly.gesturelauncher.data.BackupManager
import dev.neffly.gesturelauncher.data.FontStore
import dev.neffly.gesturelauncher.data.GestureStore
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.drawer.AppRepository
import dev.neffly.gesturelauncher.search.FilePermissions
import dev.neffly.gesturelauncher.ui.BaseActivity
import dev.neffly.gesturelauncher.ui.FontEngine
import dev.neffly.gesturelauncher.ui.overrideNextTransition
import dev.neffly.gesturelauncher.ui.overrideOwnTransitions
import dev.neffly.gesturelauncher.ui.showWithFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Settings entry point: sections for Gestures ([GestureSettingsActivity], sensitivity/test via
 * [GestureSensitivityActivity]) and Backup ([BackupManager]). Reached from the app drawer's
 * overflow menu.
 *
 * Slides in from the right over the drawer and back out on close — same translucent-window +
 * self-driven-animation technique [dev.neffly.gesturelauncher.drawer.AppDrawerActivity] uses (OEM
 * skins like HyperOS otherwise replace the requested transition with their own "app opening" zoom).
 */
class SettingsHubActivity : BaseActivity() {

    private lateinit var hubRoot: View
    private lateinit var gesturesSubtitle: TextView
    private lateinit var autoKeyboardSwitch: MaterialSwitch
    private lateinit var hapticFeedbackSwitch: MaterialSwitch
    private lateinit var themeSubtitle: TextView
    private lateinit var batterySubtitle: TextView
    private lateinit var fontSubtitle: TextView
    private lateinit var fontScaleSubtitle: TextView
    private lateinit var searchFilesSwitch: MaterialSwitch
    private lateinit var searchWebSwitch: MaterialSwitch
    private lateinit var quickSearchSwitch: MaterialSwitch
    private lateinit var quickSearchTriggerRow: View
    private lateinit var quickSearchTriggerTitle: TextView
    private lateinit var quickSearchTriggerSubtitle: TextView
    private var isClosing = false

    /** The font scale in force when an import started, so [applyImportedAppearance] can tell
     *  whether the imported one is actually different before rebuilding the screen. */
    private var importedFromScale = 1f

    /** True while waiting to come back from the all-files-access screen, so onResume knows to turn
     *  the toggle back off if nothing was granted there. */
    private var awaitingFilePermission = false

    private val filePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Prefs.setSearchFiles(this, granted)
            searchFilesSwitch.isChecked = granted
            if (!granted) {
                Toast.makeText(this, R.string.search_files_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) doExport(uri)
        }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) doImport(uri)
        }
    private val fontLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFont(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideOwnTransitions()
        setContentView(R.layout.activity_settings_hub)

        hubRoot = findViewById(R.id.settingsHubRoot)
        // Only slide in on a genuine open. Changing the theme recreates this activity, and
        // replaying the entry animation then would read as the panel being re-opened.
        if (savedInstanceState == null) {
            hubRoot.translationX = resources.displayMetrics.widthPixels.toFloat()
            hubRoot.animate()
                .translationX(0f)
                .setDuration(SLIDE_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        gesturesSubtitle = findViewById(R.id.gesturesSubtitle)

        findViewById<View>(R.id.gesturesRow).setOnClickListener {
            startActivity(Intent(this, GestureSettingsActivity::class.java))
        }
        findViewById<View>(R.id.sensitivityRow).setOnClickListener {
            startActivity(Intent(this, GestureSensitivityActivity::class.java))
        }
        findViewById<View>(R.id.exportRow).setOnClickListener {
            exportLauncher.launch("gesture-launcher-backup.json")
        }
        findViewById<View>(R.id.importRow).setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }

        autoKeyboardSwitch = findViewById(R.id.autoKeyboardSwitch)
        findViewById<View>(R.id.autoKeyboardRow).setOnClickListener {
            val enabled = !Prefs.autoKeyboard(this)
            Prefs.setAutoKeyboard(this, enabled)
            autoKeyboardSwitch.isChecked = enabled
        }

        hapticFeedbackSwitch = findViewById(R.id.hapticFeedbackSwitch)
        findViewById<View>(R.id.hapticFeedbackRow).setOnClickListener {
            val enabled = !Prefs.hapticFeedback(this)
            Prefs.setHapticFeedback(this, enabled)
            hapticFeedbackSwitch.isChecked = enabled
        }
        themeSubtitle = findViewById(R.id.themeSubtitle)
        findViewById<View>(R.id.themeRow).setOnClickListener { showThemeDialog() }

        fontSubtitle = findViewById(R.id.fontSubtitle)
        findViewById<View>(R.id.fontRow).setOnClickListener { showFontDialog() }

        fontScaleSubtitle = findViewById(R.id.fontScaleSubtitle)
        findViewById<View>(R.id.fontScaleRow).setOnClickListener { showFontScaleDialog() }

        searchFilesSwitch = findViewById(R.id.searchFilesSwitch)
        findViewById<View>(R.id.searchFilesRow).setOnClickListener { toggleSearchFiles() }

        searchWebSwitch = findViewById(R.id.searchWebSwitch)
        findViewById<View>(R.id.searchWebRow).setOnClickListener {
            val enabled = !Prefs.searchWeb(this)
            Prefs.setSearchWeb(this, enabled)
            searchWebSwitch.isChecked = enabled
        }

        quickSearchSwitch = findViewById(R.id.quickSearchSwitch)
        findViewById<View>(R.id.quickSearchRow).setOnClickListener {
            val enabled = !Prefs.quickSearchEnabled(this)
            Prefs.setQuickSearchEnabled(this, enabled)
            quickSearchSwitch.isChecked = enabled
            updateQuickSearchTrigger()
        }

        quickSearchTriggerRow = findViewById(R.id.quickSearchTriggerRow)
        quickSearchTriggerTitle = findViewById(R.id.quickSearchTriggerTitle)
        quickSearchTriggerSubtitle = findViewById(R.id.quickSearchTriggerSubtitle)
        quickSearchTriggerRow.setOnClickListener { openAssistantSettings() }

        findViewById<View>(R.id.defaultLauncherRow).setOnClickListener {
            openDefaultLauncherSettings()
        }

        batterySubtitle = findViewById(R.id.batterySubtitle)
        findViewById<View>(R.id.batteryRow).setOnClickListener {
            openBatteryOptimizationSettings()
        }

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        findViewById<TextView>(R.id.aboutRow).text = getString(R.string.about_version, version)
    }

    override fun onResume() {
        super.onResume()
        gesturesSubtitle.text = getString(R.string.gestures_row_subtitle, GestureStore.all(this).size)
        autoKeyboardSwitch.isChecked = Prefs.autoKeyboard(this)
        hapticFeedbackSwitch.isChecked = Prefs.hapticFeedback(this)
        themeSubtitle.setText(themeLabelFor(Prefs.themeMode(this)))
        // Re-read on every resume, not just at create: the usual flow is tapping the row, changing
        // it in system settings, and coming straight back here.
        batterySubtitle.setText(
            if (isBatteryOptimizationExempt()) R.string.battery_exempt else R.string.battery_optimized
        )
        fontSubtitle.text = Prefs.fontName(this) ?: getString(R.string.font_system_default)
        fontScaleSubtitle.text = fontScaleLabel(Prefs.fontScale(this))

        // All-files access is granted on a settings screen we can't get a result from, so the
        // toggle is reconciled against the real permission state on the way back.
        if (awaitingFilePermission) {
            awaitingFilePermission = false
            val granted = FilePermissions.isGranted(this)
            Prefs.setSearchFiles(this, granted)
            if (!granted) {
                Toast.makeText(this, R.string.search_files_permission_denied, Toast.LENGTH_LONG).show()
            }
        }
        // A permission revoked in system settings has to switch the feature off too, or search
        // would silently return nothing while the row claims it's on.
        if (Prefs.searchFiles(this) && !FilePermissions.isGranted(this)) {
            Prefs.setSearchFiles(this, false)
        }
        searchFilesSwitch.isChecked = Prefs.searchFiles(this)
        searchWebSwitch.isChecked = Prefs.searchWeb(this)
        quickSearchSwitch.isChecked = Prefs.quickSearchEnabled(this)
        updateQuickSearchTrigger()
    }

    /**
     * Turning file search on has to clear the storage grant first — the toggle is meaningless
     * without it, so it only sticks once the permission is actually held.
     */
    private fun toggleSearchFiles() {
        if (Prefs.searchFiles(this)) {
            Prefs.setSearchFiles(this, false)
            searchFilesSwitch.isChecked = false
            return
        }
        if (FilePermissions.isGranted(this)) {
            Prefs.setSearchFiles(this, true)
            searchFilesSwitch.isChecked = true
            return
        }
        val runtimePermission = FilePermissions.runtimePermission
        if (runtimePermission != null) {
            filePermissionLauncher.launch(runtimePermission)
        } else {
            awaitingFilePermission = true
            openAllFilesAccessSettings()
        }
    }

    /** Greys the trigger row out while the overlay is off, and reports whether the role is held. */
    private fun updateQuickSearchTrigger() {
        val enabled = Prefs.quickSearchEnabled(this)
        quickSearchTriggerRow.isEnabled = enabled
        quickSearchTriggerTitle.isEnabled = enabled
        quickSearchTriggerSubtitle.isEnabled = enabled
        quickSearchTriggerRow.alpha = if (enabled) 1f else DISABLED_ROW_ALPHA
        quickSearchTriggerSubtitle.setText(
            if (isAssistantRoleHeld()) R.string.quick_search_trigger_granted
            else R.string.quick_search_trigger_missing
        )
    }

    /** "Default (100%)" for 1.0, a bare percentage otherwise — the default is worth naming so it's
     *  obvious which entry undoes any experimenting. */
    private fun fontScaleLabel(scale: Float): String =
        if (scale == 1f) {
            getString(R.string.font_size_default)
        } else {
            getString(R.string.font_size_percent, (scale * 100).roundToInt())
        }

    private fun showFontScaleDialog() {
        val labels = FONT_SCALES.map { fontScaleLabel(it) }.toTypedArray()
        val current = Prefs.fontScale(this)
        // Nearest entry rather than indexOf: a value restored from an older build (or a future one
        // with a different set of steps) should still preselect something sensible.
        val selected = FONT_SCALES.indices.minBy { kotlin.math.abs(FONT_SCALES[it] - current) }

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_font_size)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                Prefs.setFontScale(this, FONT_SCALES[which])
                // Dismiss before recreating, for the same reason as the theme dialog above:
                // tearing the window down with the dialog attached leaks it.
                dialog.dismiss()
                FontEngine.notifyScaleChanged()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .showWithFont()
    }

    private fun showFontDialog() {
        val options = arrayOf(
            getString(R.string.font_choose_file),
            getString(R.string.font_use_system)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_font)
            .setItems(options) { _, which ->
                if (which == 0) fontLauncher.launch(FontStore.PICKER_MIME_TYPES) else clearFont()
            }
            .setNegativeButton(R.string.cancel, null)
            .showWithFont()
    }

    /** Copies, validates and activates the picked font. The import touches the filesystem, so it
     *  runs off the main thread; everything after it is UI work and hops back. */
    private fun importFont(uri: Uri) {
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                FontStore.displayName(this@SettingsHubActivity, uri)
            }
            val result = withContext(Dispatchers.IO) {
                FontStore.import(this@SettingsHubActivity, uri)
            }
            result.fold(
                onSuccess = { typeface ->
                    // The name is only ever a label for the settings row, but it also doubles as
                    // the "a custom font is set" flag, so it must never end up blank.
                    Prefs.setFontName(
                        this@SettingsHubActivity,
                        name?.takeIf { it.isNotBlank() } ?: getString(R.string.font_custom)
                    )
                    applyFont(typeface)
                },
                onFailure = { error ->
                    AlertDialog.Builder(this@SettingsHubActivity)
                        .setTitle(R.string.font_import_failed)
                        .setMessage(
                            getString(
                                R.string.font_import_failed_message,
                                error.message.orEmpty()
                            )
                        )
                        .setPositiveButton(R.string.ok, null)
                        .showWithFont()
                }
            )
        }
    }

    private fun clearFont() {
        FontStore.clear(this)
        Prefs.setFontName(this, null)
        applyFont(null)
    }

    /** Swaps the process-wide typeface and rebuilds this screen against it. Everything below in
     *  the stack notices via the version check in [BaseActivity.onResume]. */
    private fun applyFont(typeface: Typeface?) {
        FontEngine.set(typeface)
        recreate()
    }

    /** Label for a MODE_NIGHT_* constant. Anything unrecognised (an older build's value, or a
     *  mode we don't offer) falls back to "follow system", which is also the stored default. */
    @StringRes
    private fun themeLabelFor(mode: Int): Int = when (mode) {
        AppCompatDelegate.MODE_NIGHT_NO -> R.string.theme_light
        AppCompatDelegate.MODE_NIGHT_YES -> R.string.theme_dark
        else -> R.string.theme_system
    }

    private fun showThemeDialog() {
        val modes = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
        val labels = modes.map { getString(themeLabelFor(it)) }.toTypedArray()
        // Fall back to follow-system for an unrecognised stored value, matching what
        // themeLabelFor already displays for one — otherwise the row and the dialog's
        // preselected item would disagree.
        val current = modes.indexOf(Prefs.themeMode(this))
            .takeIf { it >= 0 }
            ?: modes.indexOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_theme)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val mode = modes[which]
                Prefs.setThemeMode(this, mode)
                // Dismiss before applying: setDefaultNightMode recreates this activity
                // synchronously, and tearing the window down with the dialog still attached
                // leaks it (and logs a WindowLeaked warning).
                dialog.dismiss()
                AppCompatDelegate.setDefaultNightMode(mode)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showWithFont()
    }

    override fun finish() {
        if (isClosing || isFinishing) { super.finish(); return }
        isClosing = true
        hubRoot.animate()
            .translationX(resources.displayMetrics.widthPixels.toFloat())
            .setDuration(SLIDE_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { super.finish() }
            .start()
        overrideNextTransition()
    }

    private fun doExport(uri: Uri) {
        val result = BackupManager.writeTo(this, uri, BackupManager.buildBackup(this))
        val message = if (result.isSuccess) R.string.export_success else R.string.export_failed
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun doImport(uri: Uri) {
        BackupManager.readFrom(this, uri).fold(
            onSuccess = { data -> showImportConfirmDialog(data) },
            onFailure = {
                AlertDialog.Builder(this)
                    .setTitle(R.string.import_confirm_title)
                    .setMessage(R.string.import_invalid_file)
                    .setPositiveButton(R.string.ok, null)
                    .showWithFont()
            }
        )
    }

    private fun showImportConfirmDialog(data: BackupData) {
        val message = getString(R.string.import_confirm_message, data.gestures.size, data.appTags.size)
        AlertDialog.Builder(this)
            .setTitle(R.string.import_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.import_replace) { _, _ -> applyImport(data, replace = true) }
            .setNeutralButton(R.string.import_merge) { _, _ -> applyImport(data, replace = false) }
            .setNegativeButton(R.string.cancel, null)
            .showWithFont()
    }

    private fun applyImport(data: BackupData, replace: Boolean) {
        importedFromScale = Prefs.fontScale(this)
        val result = BackupManager.apply(this, data, replace)
        AppRepository.invalidate()
        gesturesSubtitle.text = getString(R.string.gestures_row_subtitle, GestureStore.all(this).size)
        autoKeyboardSwitch.isChecked = Prefs.autoKeyboard(this)
        hapticFeedbackSwitch.isChecked = Prefs.hapticFeedback(this)

        var message = getString(R.string.import_result_message, result.gesturesImported, result.tagsImported)
        if (result.missingApps.isNotEmpty()) {
            message += getString(
                R.string.import_result_missing_apps,
                result.missingApps.size,
                result.missingApps.joinToString("\n") { "• $it" }
            )
        }
        // Theme and font size land last, once the summary has been read: applying either recreates
        // this activity, which would tear the dialog down with it (the same leak the theme and
        // font-size dialogs above dismiss themselves to avoid). Cancel is covered as well as the
        // button — the dismiss slot belongs to showWithFont.
        AlertDialog.Builder(this)
            .setTitle(R.string.import_result_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { _, _ -> applyImportedAppearance() }
            .setOnCancelListener { applyImportedAppearance() }
            .showWithFont()
    }

    /**
     * Makes an imported theme and font size visible.
     *
     * setDefaultNightMode recreates this activity by itself, but only when the mode actually
     * changed — so the font-size branch has to ask for its own recreate, and does it only when the
     * scale really moved, to avoid a pointless rebuild on every import.
     */
    private fun applyImportedAppearance() {
        val scaleChanged = Prefs.fontScale(this) != importedFromScale
        if (scaleChanged) FontEngine.notifyScaleChanged()
        AppCompatDelegate.setDefaultNightMode(Prefs.themeMode(this))
        if (scaleChanged) recreate()
    }

    companion object {
        private const val SLIDE_DURATION_MS = 260L

        /** Faded, not hidden: the row stays visible so it's clear what the toggle above unlocks. */
        private const val DISABLED_ROW_ALPHA = 0.4f

        /** Offered font-size multipliers. Skewed upward because the reason this setting exists is
         *  imported fonts that render small at a given point size; 0.9 is there so the drawer can
         *  still be made denser if someone wants that. */
        private val FONT_SCALES = floatArrayOf(0.9f, 1f, 1.1f, 1.25f, 1.4f, 1.6f)
    }
}
