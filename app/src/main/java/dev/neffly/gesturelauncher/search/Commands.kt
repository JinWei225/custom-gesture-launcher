package dev.neffly.gesturelauncher.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.widget.Toast
import androidx.annotation.StringRes
import dev.neffly.gesturelauncher.R
import java.text.Normalizer
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/** Something the search bar can do for a query that starts with its keyword — see [Commands]. */
sealed class Command {

    /** `alarm 7:30am weekdays gym`. Whatever isn't a time or a day name is the label. */
    data class Alarm(val time: LocalTime?, val days: Set<DayOfWeek>, val label: String) : Command()

    /** `timer 10 min pasta`. A bare number is minutes. */
    data class Timer(val length: Duration?, val label: String) : Command()

    /**
     * `event team discussion next mon 8pm`, `event dentist tomorrow 9-9:30am`, `event trip 21 sep`.
     * [date] with no [start] is an all-day event; [start] is never set without a [date], since a
     * bare time is read as today, or tomorrow once it has passed.
     */
    data class Event(
        val title: String,
        val date: LocalDate?,
        val start: LocalTime?,
        val end: LocalTime?
    ) : Command()

    /** `web how tall is k2`, `google docs.google.com`. [url] is set when the query is an address. */
    data class Web(val query: String, val url: String?) : Command()

    /** `map petrol station near me`. */
    data class Map(val query: String) : Command()
}

/**
 * Turns a query into a [Command] when it starts with one of the keywords, and fires it.
 *
 * No model: each command is a fixed verb with a few typed slots, and Android already has an
 * intent for every one of them. `web` and `map` have one slot, the rest of the line. The slots are read by removal — the date, time and length phrases
 * are matched anywhere in the query and cut out, and whatever is left is the title — so the
 * words can come in any order, and the row shows the reading it arrived at before anything is
 * done with it. Every partial reading is still a valid action: an alarm with no time opens the
 * clock app at its new-alarm screen, an event with only a title opens the calendar editor.
 *
 * The keyword is the whole trigger. Everything else the bar does stays quiet unless the query
 * is unmistakably for it; here the user asks by name, which is what lets "event" be followed by
 * free text without the row appearing under ordinary searches.
 */
object Commands {

    /** [query]'s command, or null when it doesn't start with a keyword. */
    fun parse(query: String, clock: Clock = Clock.systemDefaultZone()): Command? {
        val q = Normalizer.normalize(query, Normalizer.Form.NFKC).replace(WHITESPACE, " ").trim()
        if (q.length > MAX_LENGTH) return null
        val m = KEYWORD.matchEntire(q) ?: return null
        val slots = Slots(m.groupValues[2])
        return when (m.groupValues[1].lowercase(Locale.ROOT)) {
            "alarm" -> alarm(slots)
            "timer" -> timer(slots)
            "event" -> event(slots, ZonedDateTime.now(clock))
            "web", "google" -> slots.rest().let { Command.Web(it, WebSearch.detectUrl(it)) }
            else -> Command.Map(slots.rest())
        }
    }

    private fun alarm(slots: Slots): Command.Alarm {
        val time = slots.take(ALARM_TIME) { Times.parse(it.groupValues[1].lowercase(Locale.ROOT)) }
        val days = HashSet<DayOfWeek>()
        while (true) {
            val found = slots.take(ALARM_DAYS) { it.groupValues[1].lowercase(Locale.ROOT) } ?: break
            days += when (found) {
                "weekdays" -> WEEKDAYS
                "weekends" -> WEEKENDS
                "daily", "everyday", "every day" -> DayOfWeek.entries
                else -> listOf(weekday(found))
            }
        }
        // An alarm is always for the next time its hour comes round, which is what "tomorrow"
        // means at any hour someone types it — so the word is understood rather than kept as
        // a label.
        slots.take(ALARM_DAY_WORDS) { true }
        return Command.Alarm(time, days, slots.rest())
    }

