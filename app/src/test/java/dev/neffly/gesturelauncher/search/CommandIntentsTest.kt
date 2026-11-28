package dev.neffly.gesturelauncher.search

import android.provider.AlarmClock
import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar

/** The intents [Commands] hands the clock and calendar apps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandIntentsTest {

    private val zone = ZoneId.of("Asia/Kuala_Lumpur")

    /** Saturday 2026-09-19, 14:00 in Kuala Lumpur. */
    private val clock = Clock.fixed(Instant.parse("2026-09-19T06:00:00Z"), zone)

    private fun intent(query: String) =
        Commands.intentFor(RuntimeEnvironment.getApplication(), Commands.parse(query, clock)!!, zone)

    private fun millis(text: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone).toInstant().toEpochMilli()

    @Test
    fun `an event past midnight ends the next day`() {
        val i = intent("event party tomorrow 11pm-1am")
        assertEquals(millis("2026-09-20T23:00"), i.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0))
        assertEquals(millis("2026-09-21T01:00"), i.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0))
    }

    @Test
    fun `a long event keeps its whole length`() {
        val i = intent("event retreat tomorrow 9am for 25h")
        assertEquals(millis("2026-09-21T10:00"), i.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0))
    }

    @Test
    fun `an all-day event begins at UTC midnight`() {
        val i = intent("event trip 21 sep")
        assertTrue(i.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false))
        assertEquals(Instant.parse("2026-09-21T00:00:00Z").toEpochMilli(), i.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0))
    }

    @Test
    fun `alarm days map onto the calendar constants`() {
        val i = intent("alarm 7am mon sun")
        assertEquals(7, i.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(arrayListOf(Calendar.MONDAY, Calendar.SUNDAY), i.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS))
    }

    @Test
    fun `a timer passes its length in seconds`() {
        assertEquals(90 * 60, intent("timer 1h30").getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
    }
}
