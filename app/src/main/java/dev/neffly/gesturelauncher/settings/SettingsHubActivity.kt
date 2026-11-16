package dev.neffly.gesturelauncher.settings

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.skydoves.balloon.Balloon
import dev.neffly.gesturelauncher.MainActivity
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.BackupData
import dev.neffly.gesturelauncher.data.BackupManager
import dev.neffly.gesturelauncher.data.FontStore
import dev.neffly.gesturelauncher.data.GestureStore
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.drawer.AppRepository
import dev.neffly.gesturelauncher.search.MapSearch
import dev.neffly.gesturelauncher.search.WebSearch
import dev.neffly.gesturelauncher.accessibility.LauncherAccessibilityService
import dev.neffly.gesturelauncher.accessibility.requestAccessibilityAccess
import dev.neffly.gesturelauncher.onboarding.Backdrop
import dev.neffly.gesturelauncher.onboarding.Placement
import dev.neffly.gesturelauncher.onboarding.TourBalloon
import dev.neffly.gesturelauncher.onboarding.TourCard
import dev.neffly.gesturelauncher.ui.FontEngine
import dev.neffly.gesturelauncher.ui.SlidePanelActivity
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
class SettingsHubActivity : SlidePanelActivity() {

    private lateinit var gesturesSubtitle: TextView
    private lateinit var autoKeyboardSwitch: MaterialSwitch
    private lateinit var hapticFeedbackSwitch: MaterialSwitch
    private lateinit var widgetsPageSwitch: MaterialSwitch
    private lateinit var notesPageSwitch: MaterialSwitch
    private lateinit var searchSideSubtitle: TextView
    private lateinit var themeSubtitle: TextView
    private lateinit var batterySubtitle: TextView
    private lateinit var fontSubtitle: TextView
    private lateinit var fontScaleSubtitle: TextView
    private lateinit var searchEngineSubtitle: TextView
    private lateinit var mapsAppRow: View
    private lateinit var mapsAppSubtitle: TextView
    private lateinit var voiceWordsSubtitle: TextView
    private lateinit var quickSearchSwitch: MaterialSwitch
    private lateinit var quickSearchRow: View
    /** The guide pop-up open over a row, if any — see [pointAtQuickSearch]. */
    private var guide: Balloon? = null
    private lateinit var quickSearchTriggerRow: View
    private lateinit var quickSearchTriggerTitle: TextView
    private lateinit var quickSearchTriggerSubtitle: TextView
    private lateinit var keyboardShortcutSwitch: MaterialSwitch
    private lateinit var keyboardShortcutRow: View
    private lateinit var keyboardShortcutTitle: TextView
    private lateinit var keyboardShortcutSubtitle: TextView
    private lateinit var keyboardShortcutAccessRow: View
    private lateinit var keyboardShortcutAccessTitle: TextView
    private lateinit var keyboardShortcutAccessSubtitle: TextView

    /** The font scale in force when an import started, so [applyImportedAppearance] can tell
     *  whether the imported one is actually different before rebuilding the screen. */
    private var importedFromScale = 1f

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

        slideIn(findViewById(R.id.settingsHubRoot), savedInstanceState)

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

        // The home screen rebuilds itself with the new set of pages when it next comes back.
        widgetsPageSwitch = findViewById(R.id.widgetsPageSwitch)
        findViewById<View>(R.id.widgetsPageRow).setOnClickListener {
            val enabled = !Prefs.widgetsPage(this)
            Prefs.setWidgetsPage(this, enabled)
            widgetsPageSwitch.isChecked = enabled
        }
        notesPageSwitch = findViewById(R.id.notesPageSwitch)
        findViewById<View>(R.id.notesPageRow).setOnClickListener {
            val enabled = !Prefs.notesPage(this)
            Prefs.setNotesPage(this, enabled)
            notesPageSwitch.isChecked = enabled
        }

        hapticFeedbackSwitch = findViewById(R.id.hapticFeedbackSwitch)
        findViewById<View>(R.id.hapticFeedbackRow).setOnClickListener {
            val enabled = !Prefs.hapticFeedback(this)
            Prefs.setHapticFeedback(this, enabled)
            hapticFeedbackSwitch.isChecked = enabled
        }
        searchSideSubtitle = findViewById(R.id.searchSideSubtitle)
        findViewById<View>(R.id.searchSideRow).setOnClickListener { showSearchSideDialog() }

        themeSubtitle = findViewById(R.id.themeSubtitle)
        findViewById<View>(R.id.themeRow).setOnClickListener { showThemeDialog() }

