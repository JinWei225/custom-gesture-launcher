package dev.neffly.gesturelauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Parsing and conversion only — the row's wording needs a Context and is checked on a device.
 * Like [CalculatorTest], the second half is about staying quiet for ordinary queries.
 */
class TimeZonesTest {

    /** Tuesday 2026-09-15, 10:00 in Kuala Lumpur (UTC+8): 02:00 UTC, 11:00 Tokyo, 22:00 the
     *  previous evening in New York (EDT, UTC-4). */
    private val clock: Clock =
        Clock.fixed(Instant.parse("2026-09-15T02:00:00Z"), ZoneId.of("Asia/Kuala_Lumpur"))

    private fun answer(query: String) = TimeZones.answer(query, clock)

    private fun assertTarget(query: String, zone: String, time: String, date: String = "2026-09-15") {
        val answer = answer(query)
        assertNotNull("no answer for '$query'", answer)
        assertEquals(ZoneId.of(zone), answer!!.target.zone)
        assertEquals(LocalTime.parse(time), answer.target.toLocalTime())
        assertEquals(LocalDate.parse(date), answer.target.toLocalDate())
    }

    // --- the current time somewhere -----------------------------------------

    @Test
    fun `answers the current time in a named zone`() {
        assertTarget("time in tokyo", "Asia/Tokyo", "11:00")
        assertTarget("tokyo time", "Asia/Tokyo", "11:00")
        assertTarget("now in tokyo", "Asia/Tokyo", "11:00")
        assertTarget("time tokyo", "Asia/Tokyo", "11:00")
        assertTarget("what time is it in tokyo", "Asia/Tokyo", "11:00")
        assertTarget("time now in new york", "America/New_York", "22:00", "2026-09-14")
    }

    @Test
    fun `marks the current time as now, in the local zone as the reference`() {
        val answer = answer("time in tokyo")!!
        assertTrue(answer.fromNow)
        assertEquals(ZoneId.of("Asia/Kuala_Lumpur"), answer.source.zone)
        assertEquals(LocalTime.of(10, 0), answer.source.toLocalTime())
    }

    // --- a time there, in the local zone -----------------------------------

    @Test
    fun `reads a time followed by a zone as that time there`() {
        assertTarget("3pm tokyo", "Asia/Kuala_Lumpur", "14:00")
        assertTarget("3pm in tokyo", "Asia/Kuala_Lumpur", "14:00")
        assertTarget("3 pm at tokyo", "Asia/Kuala_Lumpur", "14:00")
        assertTarget("tokyo 3pm", "Asia/Kuala_Lumpur", "14:00")
        assertTarget("tokyo at 3pm", "Asia/Kuala_Lumpur", "14:00")
    }

    @Test
    fun `keeps the source alongside the answer`() {
        val answer = answer("3pm tokyo")!!
        assertFalse(answer.fromNow)
        assertEquals(ZoneId.of("Asia/Tokyo"), answer.source.zone)
        assertEquals(LocalTime.of(15, 0), answer.source.toLocalTime())
    }

    // --- conversions --------------------------------------------------------

    @Test
    fun `reads a time to a zone as local time there`() {
        assertTarget("3pm to tokyo", "Asia/Tokyo", "16:00")
        assertTarget("9am to london", "Europe/London", "02:00")
    }

    /** A given time is read on the source zone's own date: it is still Monday in New York. */
    @Test
    fun `converts between two named zones`() {
        assertTarget("3pm est to tokyo", "Asia/Tokyo", "04:00")
        assertTarget("3pm new york to tokyo", "Asia/Tokyo", "04:00")
        assertTarget("3pm in new york to tokyo", "Asia/Tokyo", "04:00")
        assertTarget("9am tokyo in london", "Europe/London", "01:00")
    }

    @Test
    fun `converts the current moment between two zones`() {
        assertTarget("tokyo to london", "Europe/London", "03:00")
        val answer = answer("tokyo to london")!!
        assertEquals(LocalTime.of(11, 0), answer.source.toLocalTime())
    }

    @Test
    fun `crosses midnight in either direction`() {
        assertTarget("11pm to tokyo", "Asia/Tokyo", "00:00", "2026-09-16")
        assertTarget("1am tokyo", "Asia/Kuala_Lumpur", "00:00")
        assertTarget("10pm new york", "Asia/Kuala_Lumpur", "10:00")
    }

