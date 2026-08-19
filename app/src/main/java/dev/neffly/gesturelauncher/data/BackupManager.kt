package dev.neffly.gesturelauncher.data

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Whole-app backup: every gesture, every app alias, and every setting that means the same thing on
 * another device.
 *
 * Each field carries the same default [Prefs] uses, which does double duty: a file written before
 * a field existed still decodes, and it decodes to the behaviour it was actually taken with. Older
 * files also carry a `profileName` from when the app had a local profile — `Json` is configured
 * with `ignoreUnknownKeys` below, so those import cleanly too.
 *
 * The one setting deliberately left out is the name of the imported font. The font *file* lives in
 * filesDir and can't travel inside a JSON backup, so restoring the name would leave the settings
 * row claiming a typeface that isn't loaded. [Prefs.fontScale] is here because it is a plain
 * multiplier that means the same thing whatever font it lands on.
 */
@Serializable
data class BackupData(
    val exportedAt: Long = System.currentTimeMillis(),
    val matchThreshold: Float,
    val autoKeyboard: Boolean,
    val gestures: List<GestureMapping>,
    val appTags: Map<String, String> = emptyMap(),
    val searchFiles: Boolean = false,
    val searchWeb: Boolean = true,
    val quickSearch: Boolean = false,
    val hapticFeedback: Boolean = true,
    /** The raw AppCompatDelegate.MODE_NIGHT_* constant, exactly as [Prefs.themeMode] stores it. */
    val themeMode: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
    val fontScale: Float = 1f
)

/** Result of applying an imported [BackupData], for the user-facing summary dialog. */
data class ImportResult(
    val gesturesImported: Int,
    val tagsImported: Int,
    /** Labels of imported gestures whose target package isn't installed on this device — the
     *  realistic "switched phones" case. These are still imported, not dropped, since the app may
     *  be reinstalled later; the gesture list's existing warning badge keeps flagging them. */
    val missingApps: List<String>
)

object BackupManager {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun buildBackup(context: Context): BackupData = BackupData(
        matchThreshold = Prefs.matchThreshold(context),
        autoKeyboard = Prefs.autoKeyboard(context),
        gestures = GestureStore.all(context),
        appTags = AppTagStore.allTags(context),
        searchFiles = Prefs.searchFiles(context),
        searchWeb = Prefs.searchWeb(context),
        quickSearch = Prefs.quickSearchEnabled(context),
        hapticFeedback = Prefs.hapticFeedback(context),
        themeMode = Prefs.themeMode(context),
        fontScale = Prefs.fontScale(context)
    )

    fun writeTo(context: Context, uri: Uri, data: BackupData): Result<Unit> = runCatching {
        val text = json.encodeToString(data)
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            ?: error("Couldn't open destination for writing")
    }

    fun readFrom(context: Context, uri: Uri): Result<BackupData> = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("Couldn't open backup file for reading")
        json.decodeFromString<BackupData>(text)
    }

    fun apply(context: Context, data: BackupData, replace: Boolean): ImportResult {
        if (replace) {
            GestureStore.replaceAll(context, data.gestures)
            AppTagStore.replaceAll(context, data.appTags)
        } else {
            data.gestures.forEach { GestureStore.add(context, it) }
            AppTagStore.mergeAll(context, data.appTags)
        }
        Prefs.setMatchThreshold(context, data.matchThreshold)
        Prefs.setAutoKeyboard(context, data.autoKeyboard)
        Prefs.setHapticFeedback(context, data.hapticFeedback)
        Prefs.setSearchWeb(context, data.searchWeb)
        Prefs.setQuickSearchEnabled(context, data.quickSearch)
        Prefs.setThemeMode(context, data.themeMode)
        Prefs.setFontScale(context, data.fontScale)
        // File search is deliberately not restored on: the permission it needs is device-specific
        // and won't have been granted here, so a restored "on" would just be a broken promise.
        // The settings row grants it in one tap.
        //
        // Theme and font size are written here but not *applied* here — this is the data layer,
        // and making them visible means recreating activities. The importing screen does that once
        // the user has read the summary; see SettingsHubActivity.applyImport.

        val missingApps = data.gestures.filter {
            runCatching { context.packageManager.getApplicationInfo(it.packageName, 0) }.isFailure
        }.map { it.label }

        return ImportResult(
            gesturesImported = data.gestures.size,
            tagsImported = data.appTags.size,
            missingApps = missingApps
        )
    }
}