    private fun timer(slots: Slots): Command.Timer {
        val length = slots.take(TIMER_LENGTH) { Times.duration(it.groupValues[1].lowercase(Locale.ROOT)) }
            ?: slots.take(BARE_NUMBER) { Duration.ofMinutes(it.groupValues[1].toLong()) }
        return Command.Timer(length, slots.rest())
    }

    private fun event(slots: Slots, now: ZonedDateTime): Command.Event {
        val range = slots.take(EVENT_RANGE) { m ->
            val (startText, endText) = m.destructured
            range(startText.lowercase(Locale.ROOT), endText.lowercase(Locale.ROOT))
        }
        val length = slots.take(EVENT_LENGTH) { Times.duration(it.groupValues[1].lowercase(Locale.ROOT)) }
        val start = range?.first ?: slots.take(EVENT_TIME) { m ->
            Times.parse(m.groupValues[1].ifEmpty { m.groupValues[2] }.lowercase(Locale.ROOT))
        }
        var end = range?.second
        var date = slots.take(EVENT_DATE) {
            Dates.resolve(it.groupValues[1].lowercase(Locale.ROOT), now.toLocalDate())
        }
        if (start != null) {
            if (end == null && length != null) end = start.plus(length)
            if (date == null) {
                date = if (start.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
            }
        }
        return Command.Event(slots.rest(), date, start, end)
    }

    /**
     * `8-9pm`, `8:30 to 9pm`, `11-1pm`, `20:00-21:00`. One side has to carry a colon or a meridiem,
     * or "8-9" in a title would be read as a time. A start with no meridiem takes the end's, unless
     * that would put it after the end — "11-1pm" is 11 in the morning.
     */
    private fun range(startText: String, endText: String): Pair<LocalTime, LocalTime>? {
        val startStrict = STRICT.matches(startText)
        val endStrict = STRICT.matches(endText)
        if (!startStrict && !endStrict) return null
        var start = Times.parse(startText) ?: return null
        val end = Times.parse(endText) ?: return null
        if (!MERIDIEM.containsMatchIn(startText) && MERIDIEM.containsMatchIn(endText) && start.hour in 1..12) {
            val pm = end.hour >= 12
            start = start.withHour(start.hour % 12 + if (pm) 12 else 0)
            if (!start.isBefore(end)) start = start.withHour((start.hour + 12) % 24)
        }
        return start to end
    }

    // --- display ------------------------------------------------------------

    /** The row's title: the thing that will be created, with the part most worth checking. */
    fun title(context: Context, command: Command): String = when (command) {
        is Command.Alarm -> command.time?.let {
            context.getString(R.string.search_alarm_at, Times.formatter(context).format(it))
        } ?: context.getString(R.string.search_alarm)
        is Command.Timer -> command.length?.let {
            context.getString(R.string.search_timer_for, length(context, it))
        } ?: context.getString(R.string.search_timer)
        is Command.Event -> command.title.ifEmpty { context.getString(R.string.search_event_new) }
        is Command.Web -> command.query.ifEmpty { context.getString(R.string.search_web) }
        is Command.Map -> command.query.ifEmpty { context.getString(R.string.search_map) }
    }

    /** The row's subtitle: the rest of the reading, or, with nothing read yet, what to type. */
    fun detail(context: Context, command: Command): String = when (command) {
        is Command.Alarm -> listOfNotNull(days(context, command.days), command.label.ifEmpty { null })
            .joinToString(SEPARATOR).ifEmpty { context.getString(R.string.search_alarm_hint) }
        is Command.Timer -> command.label.ifEmpty { context.getString(R.string.search_timer_hint) }
        is Command.Event -> eventDetail(context, command)
        is Command.Web -> context.getString(
            when {
                command.query.isEmpty() -> R.string.search_web_hint
                command.url != null -> R.string.search_open_url_subtitle
                else -> R.string.search_google_subtitle
            }
        )
        is Command.Map -> context.getString(
            if (command.query.isEmpty()) R.string.search_map_hint else R.string.search_map_subtitle
        )
    }

    private fun eventDetail(context: Context, event: Command.Event): String {
        val date = event.date ?: return context.getString(R.string.search_event_hint)
        val start = event.start
            ?: return day(context, date) + SEPARATOR + context.getString(R.string.search_event_all_day)
        val time = Times.formatter(context)
        val span = event.end?.let {
            context.getString(R.string.search_event_span, time.format(start), time.format(it))
        } ?: time.format(start)
        return day(context, date) + SEPARATOR + span
    }

    private fun days(context: Context, days: Set<DayOfWeek>): String? = when {
        days.isEmpty() -> null
        days.size == 7 -> context.getString(R.string.search_alarm_every_day)
        days == WEEKDAYS -> context.getString(R.string.search_alarm_weekdays)
        days == WEEKENDS -> context.getString(R.string.search_alarm_weekends)
        else -> days.sorted().joinToString(", ") {
            it.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
    }

    private fun length(context: Context, length: Duration): String {
        // Split by hand: Duration's own part accessors only arrived on Android 12.
        val total = length.seconds
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val seconds = total % 60
        val parts = ArrayList<String>(3)
        if (hours > 0) parts += context.getString(R.string.search_time_hours, hours)
        if (minutes > 0) parts += context.getString(R.string.search_time_minutes, minutes)
        if (seconds > 0) parts += context.getString(R.string.search_time_seconds, seconds)
        return parts.joinToString(" ")
    }

    /** "Mon, 21 Sep" in the device's own order, with the year once it isn't this one. */
    private fun day(context: Context, date: LocalDate): String {
        val skeleton = if (date.year == LocalDate.now().year) "EEEdMMM" else "EEEdMMMy"
        val locale = Locale.getDefault()
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            .format(date)
    }

    // --- intents ------------------------------------------------------------

    /** Starts the clock or calendar app on [command], prefilled, and says so if there is none. */
    fun open(context: Context, command: Command) {
        if (runCatching { context.startActivity(intentFor(command)) }.isFailure) {
            Toast.makeText(context, failureMessage(command), Toast.LENGTH_SHORT).show()
        }
    }

    @StringRes
    fun failureMessage(command: Command): Int = when (command) {
        is Command.Alarm, is Command.Timer -> R.string.clock_app_missing
        is Command.Event -> R.string.calendar_app_missing
        is Command.Web -> R.string.web_search_failed
        is Command.Map -> R.string.map_search_failed
    }

    /**
     * The platform intent for [command]. The receiving app's own UI is left on, so the alarm or
     * event is seen once more in the app that owns it before it exists — the row is a preview,
     * not a confirmation. NEW_TASK because the floating window lives in its own task.
     */
    fun intentFor(command: Command, zone: ZoneId = ZoneId.systemDefault()): Intent {
        val intent = when (command) {
            is Command.Alarm -> Intent(AlarmClock.ACTION_SET_ALARM).apply {
                command.time?.let {
                    putExtra(AlarmClock.EXTRA_HOUR, it.hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, it.minute)
                }
                if (command.label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, command.label)
                if (command.days.isNotEmpty()) {
                    putIntegerArrayListExtra(
                        AlarmClock.EXTRA_DAYS, ArrayList(command.days.sorted().map { calendarDay(it) })
                    )
                }
            }
            is Command.Timer -> Intent(AlarmClock.ACTION_SET_TIMER).apply {
                command.length?.let { putExtra(AlarmClock.EXTRA_LENGTH, it.seconds.toInt()) }
                if (command.label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, command.label)
            }
            is Command.Event -> Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                if (command.title.isNotEmpty()) putExtra(CalendarContract.Events.TITLE, command.title)
                val date = command.date ?: return@apply
                val start = command.start
                if (start == null) {
                    putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis(date.atStartOfDay(), zone))
                    return@apply
                }
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis(date.atTime(start), zone))
                command.end?.let { end ->
                    // "11pm to 1am" ends the next morning.
                    val endDay = if (end.isAfter(start)) date else date.plusDays(1)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, millis(endDay.atTime(end), zone))
                }
            }
            is Command.Web -> WebSearch.intentFor(command.query, command.url)
            // Google's documented Maps URL rather than a geo: URI. It is claimed by the Maps app
            // alone, so it opens there directly instead of through a chooser on a phone with
            // Waze installed as well, and it still works in a browser where Maps is absent.
            is Command.Map -> Intent(Intent.ACTION_VIEW, Uri.parse(MAPS_SEARCH + Uri.encode(command.query)))
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun millis(at: LocalDateTime, zone: ZoneId): Long = at.atZone(zone).toInstant().toEpochMilli()

