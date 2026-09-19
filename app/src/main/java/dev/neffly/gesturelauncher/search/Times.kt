package dev.neffly.gesturelauncher.search

import android.content.Context
import android.text.format.DateFormat
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Times of day and lengths of time, the way they get typed into a search bar. Shared by the
 * time-zone row and the commands so "8pm" means the same thing everywhere.
 *
 * The regex fragments are for embedding in a larger pattern — they capture nothing themselves,
 * and expect lower-case input. [parse] and [duration] then read the matched text.
 */
object Times {

    /** `3pm`, `3:30 pm`, `15:00`, `noon`, `midnight`, or a bare hour read on the 24-hour clock. */
    const val TIME = """(?:noon|midnight|\d{1,2}(?::\d{2})?(?: ?[ap]\.?m\.?)?)"""

    /**
     * [TIME] without the bare hour: a colon or a meridiem has to be there. For a query that also
     * carries free text — an event title — a lone "8" is more often part of the title than a time.
     */
    const val TIME_STRICT = """(?:noon|midnight|\d{1,2}(?::\d{2}(?: ?[ap]\.?m\.?)?| ?[ap]\.?m\.?))"""

    /** `10 min`, `1h30m`, `1 hour 30 minutes`, `1.5h`, `90s` — one or more unit-suffixed numbers. */
    const val DURATION = """(?:\d+(?:\.\d+)?\s?(?:h|hrs?|hours?|m|mins?|minutes?|s|secs?|seconds?)(?![a-z])\s?)+"""

    private val TIME_PARTS = Regex("""(\d{1,2})(?::(\d{2}))?(?: ?([ap])\.?m\.?)?""")
    private val DURATION_PART = Regex("""(\d+(?:\.\d+)?)\s?([a-z]+)""")

    /** The time [text] names, or null when it is not one [TIME] accepts. */
    fun parse(text: String): LocalTime? {
        when (text) {
            "noon" -> return LocalTime.NOON
            "midnight" -> return LocalTime.MIDNIGHT
        }
        val m = TIME_PARTS.matchEntire(text) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val meridiem = m.groupValues[3]
        if (minute > 59) return null
        if (meridiem.isNotEmpty()) {
            if (hour !in 1..12) return null
            hour = hour % 12 + if (meridiem == "p") 12 else 0
        } else if (hour > 23) {
            return null
        }
        return LocalTime.of(hour, minute)
    }

    /** The length [text] names, or null when no part of it reads as one, or the total is zero. */
    fun duration(text: String): Duration? {
        var seconds = 0.0
        var parts = 0
        for (m in DURATION_PART.findAll(text)) {
            val amount = m.groupValues[1].toDouble()
            seconds += amount * when (m.groupValues[2].first()) {
                'h' -> 3600
                'm' -> 60
                's' -> 1
                else -> return null
            }
            parts++
        }
        if (parts == 0 || seconds < 1) return null
        return Duration.ofSeconds(seconds.toLong())
    }

    /** Follows the device's 12/24-hour setting, as the home-screen clock does. */
    fun formatter(context: Context): DateTimeFormatter =
        DateTimeFormatter.ofPattern(
            if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
            Locale.getDefault()
        )
}
