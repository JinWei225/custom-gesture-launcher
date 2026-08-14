package dev.neffly.gesturelauncher.data

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Whole-app backup: gestures, recognition/behavior prefs, and app labels.
 *
 *  Older files carry a `profileName` field from when the app had a local profile; `Json` is
 *  configured with `ignoreUnknownKeys` below, so those still import cleanly. */
@Serializable
data class BackupData(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val matchThreshold: Float,
    val autoKeyboard: Boolean,
    val gestures: List<GestureMapping>,
    val appTags: Map<String, String> = emptyMap(),
    /** Search toggles. Defaulted so files written before these existed still decode; the defaults
     *  match Prefs', so an older backup restores the behaviour it was taken with. */
    val searchFiles: Boolean = false,
    val searchWeb: Boolean = true,
    val quickSearch: Boolean = false
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
        quickSearch = Prefs.quickSearchEnabled(context)
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
        Prefs.setSearchWeb(context, data.searchWeb)
        Prefs.setQuickSearchEnabled(context, data.quickSearch)
        // File search is deliberately not restored on: the permission it needs is device-specific
        // and won't have been granted here, so a restored "on" would just be a broken promise.
        // The settings row grants it in one tap.

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
