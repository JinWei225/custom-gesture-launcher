package dev.neffly.gesturelauncher.search

import dev.neffly.gesturelauncher.drawer.AppInfo

/** One openable row in a search result list, whatever kind of thing it points at. */
sealed class SearchResult {

    data class App(val app: AppInfo) : SearchResult()

    /** This launcher's own settings hub. No payload — there's only ever the one. */
    object Settings : SearchResult()

    /**
     * A sum the query turned out to be — see [Calculator]. [result] is already formatted for
     * display and is what gets copied; [expression] is the query it came from, kept so the row can
     * show its working.
     */
    data class Calculation(val expression: String, val result: String) : SearchResult()

    /**
     * A time-zone question the query turned out to be — see [TimeZones]. [time] is the answer,
     * formatted, and is what gets copied; [detail] says which zones it is between.
     */
    data class Time(val time: String, val detail: String) : SearchResult()

    /**
     * An alarm, timer or event the query asked for by keyword — see [Commands]. [title] and
     * [detail] are the reading of it, already worded for the row.
     */
    data class Action(val command: Command, val title: String, val detail: String) : SearchResult()
}
