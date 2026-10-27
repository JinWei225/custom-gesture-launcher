package dev.neffly.gesturelauncher.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Calendar

/**
 * The day rows' labels are relative to today, so the rows have to change when today does —
 * otherwise a note written at 23:59 keeps its "Today" label above one written at 00:00.
 */
class NoteAdapterTest {

    private fun at(day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.OCTOBER, day, hour, minute)
        }.timeInMillis

    @Test
    fun `notes either side of midnight get separate days, only the second of them today`() {
        val notes = listOf(Note(at(27, 23, 59), "late"), Note(at(28, 0, 0), "early"))
        val days = NoteAdapter.rowsFor(notes, now = at(28, 0, 1)).filterIsInstance<NoteAdapter.Row.Day>()

        assertEquals(2, days.size)
        val today = NoteAdapter.startOfDay(at(28, 0, 1))
        assertNotEquals(today, days[0].dayStart)
        assertEquals(today, days[1].dayStart)
        assertEquals(today, days[0].today)
    }

    @Test
    fun `a day row built after midnight differs from the same row built before it`() {
        val notes = listOf(Note(at(27, 23, 59), "late"))
        val before = NoteAdapter.rowsFor(notes, now = at(27, 23, 59))
        val after = NoteAdapter.rowsFor(notes, now = at(28, 0, 0))

        // Differing content is what makes DiffUtil rebind the row and relabel it "Yesterday".
        assertNotEquals(before[0], after[0])
    }
}
