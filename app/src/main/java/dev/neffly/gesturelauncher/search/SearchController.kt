package dev.neffly.gesturelauncher.search

import android.content.Context
import dev.neffly.gesturelauncher.drawer.AppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives one search box. Shared by the app drawer and the floating quick-search window so the two
 * can't drift apart in behaviour.
 *
 * Apps and the web row are emitted synchronously on every keystroke — the app filter is an
 * in-memory pass and has always been instant. The file section arrives separately from a debounced
 * background query, and is spliced in only if the box still holds the query it was run for; a
 * stale result is dropped rather than rendered. That keeps typing as responsive as it was before
 * files existed, at the cost of the FILES section appearing a beat later.
 *
 * [onResults] is always called on the main thread, with the query the results belong to.
 */
class SearchController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onResults: (query: String, results: List<SearchResult>) -> Unit
) {

    /** The app list to filter. Set it again whenever the repository reloads. */
    var apps: List<AppInfo> = emptyList()

    private var query: String = ""
    private var fileJob: Job? = null

    /** File hits for [fileQuery] — kept so an app-list refresh can re-emit without re-querying. */
    private var fileHits: List<SearchResult.File> = emptyList()
    private var fileQuery: String? = null

    fun onQueryChanged(newQuery: String) {
        query = newQuery
        fileJob?.cancel()
        if (fileQuery != newQuery) {
            fileHits = emptyList()
            fileQuery = null
        }
        emit()
        if (newQuery.isBlank()) return
        fileJob = scope.launch {
            delay(FILE_DEBOUNCE_MS)
            val hits = withContext(Dispatchers.IO) { SearchEngine.files(context, newQuery) }
            // The box may have moved on while the query ran; anything stale is discarded.
            if (query != newQuery) return@launch
            fileHits = hits
            fileQuery = newQuery
            emit()
        }
    }

    /** Re-runs the current query, e.g. after [apps] changed or a settings toggle was flipped. */
    fun refresh() = onQueryChanged(query)

    fun cancel() {
        fileJob?.cancel()
    }

    private fun emit() {
        if (query.isBlank()) {
            onResults(query, emptyList())
            return
        }
        val results = ArrayList<SearchResult>()
        // Pinned above everything: when the query is a sum, the answer is the whole reason it was
        // typed, and it must not move as the file section lands underneath it a beat later.
        SearchEngine.calculation(query)?.let { results += it }
        results += SearchEngine.apps(apps, query)
        // Above files and the web: someone typing "settings" wants this, not a file named after it.
        SearchEngine.settings(query)?.let { results += it }
        if (fileQuery == query) results += fileHits
        SearchEngine.web(context, query)?.let { results += it }
        onResults(query, results)
    }

    private companion object {
        /** Long enough to skip a MediaStore query per keystroke, short enough not to feel laggy. */
        const val FILE_DEBOUNCE_MS = 150L
    }
}