        fontSubtitle = findViewById(R.id.fontSubtitle)
        findViewById<View>(R.id.fontRow).setOnClickListener { showFontDialog() }

        fontScaleSubtitle = findViewById(R.id.fontScaleSubtitle)
        findViewById<View>(R.id.fontScaleRow).setOnClickListener { showFontScaleDialog() }

        searchEngineSubtitle = findViewById(R.id.searchEngineSubtitle)
        voiceWordsSubtitle = findViewById(R.id.voiceWordsSubtitle)
        findViewById<View>(R.id.voiceWordsRow).setOnClickListener { showVoiceWordsDialog() }
        showVoiceWords()
        findViewById<View>(R.id.searchEngineRow).setOnClickListener { showSearchEngineDialog() }
        mapsAppRow = findViewById(R.id.mapsAppRow)
        mapsAppSubtitle = findViewById(R.id.mapsAppSubtitle)
        mapsAppRow.setOnClickListener { showMapsAppDialog() }

        quickSearchSwitch = findViewById(R.id.quickSearchSwitch)
        quickSearchRow = findViewById(R.id.quickSearchRow)
        quickSearchRow.setOnClickListener {
            val enabled = !Prefs.quickSearchEnabled(this)
            Prefs.setQuickSearchEnabled(this, enabled)
            quickSearchSwitch.isChecked = enabled
            updateQuickSearchTrigger()
            // The keyboard shortcut opens this same window, so it follows the master switch too.
            updateKeyboardShortcut()
            if (enabled) explainSettingsAfterQuickSearch()
        }

        quickSearchTriggerRow = findViewById(R.id.quickSearchTriggerRow)
        quickSearchTriggerTitle = findViewById(R.id.quickSearchTriggerTitle)
        quickSearchTriggerSubtitle = findViewById(R.id.quickSearchTriggerSubtitle)
        quickSearchTriggerRow.setOnClickListener { openAssistantSettings() }

        keyboardShortcutRow = findViewById(R.id.keyboardShortcutRow)
        keyboardShortcutTitle = findViewById(R.id.keyboardShortcutTitle)
        keyboardShortcutSubtitle = findViewById(R.id.keyboardShortcutSubtitle)
        keyboardShortcutSwitch = findViewById(R.id.keyboardShortcutSwitch)
        keyboardShortcutRow.setOnClickListener {
            val enabled = !Prefs.keyboardShortcutEnabled(this)
            Prefs.setKeyboardShortcutEnabled(this, enabled)
            keyboardShortcutSwitch.isChecked = enabled
            updateKeyboardShortcut()
        }

        keyboardShortcutAccessRow = findViewById(R.id.keyboardShortcutAccessRow)
        keyboardShortcutAccessTitle = findViewById(R.id.keyboardShortcutAccessTitle)
        keyboardShortcutAccessSubtitle = findViewById(R.id.keyboardShortcutAccessSubtitle)
        keyboardShortcutAccessRow.setOnClickListener { requestAccessibilityAccess() }

        // Every tour is due again: home's on the next resume, and the others the next time their
        // screen or page comes up. Home is singleTask, so bringing it forward closes this screen
        // and whatever is between.
        findViewById<View>(R.id.tourReplayRow).setOnClickListener {
            Prefs.clearHintsSeen(this)
            startActivity(Intent(this, MainActivity::class.java))
        }

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

    /** Opened from the home tour's floating-search step: show where that switch is. */
    override fun onSlidIn() {
        if (intent.getStringExtra(EXTRA_HIGHLIGHT) == HIGHLIGHT_QUICK_SEARCH) pointAtQuickSearch()
    }

    /** Scrolls the floating-search row into view and points at it. Nothing is dimmed, so the row
     *  itself can be tapped straight away; a touch anywhere closes the pop-up. */
    private fun pointAtQuickSearch() {
        val scroll = findViewById<NestedScrollView>(R.id.settingsScroll)
        scroll.scrollTo(0, (quickSearchRow.top - scroll.height / 4).coerceAtLeast(0))
        quickSearchRow.post {
            guide?.dismiss()
            guide = TourBalloon.show(
                this, quickSearchRow, Placement.BELOW, Backdrop.NONE,
                TourCard(
                    title = getText(R.string.quick_search_setup_title),
                    body = getText(R.string.quick_search_setup_body),
                    primary = TourCard.Action(getText(R.string.tour_got_it)) {},
                ),
            )
        }
    }

