package dev.neffly.gesturelauncher.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.neffly.gesturelauncher.R
import dev.neffly.gesturelauncher.ui.GlassMenu

/**
 * The home screen's notes page: a box at the bottom, and everything ever sent from it stacked
 * above as messages, newest last.
 *
 * Sending is the whole interaction — there is no separate save. A long-press on a note offers to
 * copy it, edit it (it goes back into the box, and the send button becomes a save) or delete it.
 */
class NotesPage(private val activity: AppCompatActivity, page: View) {

    private val list: RecyclerView = page.findViewById(R.id.noteList)
    private val empty: TextView = page.findViewById(R.id.notesEmpty)
    private val input: EditText = page.findViewById(R.id.noteInput)
    private val send: ImageButton = page.findViewById(R.id.noteSendButton)

    private val notes: MutableList<Note> = NoteStore.load(activity).toMutableList()
    private val adapter = NoteAdapter { note, bubble -> showMenu(note, bubble) }

    /** The note in the box for editing, or null when the box is composing a new one. */
    private var editing: Note? = null

    init {
        // From the end, like a conversation: the newest note sits just above the box, and a short
        // list hugs the bottom rather than the top.
        list.layoutManager = LinearLayoutManager(activity).apply { stackFromEnd = true }
        list.adapter = adapter
        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, event ->
            val isSend = actionId == EditorInfo.IME_ACTION_SEND ||
                (actionId == EditorInfo.IME_ACTION_UNSPECIFIED &&
                    event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isSend) submit()
            isSend
        }
        show(scrollToEnd = true)
    }

    /** Picks up notes written to the store behind this page's back — a backup import, which
     *  happens in the settings hub while the home screen waits underneath. A page that matches
     *  the store is left alone, draft and edit in progress included. */
    fun refresh() {
        val stored = NoteStore.load(activity)
        if (stored == notes) return
        notes.clear()
        notes += stored
        cancelEdit()
        input.text.clear()
        show(scrollToEnd = true)
    }

    private fun show(scrollToEnd: Boolean) {
        empty.isVisible = notes.isEmpty()
        adapter.submitNotes(notes.toList()) {
            if (scrollToEnd && adapter.itemCount > 0) list.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val editing = editing
        if (editing == null) {
            notes += Note(id = System.currentTimeMillis(), text = text)
        } else {
            val index = notes.indexOfFirst { it.id == editing.id }
            if (index >= 0) notes[index] = editing.copy(text = text, editedAt = System.currentTimeMillis())
            cancelEdit()
        }
        NoteStore.save(activity, notes)
        input.text.clear()
        show(scrollToEnd = editing == null)
    }

    private fun showMenu(note: Note, bubble: View) {
        GlassMenu.show(
            bubble,
            listOf(
                GlassMenu.item(bubble, R.string.note_copy, R.drawable.ic_copy) { copy(note) },
                GlassMenu.item(bubble, R.string.note_edit, R.drawable.ic_edit) { edit(note) },
                GlassMenu.item(bubble, R.string.note_delete, R.drawable.ic_delete, destructive = true) { delete(note) }
            ),
            overWallpaper = true,
            gravity = Gravity.END
        )
    }

    private fun copy(note: Note) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("note", note.text))
        // Android 13+ shows its own "copied" confirmation; a second one would be noise.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(activity, R.string.note_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun edit(note: Note) {
        editing = note
        input.setText(note.text)
        input.setSelection(note.text.length)
        send.setImageResource(R.drawable.ic_check)
        send.contentDescription = activity.getString(R.string.note_save)
        input.requestFocus()
        WindowInsetsControllerCompat(activity.window, input).show(WindowInsetsCompat.Type.ime())
    }

    private fun cancelEdit() {
        editing = null
        send.setImageResource(R.drawable.ic_send)
        send.contentDescription = activity.getString(R.string.note_send)
    }

    private fun delete(note: Note) {
        notes.removeAll { it.id == note.id }
        if (editing?.id == note.id) {
            cancelEdit()
            input.text.clear()
        }
        NoteStore.save(activity, notes)
        show(scrollToEnd = false)
    }
}
