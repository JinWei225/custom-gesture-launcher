package dev.neffly.gesturelauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Parsing only — the row's wording and the intents need a Context and are checked on a device. */
class CommandsTest {

    /** Saturday 2026-09-19, 14:00 in Kuala Lumpur. */
    private val clock: Clock =
        Clock.fixed(Instant.parse("2026-09-19T06:00:00Z"), ZoneId.of("Asia/Kuala_Lumpur"))

    private fun parse(query: String) = Commands.parse(query, clock)

    private fun alarm(query: String) = parse(query) as Command.Alarm
    private fun timer(query: String) = parse(query) as Command.Timer
    private fun event(query: String) = parse(query) as Command.Event

    private fun t(text: String) = LocalTime.parse(text)
    private fun d(text: String) = LocalDate.parse(text)

    // --- keywords -----------------------------------------------------------

    @Test
    fun `only a leading keyword triggers`() {
        assertNull(parse("set an alarm for 7am"))
        assertNull(parse("alarms"))
        assertNull(parse("timers 10 min"))
        assertNull(parse("eventful evening"))
        assertNull(parse("team discussion event"))
    }

    @Test
    fun `a bare keyword is an empty command`() {
        assertEquals(Command.Alarm(null, emptySet(), ""), parse("alarm"))
        assertEquals(Command.Timer(null, ""), parse("Timer"))
        assertEquals(Command.Event("", null, null, null), parse("event "))
    }

    // --- alarm --------------------------------------------------------------

    @Test
    fun `reads an alarm time in any of the usual shapes`() {
        assertEquals(t("07:00"), alarm("alarm 7am").time)
        assertEquals(t("07:30"), alarm("alarm 7:30 am").time)
        assertEquals(t("19:30"), alarm("alarm at 7:30pm").time)
        assertEquals(t("19:30"), alarm("alarm 19:30").time)
        assertEquals(t("07:00"), alarm("alarm 7").time)
        assertEquals(t("12:00"), alarm("alarm noon").time)
    }

    @Test
    fun `everything that is not a time or a day is the label`() {
        assertEquals("gym", alarm("alarm 7am gym").label)
        assertEquals("Gym session", alarm("alarm Gym session 7am").label)
        assertEquals("", alarm("alarm 7am").label)
    }