    /** The reader's frame for "tomorrow" is the local date, whatever date the source is on. */
    @Test
    fun `reports the local date as today`() {
        assertEquals(LocalDate.parse("2026-09-15"), answer("10pm new york")!!.today)
        assertEquals(LocalDate.parse("2026-09-15"), answer("time in new york")!!.today)
    }

    // --- time formats -------------------------------------------------------

    @Test
    fun `reads the ways a time is written`() {
        assertTarget("3:30pm tokyo", "Asia/Kuala_Lumpur", "14:30")
        assertTarget("3:30 p.m. tokyo", "Asia/Kuala_Lumpur", "14:30")
        assertTarget("15:30 tokyo", "Asia/Kuala_Lumpur", "14:30")
        assertTarget("15 tokyo", "Asia/Kuala_Lumpur", "14:00")
        assertTarget("12am tokyo", "Asia/Kuala_Lumpur", "23:00", "2026-09-14")
        assertTarget("12pm tokyo", "Asia/Kuala_Lumpur", "11:00")
        assertTarget("noon tokyo", "Asia/Kuala_Lumpur", "11:00")
        assertTarget("midnight to tokyo", "Asia/Tokyo", "01:00")
    }

    @Test
    fun `rejects impossible times`() {
        assertNull(answer("25:00 tokyo"))
        assertNull(answer("13pm tokyo"))
        assertNull(answer("0pm tokyo"))
        assertNull(answer("3:60pm tokyo"))
    }

    // --- zone names ---------------------------------------------------------

    @Test
    fun `resolves cities from the tz database`() {
        assertTarget("time in new york", "America/New_York", "22:00", "2026-09-14")
        assertTarget("time in los angeles", "America/Los_Angeles", "19:00", "2026-09-14")
        assertTarget("time in hong kong", "Asia/Hong_Kong", "10:00")
        assertTarget("time in ho chi minh", "Asia/Ho_Chi_Minh", "09:00")
        assertTarget("time in kolkata", "Asia/Kolkata", "07:30")
    }

    @Test
    fun `resolves abbreviations to a real city so daylight saving applies`() {
        // September: New York is on EDT, so "est" must not mean a fixed UTC-5.
        assertTarget("3pm est to tokyo", "Asia/Tokyo", "04:00")
        assertTarget("time in pst", "America/Los_Angeles", "19:00", "2026-09-14")
        assertTarget("time in cet", "Europe/Paris", "04:00")
        assertTarget("time in utc", "UTC", "02:00")
        assertTarget("time in gmt", "UTC", "02:00")
    }

    @Test
    fun `resolves countries and cities that share a bigger city's zone`() {
        assertTarget("time in japan", "Asia/Tokyo", "11:00")
        assertTarget("time in uk", "Europe/London", "03:00")
        assertTarget("time in beijing", "Asia/Shanghai", "10:00")
        assertTarget("time in mumbai", "Asia/Kolkata", "07:30")
        assertTarget("time in san francisco", "America/Los_Angeles", "19:00", "2026-09-14")
        assertTarget("time in kl", "Asia/Kuala_Lumpur", "10:00")
    }

    @Test
    fun `ignores case, extra spaces and a question mark`() {
        assertTarget("Time in Tokyo?", "Asia/Tokyo", "11:00")
        assertTarget("  3PM   New York  ", "Asia/Kuala_Lumpur", "03:00")
        assertTarget("３pm　tokyo", "Asia/Kuala_Lumpur", "14:00")
    }

    // --- knowing when not to answer -----------------------------------------

    @Test
    fun `ignores a bare zone name`() {
        assertNull(answer("tokyo"))
        assertNull(answer("new york"))
        assertNull(answer("japan"))
    }

    @Test
    fun `ignores ordinary search queries`() {
        assertNull(answer("gmail"))
        assertNull(answer("screen time"))
        assertNull(answer("time machine"))
        assertNull(answer("timer"))
        assertNull(answer("how to draw"))
        assertNull(answer("iphone 15"))
        assertNull(answer("chrome 3"))
        assertNull(answer("time"))
        assertNull(answer("now"))
        assertNull(answer(""))
    }

    @Test
    fun `ignores a time with no zone`() {
        assertNull(answer("3pm"))
        assertNull(answer("15:00"))
        assertNull(answer("3pm to nowhere"))
    }

    @Test
    fun `ignores arithmetic, which is the calculator's`() {
        assertNull(answer("3 - 5"))
        assertNull(answer("12*12"))
    }
}