    /**
     * Turning the floating search on takes the drawer — and the ⚙ in it, the way into this
     * screen — off a plain tap on the home screen's search button. Said every time the switch goes
     * on, so nobody is left without a way back here.
     */
    private fun explainSettingsAfterQuickSearch() {
        guide?.dismiss()
        guide = TourBalloon.show(
            this, quickSearchRow, Placement.BELOW, Backdrop.SPOT_ROUND_RECT,
            TourCard(
                title = getText(R.string.quick_search_on_title),
                body = getText(R.string.quick_search_on_body),
                primary = TourCard.Action(getText(R.string.tour_got_it)) {},
            ),
        )
    }

    override fun onResume() {
        super.onResume()
        gesturesSubtitle.text = getString(R.string.gestures_row_subtitle, GestureStore.all(this).size)
        autoKeyboardSwitch.isChecked = Prefs.autoKeyboard(this)
        hapticFeedbackSwitch.isChecked = Prefs.hapticFeedback(this)
        widgetsPageSwitch.isChecked = Prefs.widgetsPage(this)
        notesPageSwitch.isChecked = Prefs.notesPage(this)
        searchSideSubtitle.setText(searchSideLabel(Prefs.searchOnLeft(this)))
        themeSubtitle.setText(themeLabelFor(Prefs.themeMode(this)))
        // Re-read on every resume, not just at create: the usual flow is tapping the row, changing
        // it in system settings, and coming straight back here.
        batterySubtitle.setText(
            if (isBatteryOptimizationExempt()) R.string.battery_exempt else R.string.battery_optimized
        )
        fontSubtitle.text = Prefs.fontName(this) ?: getString(R.string.font_system_default)
        fontScaleSubtitle.text = fontScaleLabel(Prefs.fontScale(this))
        searchEngineSubtitle.setText(Prefs.searchEngine(this).label)
        // Re-read on every resume: Waze can be installed or removed while this screen is away.
        mapsAppRow.isVisible = MapSearch.isInstalled(this, MapSearch.App.WAZE)
        mapsAppSubtitle.setText(MapSearch.app(this).label)
        quickSearchSwitch.isChecked = Prefs.quickSearchEnabled(this)
        updateQuickSearchTrigger()
        keyboardShortcutSwitch.isChecked = Prefs.keyboardShortcutEnabled(this)
        // Re-read for the same reason the battery row is: the access is granted on a system screen
        // this one can't get a result from, so coming back is the only moment to notice.
        updateKeyboardShortcut()
    }

