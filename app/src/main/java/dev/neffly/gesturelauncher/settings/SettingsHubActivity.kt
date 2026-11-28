package dev.neffly.gesturelauncher.settings

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.BundleCompat
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
import dev.neffly.gesturelauncher.data.LaunchStyle
import dev.neffly.gesturelauncher.data.PieStore
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.data.QuicklinkStore
import dev.neffly.gesturelauncher.drawer.AppRepository
import dev.neffly.gesturelauncher.onboarding.Backdrop
import dev.neffly.gesturelauncher.onboarding.Placement
import dev.neffly.gesturelauncher.onboarding.TourBalloon
import dev.neffly.gesturelauncher.onboarding.TourCard
import dev.neffly.gesturelauncher.search.MapSearch
import dev.neffly.gesturelauncher.search.WebSearch
import dev.neffly.gesturelauncher.ui.FontEngine
import dev.neffly.gesturelauncher.ui.SlidePanelActivity
import dev.neffly.gesturelauncher.ui.overrideOwnTransitions
import dev.neffly.gesturelauncher.ui.showWithFont
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private lateinit var gesturesRow: View
    private lateinit var sensitivityRow: View
    private lateinit var pieAppsRow: View
    private lateinit var pieAppsSubtitle: TextView
    private lateinit var launchStyleSubtitle: TextView
    private lateinit var quicklinksSubtitle: TextView
    private lateinit var mapsAppSubtitle: TextView
    private lateinit var voiceWordsSubtitle: TextView
    private lateinit var voiceSwitch: MaterialSwitch
    private lateinit var voiceOnAssistRow: View
    private lateinit var voiceOnAssistTitle: TextView
    private lateinit var voiceOnAssistSubtitle: TextView
    private lateinit var voiceOnAssistSwitch: MaterialSwitch
    private lateinit var quickSearchSwitch: MaterialSwitch
    private lateinit var quickSearchRow: View
    /** The guide pop-up open over a row, if any — see [pointAtQuickSearch]. */
    private var guide: Balloon? = null

    /** Back closes a modal guide rather than the screen under it: its spotlight blocks the screen,
     *  and the pop-up's own contract leaves Back to the caller (see TourBalloon). */
    private val closeGuideOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            guide?.dismiss()
        }
    }
    private lateinit var quickSearchTriggerRow: View
    private lateinit var quickSearchTriggerTitle: TextView
    private lateinit var quickSearchTriggerSubtitle: TextView

    /** The font scale in force when an import started, so [applyImportedAppearance] can tell
     *  whether the imported one is actually different before rebuilding the screen. */
    private var importedFromScale = 1f

    /** The backup whose confirm dialog is up, and the summary of the import whose result dialog
     *  is up. Kept across a recreation so neither is lost to a rotation: the confirm is asked
     *  again, and the result is shown again so that dismissing it still applies the imported
     *  theme and font size. */
    private var pendingImport: Uri? = null
    private var pendingSummary: String? = null
    private var importDialog: AlertDialog? = null

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
        onBackPressedDispatcher.addCallback(this, closeGuideOnBack)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        gesturesSubtitle = findViewById(R.id.gesturesSubtitle)
        gesturesRow = findViewById(R.id.gesturesRow)
        sensitivityRow = findViewById(R.id.sensitivityRow)
        pieAppsRow = findViewById(R.id.pieAppsRow)
        pieAppsSubtitle = findViewById(R.id.pieAppsSubtitle)
        launchStyleSubtitle = findViewById(R.id.launchStyleSubtitle)
        quicklinksSubtitle = findViewById(R.id.quicklinksSubtitle)
        findViewById<View>(R.id.launchStyleRow).setOnClickListener { showLaunchStyleDialog() }
        pieAppsRow.setOnClickListener { startActivity(Intent(this, PieAppsActivity::class.java)) }
        findViewById<View>(R.id.quicklinksRow).setOnClickListener {
            startActivity(Intent(this, QuicklinksActivity::class.java))
        }

        gesturesRow.setOnClickListener {
            startActivity(Intent(this, GestureSettingsActivity::class.java))
        }
        sensitivityRow.setOnClickListener {
            startActivity(Intent(this, GestureSensitivityActivity::class.java))
        }
        findViewById<View>(R.id.exportRow).setOnClickListener {
            launchPicker { exportLauncher.launch("gesture-launcher-backup.json") }
        }
        findViewById<View>(R.id.importRow).setOnClickListener {
            launchPicker { importLauncher.launch(arrayOf("application/json")) }
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
        voiceSwitch = findViewById(R.id.voiceSwitch)
        findViewById<View>(R.id.voiceRow).setOnClickListener {
            val enabled = !Prefs.voiceEnabled(this)
            Prefs.setVoiceEnabled(this, enabled)
            voiceSwitch.isChecked = enabled
            updateVoice()
        }
        voiceOnAssistRow = findViewById(R.id.voiceOnAssistRow)
        voiceOnAssistTitle = findViewById(R.id.voiceOnAssistTitle)
        voiceOnAssistSubtitle = findViewById(R.id.voiceOnAssistSubtitle)
        voiceOnAssistSwitch = findViewById(R.id.voiceOnAssistSwitch)
        voiceOnAssistRow.setOnClickListener {
            val enabled = !Prefs.voiceOnAssist(this)
            Prefs.setVoiceOnAssist(this, enabled)
            voiceOnAssistSwitch.isChecked = enabled
        }
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
            if (enabled) explainSettingsAfterQuickSearch()
        }

        quickSearchTriggerRow = findViewById(R.id.quickSearchTriggerRow)
        quickSearchTriggerTitle = findViewById(R.id.quickSearchTriggerTitle)
        quickSearchTriggerSubtitle = findViewById(R.id.quickSearchTriggerSubtitle)
        quickSearchTriggerRow.setOnClickListener { openAssistantSettings() }

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

        // An import dialog that was up when the screen was recreated — see pendingImport.
        savedInstanceState?.let { state ->
            importedFromScale = state.getFloat(STATE_IMPORT_SCALE, 1f)
            state.getString(STATE_IMPORT_SUMMARY)?.let(::showImportResult)
            BundleCompat.getParcelable(state, STATE_IMPORT_URI, Uri::class.java)?.let(::doImport)
        }
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
        ).also { balloon ->
            closeGuideOnBack.isEnabled = true
            balloon.setOnBalloonDismissListener { closeGuideOnBack.isEnabled = false }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRows()
    }

    /** Every row's switch and subtitle from what is stored now. On resume, and after an import —
     *  which writes nearly every setting at once, so it refreshes through the same path rather than
     *  a list of its own that can fall behind this one. */
    private fun refreshRows() {
        gesturesSubtitle.text = getString(R.string.gestures_row_subtitle, GestureStore.all(this).size)
        updateLaunchStyle()
        pieAppsSubtitle.text = getString(R.string.pie_apps_row_subtitle, PieStore.all(this).size, PieStore.MAX_APPS)
        val links = QuicklinkStore.all(this).size
        quicklinksSubtitle.text = resources.getQuantityString(R.plurals.quicklinks_row_subtitle, links, links)
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
        updateVoice()
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

    /** Both voice switches' state, and the assistant-listening row greyed while voice search is
     *  off — with nothing to start, its choice means nothing. */
    private fun updateVoice() {
        val voice = Prefs.voiceEnabled(this)
        voiceSwitch.isChecked = voice
        voiceOnAssistSwitch.isChecked = Prefs.voiceOnAssist(this)
        setRowEnabled(voiceOnAssistRow, voice, voiceOnAssistTitle, voiceOnAssistSubtitle)
        voiceOnAssistSwitch.isEnabled = voice
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
        val scales = Prefs.FONT_SCALES
        val labels = scales.map { fontScaleLabel(it) }.toTypedArray()
        val current = Prefs.fontScale(this)
        val selected = scales.indexOfFirst { it == current }

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_font_size)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                Prefs.setFontScale(this, scales[which])
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
                if (which == 0) launchPicker { fontLauncher.launch(FontStore.PICKER_MIME_TYPES) } else clearFont()
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

    /** Only the rows that belong to the chosen style are shown — the gesture list means nothing
     *  while the pie is in use, and the other way round. */
    private fun updateLaunchStyle() {
        val style = Prefs.launchStyle(this)
        launchStyleSubtitle.setText(launchStyleLabel(style))
        val pie = style == LaunchStyle.PIE
        gesturesRow.isVisible = !pie
        sensitivityRow.isVisible = !pie
        pieAppsRow.isVisible = pie
    }

    @StringRes
    private fun launchStyleLabel(style: LaunchStyle): Int = when (style) {
        LaunchStyle.GESTURES -> R.string.launch_style_gestures
        LaunchStyle.PIE -> R.string.launch_style_pie
    }

    /** The home screen switches surfaces on its next resume, which is the moment this panel closes. */
    private fun showLaunchStyleDialog() {
        val styles = LaunchStyle.entries
        val labels = styles.map { getString(launchStyleLabel(it)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.launch_style_title)
            .setSingleChoiceItems(labels, styles.indexOf(Prefs.launchStyle(this))) { dialog, which ->
                Prefs.setLaunchStyle(this, styles[which])
                updateLaunchStyle()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showWithFont()
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
        val modes = Prefs.THEME_MODES
        val labels = modes.map { getString(themeLabelFor(it)) }.toTypedArray()
        val current = modes.indexOf(Prefs.themeMode(this))

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

    /** The file goes through a document provider, which can be a cloud one — so the work runs
     *  off the main thread, and only the toast comes back. */
    private fun doExport(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                BackupManager.writeTo(applicationContext, uri, BackupManager.buildBackup(applicationContext))
            }
            val message = if (result.isSuccess) R.string.export_success else R.string.export_failed
            Toast.makeText(this@SettingsHubActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun doImport(uri: Uri) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { BackupManager.readFrom(applicationContext, uri) }.fold(
                onSuccess = { data -> showImportConfirmDialog(uri, data) },
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

    private fun showImportConfirmDialog(uri: Uri, data: BackupData) {
        pendingImport = uri
        val message = getString(R.string.import_confirm_message, data.gestures.size, data.appTags.size)
        importDialog = AlertDialog.Builder(this)
            .setTitle(R.string.import_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.import_replace) { _, _ -> pendingImport = null; applyImport(data, replace = true) }
            .setNeutralButton(R.string.import_merge) { _, _ -> pendingImport = null; applyImport(data, replace = false) }
            .setNegativeButton(R.string.cancel) { _, _ -> pendingImport = null }
            .setOnCancelListener { pendingImport = null }
            .showWithFont()
    }

    /** Applied off the main thread: a backup is several file writes and a package lookup per
     *  gesture. */
    private fun applyImport(data: BackupData, replace: Boolean) {
        importedFromScale = Prefs.fontScale(this)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { BackupManager.apply(applicationContext, data, replace) }
            AppRepository.invalidate()
            refreshRows()
            showVoiceWords()

            var summary = getString(R.string.import_result_message, result.gesturesImported, result.tagsImported)
            if (result.missingApps.isNotEmpty()) {
                summary += getString(
                    R.string.import_result_missing_apps,
                    result.missingApps.size,
                    result.missingApps.joinToString("\n") { "• $it" }
                )
            }
            showImportResult(summary)
        }
    }

    /** Theme and font size land last, once the summary has been read: applying either recreates
     *  this activity, which would tear the dialog down with it (the same leak the theme and
     *  font-size dialogs above dismiss themselves to avoid). Cancel is covered as well as the
     *  button — the dismiss slot belongs to showWithFont. */
    private fun showImportResult(summary: String) {
        pendingSummary = summary
        importDialog = AlertDialog.Builder(this)
            .setTitle(R.string.import_result_title)
            .setMessage(summary)
            .setPositiveButton(R.string.ok) { _, _ -> pendingSummary = null; applyImportedAppearance() }
            .setOnCancelListener { pendingSummary = null; applyImportedAppearance() }
            .showWithFont()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(STATE_IMPORT_URI, pendingImport)
        outState.putString(STATE_IMPORT_SUMMARY, pendingSummary)
        outState.putFloat(STATE_IMPORT_SCALE, importedFromScale)
    }

    override fun onDestroy() {
        importDialog?.dismiss()
        super.onDestroy()
    }

    /** ActivityResultLauncher.launch throws when no app handles the document intent — DocumentsUI
     *  can be disabled on managed and Go devices. */
    private fun launchPicker(launch: () -> Unit) {
        if (runCatching(launch).isFailure) {
            Toast.makeText(this, R.string.could_not_open_settings, Toast.LENGTH_SHORT).show()
        }
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

        private const val STATE_IMPORT_URI = "import_uri"
        private const val STATE_IMPORT_SUMMARY = "import_summary"
        private const val STATE_IMPORT_SCALE = "import_scale"

        /** Faded, not hidden: the row stays visible so it's clear what the toggle above unlocks. */
        private const val DISABLED_ROW_ALPHA = 0.4f
    }
}