    @Test
    fun `reads repeat days`() {
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            alarm("alarm 7am mon wed fri").days)
        assertEquals(DayOfWeek.entries.take(5).toSet(), alarm("alarm 7am weekdays gym").days)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), alarm("alarm 9am weekends").days)
        assertEquals(DayOfWeek.entries.toSet(), alarm("alarm 7am every day").days)
        assertEquals(setOf(DayOfWeek.MONDAY), alarm("alarm every monday 7am").days)
        assertEquals("gym", alarm("alarm 7am weekdays gym").label)
    }

    @Test
    fun `tomorrow is understood, not kept as a label`() {
        val a = alarm("alarm 6am tomorrow")
        assertEquals(t("06:00"), a.time)
        assertEquals("", a.label)
    }

    @Test
    fun `an alarm without a time still parses`() {
        assertEquals(Command.Alarm(null, emptySet(), "gym"), parse("alarm gym"))
    }

    // --- timer --------------------------------------------------------------

    @Test
    fun `reads timer lengths`() {
        assertEquals(Duration.ofMinutes(10), timer("timer 10 min").length)
        assertEquals(Duration.ofMinutes(10), timer("timer 10m").length)
        assertEquals(Duration.ofMinutes(10), timer("timer 10").length)
        assertEquals(Duration.ofMinutes(90), timer("timer 1h30m").length)
        assertEquals(Duration.ofMinutes(90), timer("timer 1 hour 30 minutes").length)
        assertEquals(Duration.ofMinutes(90), timer("timer 1.5h").length)
        assertEquals(Duration.ofSeconds(45), timer("timer 45s").length)
        assertEquals(Duration.ofSeconds(45), timer("timer for 45 sec").length)
    }

    @Test
    fun `the rest is the timer label`() {
        assertEquals("pasta", timer("timer 10 min pasta").label)
        assertEquals("pasta", timer("timer pasta 10 min").label)
        assertEquals("Boil eggs", timer("timer Boil eggs 7m").label)
        assertEquals(Command.Timer(null, "pasta"), parse("timer pasta"))
    }

    // --- event --------------------------------------------------------------

    @Test
    fun `the motivating case`() {
        val e = event("event Team Discussion next monday 8pm")
        assertEquals("Team Discussion", e.title)
        assertEquals(d("2026-09-21"), e.date)
        assertEquals(t("20:00"), e.start)
        assertNull(e.end)
    }

    @Test
    fun `slots can come in any order and carry prepositions`() {
        val e = event("event on monday at 8pm Team Discussion")
        assertEquals("Team Discussion", e.title)
        assertEquals(d("2026-09-21"), e.date)
        assertEquals(t("20:00"), e.start)
        assertEquals("dinner with sam", event("event dinner with sam on fri at 7pm").title)
    }

    @Test
    fun `reads days relative to today`() {
        assertEquals(d("2026-09-19"), event("event x today").date)
        assertEquals(d("2026-09-20"), event("event x tomorrow").date)
        assertEquals(d("2026-09-20"), event("event x tmr").date)
        assertEquals(d("2026-09-22"), event("event x in 3 days").date)
        assertEquals(d("2026-10-03"), event("event x in 2 weeks").date)
        // Saturday today: "saturday" is today, "next saturday" a week on, "sunday" tomorrow.
        assertEquals(d("2026-09-19"), event("event x saturday").date)
        assertEquals(d("2026-09-26"), event("event x next saturday").date)
        assertEquals(d("2026-09-20"), event("event x sunday").date)
        assertEquals(d("2026-09-20"), event("event x next sunday").date)
        assertEquals(d("2026-09-25"), event("event x fri").date)
    }

    @Test
    fun `reads explicit dates, rolling a past day into next year`() {
        assertEquals(d("2026-09-21"), event("event x 21 sep").date)
        assertEquals(d("2026-09-21"), event("event x 21st September").date)
        assertEquals(d("2026-09-21"), event("event x sep 21").date)
        assertEquals(d("2027-09-21"), event("event x sep 21, 2027").date)
        assertEquals(d("2026-09-21"), event("event x 2026-09-21").date)
        assertEquals(d("2027-03-01"), event("event x 1 mar").date)
        assertEquals("x 31 feb", event("event x 31 feb").title)
    }

    @Test
    fun `a bare time is today until it has passed, then tomorrow`() {
        assertEquals(d("2026-09-19"), event("event x 8pm").date)
        assertEquals(d("2026-09-20"), event("event x 9am").date)
    }

    @Test
    fun `a bare hour is only a time after at`() {
        val e = event("event room 8 booking at 3pm")
        assertEquals("room 8 booking", e.title)
        assertEquals(t("15:00"), e.start)
        assertEquals(t("07:00"), event("event dinner at 7").start)
    }

    @Test
    fun `reads a time range, borrowing the meridiem`() {
        var e = event("event standup 8-9pm mon")
        assertEquals(t("20:00") to t("21:00"), e.start to e.end)
        assertEquals("standup", e.title)
        e = event("event lunch 11-1pm")
        assertEquals(t("11:00") to t("13:00"), e.start to e.end)
        e = event("event x 8:30 to 9pm")
        assertEquals(t("20:30") to t("21:00"), e.start to e.end)
        e = event("event x from 20:00 – 21:30")
        assertEquals(t("20:00") to t("21:30"), e.start to e.end)
        e = event("event x 12-1pm")
        assertEquals(t("12:00") to t("13:00"), e.start to e.end)
        // Neither side is unmistakably a time, so it stays in the title.
        e = event("event grade 8-9 meeting 3pm")
        assertEquals("grade 8-9 meeting", e.title)
        assertEquals(t("15:00"), e.start)
    }

    @Test
    fun `a length sets the end`() {
        val e = event("event call 3pm for 30 min")
        assertEquals(t("15:00") to t("15:30"), e.start to e.end)
        assertEquals("call", e.title)
        assertEquals(t("22:30"), event("event x 9pm 1h30m").end)
    }

    @Test
    fun `a day without a time is all day, and neither is nothing`() {
        val e = event("event Trip to Penang 21 sep")
        assertEquals("Trip to Penang", e.title)
        assertEquals(d("2026-09-21"), e.date)
        assertNull(e.start)
        assertEquals(Command.Event("Team Discussion", null, null, null), parse("event Team Discussion"))
    }

    // --- web and map --------------------------------------------------------

    @Test
    fun `web and map take the rest of the line`() {
        assertEquals(Command.Web("how tall is K2", null), parse("web how tall is K2"))
        assertEquals(Command.Web("how tall is K2", null), parse("google how tall is K2"))
        assertEquals(Command.Web("docs.google.com", "https://docs.google.com"), parse("web docs.google.com"))
        assertEquals(Command.Web("", null), parse("web"))
        assertEquals(Command.Map("petrol station near me"), parse("map petrol station near me"))
        assertEquals(Command.Map("KLCC"), parse("maps KLCC"))
        assertEquals(Command.Map(""), parse("map"))
        assertNull(parse("mapping tools"))
        assertNull(parse("googled it"))
    }
}
