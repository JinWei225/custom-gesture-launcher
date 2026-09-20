package dev.neffly.gesturelauncher.search

import android.content.Context
import androidx.annotation.StringRes
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.data.Prefs
import dev.neffly.gesturelauncher.drawer.AppInfo

/**
 * Composes one result list out of the sources, honouring the settings toggles. Pure: the
 * caller decides when to run each part — see [SearchController], which runs apps synchronously and
 * files on a debounced background job.
 */
object SearchEngine {

    /** Fuzzy app matches, exactly as the drawer has always ranked them. */
    fun apps(allApps: List<AppInfo>, query: String): List<SearchResult.App> =
        SearchScoring.rankApps(allApps, query).map { SearchResult.App(it) }

    /**
     * The launcher's own settings, when the query plainly refers to them.
     *
     * A substring test rather than the fuzzy scorer everything else uses: fuzzy matching is a
     * subsequence test, so a fixed label would surface for scattered letters ("sts", "ens") and
     * put a settings row under queries that have nothing to do with settings. Against one constant
     * string, "contains" is both predictable and easy to describe — "set", "settings" and
     * "launcher set" all match, noise doesn't. The minimum length stops a bare "s" matching.
     */
    fun settings(query: String): SearchResult.Settings? {
        val q = SearchScoring.normalize(query)
        if (q.length < MIN_SETTINGS_QUERY) return null
        return if (SETTINGS_KEYWORDS.contains(q)) SearchResult.Settings else null
    }

    /**
     * The calculator row, when the query is arithmetic. No settings toggle behind it, unlike files
     * and the web: it costs no permission, reaches nothing outside the process, and [Calculator]
     * only answers for a query that is unambiguously a sum — so there is nothing for a switch to
     * protect the user from.
     */
    fun calculation(query: String): SearchResult.Calculation? =
        Calculator.evaluate(query)?.let { SearchResult.Calculation(query.trim(), it) }

    /** The time-zone row, when the query asks what time it is somewhere. Untoggled, as above. */
    fun time(context: Context, query: String): SearchResult.Time? =
        TimeZones.answer(query)?.let {
            SearchResult.Time(TimeZones.title(context, it), TimeZones.detail(context, it))
        }

    /** The action row, when the query starts with a command keyword. Untoggled, as above: the
     *  keyword is the opt-in, and nothing happens until the row is tapped. */
    fun command(context: Context, query: String): SearchResult.Action? =
        Commands.parse(query)?.let {
            SearchResult.Action(it, Commands.title(context, it), Commands.detail(context, it))
        }

    private const val MIN_SETTINGS_QUERY = 3
    private const val SETTINGS_KEYWORDS = "launcher settings"

    /** Whether a query is worth sending to MediaStore at all: the toggle is on and the permission
     *  behind it is held. Cheap, so [SearchController] can ask before scheduling any work. */
    fun searchesFiles(context: Context): Boolean =
        Prefs.searchFiles(context) && FilePermissions.isGranted(context)

    /** Blocking MediaStore lookup; empty unless [searchesFiles]. */
    fun files(context: Context, query: String): List<SearchResult.File> {
        if (!searchesFiles(context)) return emptyList()
        return FileSearcher.search(context, query).map { SearchResult.File(it) }
    }

    /** Search-field hint naming only the sources that are actually switched on. */
    @StringRes
    fun hint(context: Context): Int =
        if (searchesFiles(context)) {
            R.string.search_apps_and_files
        } else {
            R.string.search_apps
        }
}
