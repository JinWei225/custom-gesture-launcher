package dev.neffly.gesturelauncher.search

import android.content.Context
import dev.neffly.gesturelauncher.data.QuicklinkStore
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

    /** What the last [onResults] carried. Enter opens the first of these rather than the adapter's
     *  first row: the adapter's list only changes once a background diff finishes, so a fast
     *  keyboard's Enter could open the previous query's top result. */
    var results: List<SearchResult> = emptyList()
        private set

    fun onQueryChanged(newQuery: String) {
        query = newQuery
        emit()
    }

    /** Re-runs the current query, e.g. after [apps] changed or a settings toggle was flipped. */
    fun refresh() = onQueryChanged(query)

    private fun emit() {
        // A query that folds to nothing — a lone dead-key accent, a zero-width space — would
        // otherwise rank every app as a match.
        if (query.isBlank() || SearchScoring.normalize(query).isEmpty()) {
            results = emptyList()
            onResults(query, results)
            return
        }
        val results = ArrayList<SearchResult>()
        // Pinned above everything: when the query is a sum, the answer is the whole reason it was
        // typed.
        results += SearchEngine.commands(context, query, apps)
        SearchEngine.calculation(query)?.let { results += it }
        SearchEngine.time(context, query)?.let { results += it }
        // Quicklinks sit under the apps, unless the query is one of their aliases: an alias is a
        // shortcut the user made for exactly this, so it goes first. All links move together so
        // the list never shows two Quicklinks sections.
        val links = SearchEngine.links(QuicklinkStore.all(context), query)
        val linksFirst = SearchEngine.isLinkAlias(links, query)
        if (linksFirst) results += links
        results += SearchEngine.apps(apps, query)
        if (!linksFirst) results += links
        SearchEngine.settings(query)?.let { results += it }
        this.results = results
        onResults(query, results)
    }
}
