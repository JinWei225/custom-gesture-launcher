package dev.neffly.gesturelauncher.data

import android.content.Context
import android.provider.CalendarContract
import java.util.Calendar

/** One calendar event occurring today. */
data class DayEvent(val title: String, val begin: Long, val allDay: Boolean)

/** Reads today's events from the system calendar. Requires the READ_CALENDAR permission. */
object CalendarRepository {

    private val PROJECTION = arrayOf(
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.ALL_DAY
    )

    /** Today's events sorted by start time. Returns empty on missing permission or any error. */
    fun todaysEvents(context: Context): List<DayEvent> = runCatching {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val endOfDay = startOfDay + 24L * 60 * 60 * 1000 - 1

        val events = ArrayList<DayEvent>()
        CalendarContract.Instances.query(
            context.contentResolver, PROJECTION, startOfDay, endOfDay
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(No title)"
                val begin = c.getLong(1)
                val allDay = c.getInt(2) == 1
                events.add(DayEvent(title, begin, allDay))
            }
        }
        events.sortedBy { it.begin }
    }.getOrDefault(emptyList())
}
