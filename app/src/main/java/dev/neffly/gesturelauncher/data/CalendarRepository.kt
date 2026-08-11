package dev.neffly.gesturelauncher.data

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import java.time.LocalDate

/** One calendar event occurring today. */
data class DayEvent(val title: String, val begin: Long, val allDay: Boolean)

/** Reads today's events from the system calendar. Requires the READ_CALENDAR permission. */
object CalendarRepository {

    private val PROJECTION = arrayOf(
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.ALL_DAY
    )

    /** Julian day 0 is 24 Nov 4714 BC; epoch day 0 (1 Jan 1970) is Julian day 2440588. */
    private const val JULIAN_DAY_OF_EPOCH = 2440588L

    /** Today's events sorted by start time. Returns empty on missing permission or any error. */
    fun todaysEvents(context: Context): List<DayEvent> = runCatching {
        // Query by Julian day rather than a local millisecond window: all-day events are stored
        // in UTC, so a local-time window pulls in yesterday's all-day events for any timezone
        // east of UTC. The provider's START_DAY/END_DAY already resolve that to a calendar date.
        val today = LocalDate.now().toEpochDay() + JULIAN_DAY_OF_EPOCH
        val uri = CalendarContract.Instances.CONTENT_BY_DAY_URI.buildUpon()
            .also { ContentUris.appendId(it, today); ContentUris.appendId(it, today) }
            .build()

        val events = ArrayList<DayEvent>()
        context.contentResolver.query(uri, PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(No title)"
                val begin = c.getLong(1)
                val allDay = c.getInt(2) == 1
                events.add(DayEvent(title, begin, allDay))
            }
        }
        // All-day events sort first: their `begin` is UTC midnight, which would otherwise place
        // them at the UTC offset (e.g. 08:00) in a list that's otherwise local wall-clock time.
        events.sortedWith(compareByDescending<DayEvent> { it.allDay }.thenBy { it.begin })
    }.getOrDefault(emptyList())
}
