package dev.neffly.gesturelauncher.search

import android.content.Context
import android.text.format.DateFormat
import dev.neffly.gesturelauncher.R
import java.text.Normalizer
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * Turns a search query into a time-zone answer, when it is one.
 *
 * Three shapes are read, in plain words rather than a syntax:
 *  - `time in tokyo`, `tokyo time`, `now in tokyo`          → the current time there
 *  - `3pm tokyo`, `3pm in tokyo`, `tokyo 3pm`               → that time there, in the local zone
 *  - `3pm to tokyo`, `3pm est to tokyo`, `tokyo to london`  → a time here or in one zone, in another
 *
 * A time is `3pm`, `3:30 pm`, `15:00`, `noon` or `midnight`; a bare number is read on the
 * 24-hour clock. Zones are named the way people name them — by city, by the usual abbreviation
 * or by country — and resolved against the platform's own tz database, so every city that names
 * a zone there works without a list here. [ALIASES] adds what that list lacks: abbreviations,
 * countries, and big cities that share a zone with a bigger one. No network, no reference data
 * of our own to go stale, and daylight saving comes from the zone rules.
 *
 * As with [Calculator], staying quiet matters more than answering, because every keystroke
 * passes through here. A query only counts when it names a recognisable zone *and* either a
 * time or one of the "time in" phrasings — a bare city name is a search, not a question.
 */
object TimeZones {

    /**
     * The moment asked about: [target] is it in the zone the answer is about, [source] the same
     * instant in the zone it was given in — the local zone for "now in Tokyo" and "3pm to Tokyo".
     * [fromNow] marks the first shape, whose subtitle reads "now in" rather than as a conversion.
     * [today] is the local date, which is the reader's frame for "tomorrow": a given time is read
     * on the source zone's own date, and that can already be a day off from here.
     */
    data class Answer(
        val target: ZonedDateTime,
        val source: ZonedDateTime,
        val fromNow: Boolean,
        val today: LocalDate
    )

