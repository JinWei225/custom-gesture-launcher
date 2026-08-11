package dev.neffly.gesturelauncher.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.BackupData
import dev.neffly.gesturelauncher.data.BackupManager
import dev.neffly.gesturelauncher.data.GestureStore
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.drawer.AppRepository

/**
 * Settings entry point: a local-only profile name up top (see [ProfileDialog]), with the app's
 * other settings listed below it as sections — Gestures (list/add/edit via
 * [GestureSettingsActivity], sensitivity/test via [GestureSensitivityActivity]) and Backup
 * (export/import, see [BackupManager]). Reached from the app drawer's overflow menu.
 *
 * Slides in from the right over the drawer and back out on close — same translucent-window +
 * self-driven-animation technique [dev.neffly.gesturelauncher.drawer.AppDrawerActivity] uses for
 * its own slide-up-from-bottom, for the same reason (OEM skins like HyperOS otherwise replace the
 * requested transition with their own "app opening" zoom).
 */
class SettingsHubActivity : AppCompatActivity() {

    private lateinit var hubRoot: View
    private lateinit var profileName: TextView
    private lateinit var profileAvatar: TextView
    private lateinit var gesturesSubtitle: TextView
    private lateinit var autoKeyboardSwitch: MaterialSwitch
    private lateinit var hapticFeedbackSwitch: MaterialSwitch
    private lateinit var themeSubtitle: TextView
    private var isClosing = false

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) doExport(uri)
        }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) doImport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
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

        profileName = findViewById(R.id.profileName)
        profileAvatar = findViewById(R.id.profileAvatar)
        gesturesSubtitle = findViewById(R.id.gesturesSubtitle)

        findViewById<View>(R.id.profileRow).setOnClickListener {
            ProfileDialog.show(this, Prefs.profileName(this).orEmpty()) { name ->
                Prefs.setProfileName(this, name)
                refreshProfile()
            }
        }
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

        findViewById<View>(R.id.defaultLauncherRow).setOnClickListener {
            openDefaultLauncherSettings()
        }

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        findViewById<TextView>(R.id.aboutRow).text = getString(R.string.about_version, version)
    }

    override fun onResume() {
        super.onResume()
        refreshProfile()
        gesturesSubtitle.text = getString(R.string.gestures_row_subtitle, GestureStore.all(this).size)
        autoKeyboardSwitch.isChecked = Prefs.autoKeyboard(this)
        hapticFeedbackSwitch.isChecked = Prefs.hapticFeedback(this)
        themeSubtitle.setText(themeLabelFor(Prefs.themeMode(this)))
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
            .show()
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
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(0, 0)
        }
    }

    private fun refreshProfile() {
        val name = Prefs.profileName(this).orEmpty()
        profileName.text = name.ifBlank { getString(R.string.guest) }
        profileAvatar.text = name.trim().firstOrNull()?.uppercaseChar()?.toString()
            ?: getString(R.string.guest).first().toString()
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
                    .show()
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
            .show()
    }

    private fun applyImport(data: BackupData, replace: Boolean) {
        val result = BackupManager.apply(this, data, replace)
        AppRepository.invalidate()
        gesturesSubtitle.text = getString(R.string.gestures_row_subtitle, GestureStore.all(this).size)
        autoKeyboardSwitch.isChecked = Prefs.autoKeyboard(this)
        hapticFeedbackSwitch.isChecked = Prefs.hapticFeedback(this)
        refreshProfile()

        var message = getString(R.string.import_result_message, result.gesturesImported, result.tagsImported)
        if (result.missingApps.isNotEmpty()) {
            message += getString(
                R.string.import_result_missing_apps,
                result.missingApps.size,
                result.missingApps.joinToString("\n") { "• $it" }
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.import_result_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    companion object {
        private const val SLIDE_DURATION_MS = 260L
    }
}
