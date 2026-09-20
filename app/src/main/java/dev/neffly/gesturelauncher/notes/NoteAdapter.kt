package dev.neffly.gesturelauncher.notes

import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.ui.FontEngine
import java.util.Calendar

/**
 * Notes as sent messages, with the day above each run written on a different day.
 *
 * Rows are derived from the note list by [rowsFor]; DiffUtil then animates a send as one bubble
 * appearing rather than the whole list rebinding.
 */
class NoteAdapter(
    private val onLongPress: (note: Note, bubble: View) -> Unit
) : ListAdapter<NoteAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    sealed class Row {
        data class Day(val dayStart: Long) : Row()
        data class Item(val note: Note) : Row()
    }

    fun submitNotes(notes: List<Note>, onCommitted: (() -> Unit)? = null) {
        submitList(rowsFor(notes), onCommitted)
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is Row.Day) TYPE_DAY else TYPE_NOTE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = if (viewType == TYPE_DAY) R.layout.item_note_day else R.layout.item_note
        val view = inflater.inflate(layout, parent, false)
        // Rows are built long after the activity's pass over its content view.
        FontEngine.applyTo(view)
        return if (viewType == TYPE_DAY) DayVH(view) else NoteVH(view, onLongPress)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Day -> (holder as DayVH).bind(row)
            is Row.Item -> (holder as NoteVH).bind(row.note)
        }
    }

    class DayVH(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.dayLabel)

        fun bind(row: Row.Day) {
            val context = itemView.context
            label.text = when {
                DateUtils.isToday(row.dayStart) -> context.getString(R.string.note_today)
                DateUtils.isToday(row.dayStart + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.note_yesterday)
                else -> DateUtils.formatDateTime(
                    context, row.dayStart,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL
                )
            }
        }
    }

    class NoteVH(view: View, onLongPress: (Note, View) -> Unit) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.noteText)
        private val time: TextView = view.findViewById(R.id.noteTime)
        private var note: Note? = null

        init {
            // On the bubble, not the row: the row spans the page, and a press on the empty space
            // beside a short note should be nothing.
            text.setOnLongClickListener { note?.let { onLongPress(it, text) }; true }
        }

        fun bind(note: Note) {
            this.note = note
            text.text = note.text
            val context = itemView.context
            val stamp = DateFormat.getTimeFormat(context).format(note.id)
            time.text = if (note.editedAt == null) stamp else "$stamp · ${context.getString(R.string.note_edited)}"
        }
    }

    companion object {
        private const val TYPE_DAY = 0
        private const val TYPE_NOTE = 1

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row): Boolean = when {
                a is Row.Day && b is Row.Day -> a.dayStart == b.dayStart
                a is Row.Item && b is Row.Item -> a.note.id == b.note.id
                else -> false
            }
            override fun areContentsTheSame(a: Row, b: Row): Boolean = a == b
        }

        /** [notes] in order, each run from a new day led by that day's label. */
        fun rowsFor(notes: List<Note>): List<Row> {
            val rows = ArrayList<Row>(notes.size + 8)
            var lastDay = Long.MIN_VALUE
            for (note in notes) {
                val day = startOfDay(note.id)
                if (day != lastDay) {
                    rows += Row.Day(day)
                    lastDay = day
                }
                rows += Row.Item(note)
            }
            return rows
        }

        private fun startOfDay(millis: Long): Long {
            val c = Calendar.getInstance()
            c.timeInMillis = millis
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
    }
}
