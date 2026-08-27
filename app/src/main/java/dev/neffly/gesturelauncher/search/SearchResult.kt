package dev.neffly.gesturelauncher.search

import android.net.Uri
import dev.neffly.gesturelauncher.drawer.AppInfo

/** One openable row in a search result list, whatever kind of thing it points at. */
sealed class SearchResult {

    data class App(val app: AppInfo) : SearchResult()

    data class File(val hit: FileHit) : SearchResult()

    /** This launcher's own settings hub. No payload — there's only ever the one. */
    object Settings : SearchResult()

    /**
     * A sum the query turned out to be — see [Calculator]. [result] is already formatted for
     * display and is what gets copied; [expression] is the query it came from, kept so the row can
     * show its working.
     */
    data class Calculation(val expression: String, val result: String) : SearchResult()

    /**
     * The web row. [url] is non-null when the query itself parsed as an address, in which case the
     * row offers to open it directly instead of running a Google search for it.
     */
    data class Web(val query: String, val url: String?) : SearchResult()
}

/**
 * A file from MediaStore. [uri] is already an openable `content://` URI (see [FileSearcher]), so
 * nothing here needs a FileProvider.
 */
data class FileHit(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    /** Human-readable parent folder, shown as the row's subtitle. Empty when unknown. */
    val folder: String
)