    /** java.time's Monday-first weekdays to the Sunday-first [Calendar] constants the extra wants. */
    private fun calendarDay(day: DayOfWeek): Int = day.value % 7 + Calendar.SUNDAY

    private fun weekday(name: String): DayOfWeek =
        DayOfWeek.of(WEEKDAY_NAMES.indexOf(name.take(3)) + 1)

    // --- grammar ------------------------------------------------------------

    /**
     * The query with the slots read so far cut out. Each [take] removes the first match its reader
     * accepts, so a phrase that looks like a date but isn't one — "31 feb" — falls through to the
     * title instead of vanishing.
     */
    private class Slots(private var text: String) {
        fun <T : Any> take(regex: Regex, read: (MatchResult) -> T?): T? {
            for (m in regex.findAll(text)) {
                val value = read(m) ?: continue
                text = text.removeRange(m.range)
                return value
            }
            return null
        }

        fun rest(): String = text.replace(WHITESPACE, " ").trim().trim(',', ';', '-', '–').trim()
    }

    private const val MAX_LENGTH = 120
    private const val MAPS_SEARCH = "https://www.google.com/maps/search/?api=1&query="
    private const val SEPARATOR = " · "

    private val WHITESPACE = Regex("""\s+""")
    private val KEYWORD = Regex("""(alarm|timer|event|web|google|maps?)(?: (.*))?""", RegexOption.IGNORE_CASE)

