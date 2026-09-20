package dev.neffly.gesturelauncher.notes

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One note on the notes page. [id] is the moment it was sent, which is also its place in the
 *  list; [editedAt] is set once it has been changed since. */
@Serializable
data class Note(val id: Long, val text: String, val editedAt: Long? = null)

/**
 * The notes page's list, oldest first.
 *
 * A JSON list in preferences, like the widgets: the whole list is small, is read once when the
 * home screen is built, and is written back as a unit on every send, edit and delete. Notes are
 * the user's own words, so unlike widgets they travel in the launcher backup.
 */
object NoteStore {

    private const val FILE = "notes"
    private const val KEY_NOTES = "notes"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): List<Note> {
        val raw = prefs(context).getString(KEY_NOTES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Note>>(raw) }.getOrDefault(emptyList())
    }

    fun save(context: Context, notes: List<Note>) {
        prefs(context).edit().putString(KEY_NOTES, json.encodeToString(notes)).apply()
    }

    /** Overlays [notes] on what is stored, by id, keeping the list in time order. */
    fun merge(context: Context, notes: List<Note>) {
        val byId = load(context).associateBy { it.id }.toMutableMap()
        notes.forEach { byId[it.id] = it }
        save(context, byId.values.sortedBy { it.id })
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
