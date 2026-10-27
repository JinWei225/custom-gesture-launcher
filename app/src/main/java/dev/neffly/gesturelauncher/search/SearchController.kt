package dev.neffly.gesturelauncher.search

import android.content.Context
import dev.neffly.gesturelauncher.drawer.AppInfo

/**
 * Drives one search box. Shared by the app drawer and the floating quick-search window so the two
 * can't drift apart in behaviour.
 *
 * Every source is an in-memory pass, so results are emitted synchronously on every keystroke.
 * [onResults] is called with the query the results belong to.
 */
class SearchController(
    private val context: Context,
    private val onResults: (query: String, results: List<SearchResult>) -> Unit
) {

    /** The app list to filter. Set it again whenever the repository reloads. */
    var apps: List<AppInfo> = emptyList()

    private var query: String = ""

    fun onQueryChanged(newQuery: String) {
        query = newQuery
        emit()
    }

    /** Re-runs the current query, e.g. after [apps] changed or a settings toggle was flipped. */
    fun refresh() = onQueryChanged(query)

    private fun emit() {
        if (query.isBlank()) {
            onResults(query, emptyList())
            return
        }
        val results = ArrayList<SearchResult>()
        // Pinned above everything: when the query is a sum, the answer is the whole reason it was
        // typed.
        SearchEngine.command(context, query)?.let { results += it }
        SearchEngine.calculation(query)?.let { results += it }
        SearchEngine.time(context, query)?.let { results += it }
        results += SearchEngine.apps(apps, query)
        SearchEngine.settings(query)?.let { results += it }
        onResults(query, results)
    }
}