    /** Token edges for a time: not glued to a word or another clock field. */
    private const val LEFT = """(?<![\w:])"""
    private const val RIGHT = """(?![\w:])"""

    private val WEEKDAY_NAMES = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private const val WEEKDAY =
        """(?:mon(?:day)?|tue(?:s|sday)?|wed(?:nesday)?|thu(?:r|rs|rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)"""
    private val WEEKDAYS: Set<DayOfWeek> = DayOfWeek.entries.take(5).toSet()
    private val WEEKENDS: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    private val ALARM_TIME = Regex("""$LEFT(?:at )?(${Times.TIME})$RIGHT""", RegexOption.IGNORE_CASE)
    private val ALARM_DAYS = Regex(
        """\b(?:every |on )?(weekdays|weekends|daily|every ?day|$WEEKDAY)\b,?""", RegexOption.IGNORE_CASE
    )
    private val ALARM_DAY_WORDS = Regex("""\b(?:today|tomorrow|tmrw?)\b""", RegexOption.IGNORE_CASE)

    private val TIMER_LENGTH = Regex("""$LEFT(?:for )?(${Times.DURATION})""", RegexOption.IGNORE_CASE)
    private val BARE_NUMBER = Regex("""$LEFT(\d+)$RIGHT""")

    private val EVENT_RANGE = Regex(
        """$LEFT(?:from )?(${Times.TIME}) ?(?:-|–|to) ?(${Times.TIME})$RIGHT""", RegexOption.IGNORE_CASE
    )
    private val EVENT_LENGTH = Regex("""$LEFT(?:for )?(${Times.DURATION})""", RegexOption.IGNORE_CASE)
    /** A bare hour only after "at" — "8" alone is more likely part of the title. */
    private val EVENT_TIME = Regex(
        """$LEFT(?:at (${Times.TIME})|(${Times.TIME_STRICT}))$RIGHT""", RegexOption.IGNORE_CASE
    )
    private val EVENT_DATE = Regex("""\b(?:on )?(${Dates.DATE})""", RegexOption.IGNORE_CASE)

    private val STRICT = Regex(Times.TIME_STRICT)
    private val MERIDIEM = Regex("""[ap]\.?m""")
}