    /** [query]'s answer against [clock]'s zone and instant, or null when it isn't a time question. */
    fun answer(query: String, clock: Clock = Clock.systemDefaultZone()): Answer? {
        val q = Normalizer.normalize(query, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim().trimEnd('?').trim()
        if (q.isEmpty() || q.length > MAX_LENGTH) return null
        val now = ZonedDateTime.now(clock)

        // "time in tokyo" / "tokyo time"
        (NOW_IN.matchEntire(q) ?: ZONE_TIME.matchEntire(q))?.let { m ->
            val zone = zone(m.groupValues[1]) ?: return null
            return Answer(now.withZoneSameInstant(zone), now, fromNow = true, now.toLocalDate())
        }
        // "3pm to tokyo", "3pm in tokyo", "3pm est to tokyo"
        CONVERT.matchEntire(q)?.let { m ->
            val (timeText, sourceText, connector, targetText) = m.destructured
            val time = time(timeText) ?: return null
            val target = zone(targetText) ?: return null
            // With no source named, "to" means the time is local and the answer is there; "in"
            // and "at" mean the time is there and the answer is local — as in "3pm in Tokyo".
            return when {
                sourceText.isNotEmpty() -> convert(time, zone(sourceText) ?: return null, target, now)
                connector == "to" -> convert(time, now.zone, target, now)
                else -> convert(time, target, now.zone, now)
            }
        }
        // "3pm tokyo" / "tokyo 3pm"
        TIME_ZONE.matchEntire(q)?.let { m ->
            val (timeText, zoneText) = m.destructured
            return convert(time(timeText) ?: return null, zone(zoneText) ?: return null, now.zone, now)
        }
        ZONE_AT_TIME.matchEntire(q)?.let { m ->
            val (zoneText, timeText) = m.destructured
            return convert(time(timeText) ?: return null, zone(zoneText) ?: return null, now.zone, now)
        }
        // "tokyo to london": the current moment, read across the two.
        ZONE_TO_ZONE.matchEntire(q)?.let { m ->
            val source = zone(m.groupValues[1]) ?: return null
            val target = zone(m.groupValues[2]) ?: return null
            return convert(null, source, target, now)
        }
        return null
    }

    /** [time] today in [from] (now there, when null), as seen from [to]. */
    private fun convert(time: LocalTime?, from: ZoneId, to: ZoneId, now: ZonedDateTime): Answer {
        val sourceNow = now.withZoneSameInstant(from)
        val source = if (time == null) sourceNow else sourceNow.with(time)
        return Answer(source.withZoneSameInstant(to), source, fromNow = false, now.toLocalDate())
    }

    private fun time(text: String): LocalTime? {
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

    private fun zone(name: String): ZoneId? = ALIASES[name.trim()]?.let { ZoneId.of(it) }

    // --- display ------------------------------------------------------------

    /** The row's title: the time in [Answer.target], with the day when it isn't today here. */
    fun title(context: Context, answer: Answer): String {
        val time = timeFormatter(context).format(answer.target)
        return when (ChronoUnit.DAYS.between(answer.today, answer.target.toLocalDate())) {
            1L -> context.getString(R.string.search_time_tomorrow, time)
            -1L -> context.getString(R.string.search_time_yesterday, time)
            else -> time
        }
    }

    /** The row's subtitle: which zones the title is between, and how far apart they are. */
    fun detail(context: Context, answer: Answer): String {
        val target = zoneLabel(answer.target.zone)
        val head = if (answer.fromNow) {
            context.getString(R.string.search_time_now_in, target)
        } else {
            val sourceTime = timeFormatter(context).format(answer.source)
            context.getString(
                R.string.search_time_conversion, sourceTime, zoneLabel(answer.source.zone), target
            )
        }
        if (answer.source.zone == answer.target.zone) return head
        return head + SEPARATOR + difference(context, answer)
    }

    /**
     * The city the zone is named after, and nothing more. No abbreviation, because the platform
     * only has them for a few zones and prints "GMT+09:00" for the rest; no offset either, since
     * the hour difference already says how the two relate and the subtitle is long enough.
     */
    private fun zoneLabel(zone: ZoneId): String = zone.id.substringAfterLast('/').replace('_', ' ')

    /** How far [Answer.target]'s zone sits from [Answer.source]'s, at that instant. */
    private fun difference(context: Context, answer: Answer): String {
        val minutes = (answer.target.offset.totalSeconds - answer.source.offset.totalSeconds) / 60
        if (minutes == 0) return context.getString(R.string.search_time_same)
        val hours = abs(minutes) / 60
        val rest = abs(minutes) % 60
        val span = when {
            rest == 0 -> context.getString(R.string.search_time_hours, hours)
            hours == 0 -> context.getString(R.string.search_time_minutes, rest)
            else -> context.getString(R.string.search_time_hours_minutes, hours, rest)
        }
        val direction = if (minutes > 0) R.string.search_time_ahead else R.string.search_time_behind
        return context.getString(direction, span)
    }

    /** Follows the device's 12/24-hour setting, as the home-screen clock does. */
    private fun timeFormatter(context: Context): DateTimeFormatter =
        DateTimeFormatter.ofPattern(
            if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
            Locale.getDefault()
        )

    // --- grammar ------------------------------------------------------------

    private const val MAX_LENGTH = 80
    private const val SEPARATOR = " · "

    private val WHITESPACE = Regex("""\s+""")

    private const val TIME = """(noon|midnight|\d{1,2}(?::\d{2})?(?: ?[ap]\.?m\.?)?)"""
    private const val ZONE = """([a-z][a-z' -]*?)"""
    private val TIME_PARTS = Regex("""(\d{1,2})(?::(\d{2}))?(?: ?([ap])\.?m\.?)?""")

    private val NOW_IN = Regex(
        """(?:what(?:'s| is) the time|what time is it|current time|time now|time|now)(?: (?:in|at))? $ZONE"""
    )
    private val ZONE_TIME = Regex("""$ZONE time(?: now)?""")
    private val CONVERT = Regex("""$TIME(?: (?:in |at )?$ZONE)? (to|in|at) $ZONE""")
    private val TIME_ZONE = Regex("""$TIME $ZONE""")
    private val ZONE_AT_TIME = Regex("""$ZONE(?: at)? $TIME""")
    private val ZONE_TO_ZONE = Regex("""$ZONE to $ZONE""")

    // --- zone names ---------------------------------------------------------

    /**
     * Lower-case name → zone id. The bulk comes from the tz database itself: every region id
     * contributes its last segment (`Asia/Tokyo` → `tokyo`, `America/New_York` → `new york`),
     * which is how people name most zones anyway. The hand-written entries then win on any
     * collision: they carry the abbreviations, the countries, and cities the database has no zone
     * of their own for — and they steer `est`, `pacific` and the like to a real city rather than
     * to the fixed-offset or alias ids of the same name, so the answer shows daylight saving and
     * reads "New York" rather than "Eastern".
     */
    private val ALIASES: Map<String, String> by lazy {
        val derived = HashMap<String, String>()
        for (id in ZoneId.getAvailableZoneIds().sorted()) {
            if (id.startsWith("Etc/") || id.startsWith("SystemV/")) continue
            val name = id.substringAfterLast('/').replace('_', ' ').lowercase(Locale.ROOT)
            derived.putIfAbsent(name, id)
        }
        derived + HAND_ALIASES
    }

    private val HAND_ALIASES: Map<String, String> = buildMap<String, String> {
        fun zone(id: String, vararg names: String) = names.forEach { put(it, id) }

        zone("UTC", "utc", "gmt", "zulu")
        // North America, by the abbreviations people actually type. "cst" goes to Chicago, as
        // every search engine reads it, even though it is also China's — Beijing is spelled out.
        zone("America/New_York", "est", "edt", "et", "eastern", "nyc", "new york city", "boston",
            "washington dc", "dc", "philadelphia", "miami", "atlanta", "ottawa")
        zone("America/Chicago", "cst", "cdt", "ct", "central", "houston", "dallas", "austin",
            "minneapolis", "new orleans")
        zone("America/Denver", "mst", "mdt", "mt", "mountain", "salt lake city")
        zone("America/Phoenix", "arizona")
        zone("America/Los_Angeles", "pst", "pdt", "pt", "pacific", "san francisco", "sf",
            "seattle", "san diego", "las vegas", "portland", "silicon valley", "california")
        zone("America/Anchorage", "akst", "akdt", "alaska")
        zone("Pacific/Honolulu", "hst", "hawaii")
        zone("America/Halifax", "adt", "atlantic")
        zone("America/Mexico_City", "mexico")
        zone("America/Sao_Paulo", "brazil", "rio", "rio de janeiro")
        zone("America/Argentina/Buenos_Aires", "argentina")
        zone("America/Santiago", "chile")
        zone("America/Bogota", "colombia")
        zone("America/Lima", "peru")
        // Europe
        zone("Europe/London", "bst", "uk", "britain", "great britain", "united kingdom", "england")
        zone("Europe/Dublin", "ireland")
        zone("Europe/Lisbon", "wet", "portugal")
        zone("Europe/Paris", "cet", "cest", "france")
        zone("Europe/Berlin", "germany", "frankfurt", "munich")
        zone("Europe/Madrid", "spain", "barcelona")
        zone("Europe/Rome", "italy", "milan")
        zone("Europe/Amsterdam", "netherlands", "holland")
        zone("Europe/Brussels", "belgium")
        zone("Europe/Zurich", "switzerland", "geneva")
        zone("Europe/Vienna", "austria")
        zone("Europe/Prague", "czech", "czechia")
        zone("Europe/Stockholm", "sweden")
        zone("Europe/Oslo", "norway")
        zone("Europe/Copenhagen", "denmark")
        zone("Europe/Helsinki", "finland")
        zone("Europe/Athens", "eet", "eest", "greece")
        zone("Europe/Istanbul", "turkey")
        zone("Europe/Moscow", "msk", "russia")
        // Africa and the Middle East
        zone("Africa/Cairo", "egypt")
        zone("Africa/Johannesburg", "south africa", "cape town")
        zone("Africa/Lagos", "nigeria")
        zone("Africa/Nairobi", "kenya")
        zone("Africa/Casablanca", "morocco")
        zone("Asia/Jerusalem", "israel", "tel aviv")
        zone("Asia/Dubai", "gst", "uae", "abu dhabi")
        zone("Asia/Riyadh", "saudi", "saudi arabia")
        zone("Asia/Tehran", "iran")
        // South and South-East Asia
        zone("Asia/Kolkata", "ist", "india", "delhi", "new delhi", "mumbai", "bangalore",
            "bengaluru", "chennai", "hyderabad")
        zone("Asia/Karachi", "pkt", "pakistan")
        zone("Asia/Dhaka", "bangladesh")
        zone("Asia/Bangkok", "ict", "thailand")
        zone("Asia/Ho_Chi_Minh", "vietnam", "hanoi")
        zone("Asia/Kuala_Lumpur", "myt", "malaysia", "kl")
        zone("Asia/Singapore", "sgt")
        zone("Asia/Jakarta", "wib", "indonesia", "bali")
        zone("Asia/Manila", "pht", "philippines")
        // East Asia
        zone("Asia/Shanghai", "china", "beijing", "shenzhen", "guangzhou", "hangzhou", "chengdu")
        zone("Asia/Hong_Kong", "hkt", "hk")
        zone("Asia/Taipei", "taiwan")
        zone("Asia/Tokyo", "jst", "japan", "osaka")
        zone("Asia/Seoul", "kst", "korea", "south korea")
        // Oceania
        zone("Australia/Sydney", "aest", "aedt", "aet", "australia")
        zone("Australia/Adelaide", "acst", "acdt")
        zone("Australia/Perth", "awst")
        zone("Pacific/Auckland", "nzst", "nzdt", "new zealand")
    }
}
