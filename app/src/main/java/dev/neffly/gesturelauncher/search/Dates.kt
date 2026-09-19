package dev.neffly.gesturelauncher.search

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

/**
 * Calendar days the way they get typed: `today`, `tomorrow`, `mon`, `next friday`, `21 sep`,
 * `sep 21 2027`, `2026-09-21`, `in 3 days`, `in 2 weeks`.
 *
 * "friday" is the coming Friday — today, when today is one. "next friday" is the same day, unless
 * that would be today, in which case it is a week on. That is how the phone's own assistant
 * reads it, and the shorter of the two readings; the row shows the date it resolved to, so the
 * other reading costs one glance rather than a wrong event.
 */
object Dates {

    private const val WEEKDAY =
        """(?:mon(?:day)?|tue(?:s|sday)?|wed(?:nesday)?|thu(?:r|rs|rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)"""
    private const val MONTH =
        """(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t|tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"""
    private const val DAY = """\d{1,2}(?:st|nd|rd|th)?"""
    private const val YEAR = """\d{4}"""

    /** Regex fragment matching one day phrase, capturing nothing. Lower-case input, word-bounded. */
    const val DATE =
        """\b(?:today|tomorrow|tmrw?|(?:next |this )?$WEEKDAY|in \d+ (?:days?|weeks?)|$DAY $MONTH(?: $YEAR)?|$MONTH $DAY(?:,? $YEAR)?|$YEAR-\d{2}-\d{2})\b"""

    private val RELATIVE = Regex("""in (\d+) (day|week)s?""")
    private val NAMED_DAY = Regex("""(next |this )?($WEEKDAY)""")
    private val DAY_MONTH = Regex("""(\d{1,2})(?:st|nd|rd|th)? ($MONTH)(?: ($YEAR))?""")
    private val MONTH_DAY = Regex("""($MONTH) (\d{1,2})(?:st|nd|rd|th)?(?:,? ($YEAR))?""")
    private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})""")

    /** The day [text] names, seen from [today], or null when it isn't one or isn't a real date. */
    fun resolve(text: String, today: LocalDate): LocalDate? {
        when (text) {
            "today" -> return today
            "tomorrow", "tmr", "tmrw" -> return today.plusDays(1)
        }
        RELATIVE.matchEntire(text)?.let { m ->
            val n = m.groupValues[1].toLong()
            return if (m.groupValues[2] == "day") today.plusDays(n) else today.plusWeeks(n)
        }
        NAMED_DAY.matchEntire(text)?.let { m ->
            val target = weekday(m.groupValues[2])
            var ahead = (target.value - today.dayOfWeek.value + 7) % 7
            if (ahead == 0 && m.groupValues[1] == "next ") ahead = 7
            return today.plusDays(ahead.toLong())
        }
        DAY_MONTH.matchEntire(text)?.let { m ->
            return dayOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], today)
        }
        MONTH_DAY.matchEntire(text)?.let { m ->
            return dayOf(m.groupValues[2], m.groupValues[1], m.groupValues[3], today)
        }
        ISO.matchEntire(text)?.let { m ->
            val (y, mo, d) = m.destructured
            return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
        }
        return null
    }

    /** A day and month with no year is the next such date, this year or next. */
    private fun dayOf(day: String, month: String, year: String, today: LocalDate): LocalDate? {
        val m = month(month)
        val d = day.toInt()
        if (year.isNotEmpty()) return runCatching { LocalDate.of(year.toInt(), m, d) }.getOrNull()
        val thisYear = runCatching { LocalDate.of(today.year, m, d) }.getOrNull()
        // Only a leap day fails this year and not next; anything else invalid fails both.
        if (thisYear != null && !thisYear.isBefore(today)) return thisYear
        return runCatching { LocalDate.of(today.year + 1, m, d) }.getOrNull()
    }

    // By fixed English prefixes rather than the platform's month names: those follow CLDR, which
    // spells September "Sept" in some English locales and would silently stop matching "sep".
    private fun weekday(name: String): DayOfWeek =
        DayOfWeek.of(WEEKDAYS.indexOf(name.take(3)) + 1)

    private fun month(name: String): Month = Month.of(MONTHS.indexOf(name.take(3)) + 1)

    private val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private val MONTHS =
        listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
}
