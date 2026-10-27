package dev.neffly.gesturelauncher.search

import android.content.Context
import dev.neffly.gesturelauncher.drawer.AppInfo

/** The sources one result list is composed from — see [SearchController] for the order. */
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
     * The calculator row, when the query is arithmetic. No settings toggle behind it: it costs no permission, reaches nothing outside the process, and [Calculator]
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
}