    /** Where the `web` command searches. Read when the row is built, so nothing needs refreshing
     *  beyond this screen's own subtitle. */
    private fun showSearchEngineDialog() {
        val engines = WebSearch.Engine.entries
        val labels = engines.map { getString(it.label) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_search_engine)
            .setSingleChoiceItems(labels, engines.indexOf(Prefs.searchEngine(this))) { dialog, which ->
                Prefs.setSearchEngine(this, engines[which])
                searchEngineSubtitle.setText(engines[which].label)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showWithFont()
    }

    /** Where the `map` command searches and routes. */
    private fun showMapsAppDialog() {
        val apps = MapSearch.App.entries
        val labels = apps.map { getString(it.label) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_maps_app)
            .setSingleChoiceItems(labels, apps.indexOf(MapSearch.app(this))) { dialog, which ->
                Prefs.setMapsApp(this, apps[which])
                mapsAppSubtitle.setText(apps[which].label)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showWithFont()
    }

    /** The words themselves, comma-separated, or what the row is for while there are none. */
    private fun showVoiceWords() {
        val words = Prefs.voiceWords(this)
        voiceWordsSubtitle.text =
            if (words.isEmpty()) getString(R.string.voice_words_none) else words.joinToString(", ")
    }

    private fun showVoiceWordsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_voice_words, null)
        val input = view.findViewById<TextInputEditText>(R.id.voiceWordsInput)
        input.setText(Prefs.voiceWords(this).joinToString("\n"))
        input.setSelection(input.length())
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_words)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                Prefs.setVoiceWords(this, input.text?.toString().orEmpty().lines())
                showVoiceWords()
                val count = Prefs.voiceWords(this).size
                val message = if (count == 0) getString(R.string.voice_words_cleared)
                else resources.getQuantityString(R.plurals.voice_words_saved, count, count)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .showWithFont()
    }

    /** Greys the trigger row out while the overlay is off, and reports whether the role is held. */
    private fun updateQuickSearchTrigger() {
        val enabled = Prefs.quickSearchEnabled(this)
        setRowEnabled(
            quickSearchTriggerRow,
            enabled,
            quickSearchTriggerTitle,
            quickSearchTriggerSubtitle
        )
        quickSearchTriggerSubtitle.setText(
            if (isAssistantRoleHeld()) R.string.quick_search_trigger_granted
            else R.string.quick_search_trigger_missing
        )
    }

    /**
     * Greys both keyboard rows out while quick search itself is off, and reports whether the
     * accessibility access the shortcut needs has been granted.
     *
     * The shortcut opens the quick-search window and nothing else, so with the overlay switched
     * off there is nothing for it to open — the same relationship the trigger row above has.
     */
    private fun updateKeyboardShortcut() {
        val quickSearch = Prefs.quickSearchEnabled(this)
        setRowEnabled(keyboardShortcutRow, quickSearch, keyboardShortcutTitle, keyboardShortcutSubtitle)
        keyboardShortcutSwitch.isEnabled = quickSearch

        // The access row is only worth offering once the shortcut is actually switched on.
        val shortcut = quickSearch && Prefs.keyboardShortcutEnabled(this)
        setRowEnabled(
            keyboardShortcutAccessRow,
            shortcut,
            keyboardShortcutAccessTitle,
            keyboardShortcutAccessSubtitle
        )
        keyboardShortcutAccessSubtitle.setText(
            if (LauncherAccessibilityService.isGranted(this)) R.string.keyboard_shortcut_access_granted
            else R.string.keyboard_shortcut_access_missing
        )
    }

    /** Both halves of "this row doesn't apply right now": it stops taking taps, and it dims. */
    private fun setRowEnabled(row: View, enabled: Boolean, vararg labels: TextView) {
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else DISABLED_ROW_ALPHA
        labels.forEach { it.isEnabled = enabled }
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

    @StringRes
    private fun searchSideLabel(left: Boolean): Int =
        if (left) R.string.search_side_left else R.string.search_side_right

    /** Which corner of the home screen's dock holds the search button. The home screen reads the
     *  choice on its next resume, which is the moment this panel closes. */
    private fun showSearchSideDialog() {
        val sides = booleanArrayOf(true, false)
        val labels = sides.map { getString(searchSideLabel(it)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_search_side)
            .setSingleChoiceItems(labels, sides.indexOf(Prefs.searchOnLeft(this))) { dialog, which ->
                Prefs.setSearchOnLeft(this, sides[which])
                searchSideSubtitle.setText(searchSideLabel(sides[which]))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showWithFont()
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

    /** The file goes through a document provider, which can be a cloud one — so the write runs
     *  off the main thread, and only the toast comes back. */
    private fun doExport(uri: Uri) {
        val backup = BackupManager.buildBackup(this)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                BackupManager.writeTo(this@SettingsHubActivity, uri, backup)
            }
            val message = if (result.isSuccess) R.string.export_success else R.string.export_failed
            Toast.makeText(this@SettingsHubActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun doImport(uri: Uri) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { BackupManager.readFrom(this@SettingsHubActivity, uri) }.fold(
                onSuccess = { data -> showImportConfirmDialog(data) },
                onFailure = {
                    AlertDialog.Builder(this@SettingsHubActivity)
                        .setTitle(R.string.import_confirm_title)
                        .setMessage(R.string.import_invalid_file)
                        .setPositiveButton(R.string.ok, null)
                        .showWithFont()
                }
            )
        }
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
        widgetsPageSwitch.isChecked = Prefs.widgetsPage(this)
        notesPageSwitch.isChecked = Prefs.notesPage(this)
        searchEngineSubtitle.setText(Prefs.searchEngine(this).label)
        mapsAppSubtitle.setText(MapSearch.app(this).label)
        showVoiceWords()

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
        /** Which row to point at once the screen has slid in; see [onSlidIn]. */
        const val EXTRA_HIGHLIGHT = "highlight"
        const val HIGHLIGHT_QUICK_SEARCH = "quick_search"

        /** Faded, not hidden: the row stays visible so it's clear what the toggle above unlocks. */
        private const val DISABLED_ROW_ALPHA = 0.4f

        /** Offered font-size multipliers. Skewed upward because the reason this setting exists is
         *  imported fonts that render small at a given point size; 0.9 is there so the drawer can
         *  still be made denser if someone wants that. */
        private val FONT_SCALES = floatArrayOf(0.9f, 1f, 1.1f, 1.25f, 1.4f, 1.6f)
    }
}
