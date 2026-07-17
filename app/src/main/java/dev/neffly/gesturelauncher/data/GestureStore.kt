package dev.neffly.gesturelauncher.data

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JSON-file persistence for gesture -> app mappings. Dependency-free, easy to inspect/back up.
 * All access is synchronized and cached in memory; the data set is tiny (a handful of gestures).
 *
 * Writes go through [AtomicFile] (write-to-temp + rename), so a crash or power loss mid-write
 * can never leave a truncated file behind. If the file is ever unparseable anyway, it's kept as
 * `gestures.json.bak` instead of being silently overwritten with an empty store.
 */
object GestureStore {

    private const val TAG = "GestureStore"
    private const val FILE_NAME = "gestures.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var cache: MutableList<GestureMapping>? = null

    @Synchronized
    fun all(context: Context): List<GestureMapping> = load(context).toList()

    @Synchronized
    fun add(context: Context, mapping: GestureMapping) {
        val list = load(context)
        list.add(mapping)
        save(context, list)
    }

    @Synchronized
    fun update(context: Context, mapping: GestureMapping) {
        val list = load(context)
        val idx = list.indexOfFirst { it.id == mapping.id }
        if (idx >= 0) list[idx] = mapping else list.add(mapping)
        save(context, list)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val list = load(context)
        list.removeAll { it.id == id }
        save(context, list)
    }

    /** Wipes and replaces the whole store — used by backup restore's "Replace all" mode. */
    @Synchronized
    fun replaceAll(context: Context, list: List<GestureMapping>) {
        save(context, list.toMutableList())
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun load(context: Context): MutableList<GestureMapping> {
        cache?.let { return it }
        val file = file(context)
        val loaded = if (file.exists()) {
            runCatching {
                json.decodeFromString<List<GestureMapping>>(
                    AtomicFile(file).readFully().decodeToString()
                )
            }.getOrElse { e ->
                // Don't silently start empty over a corrupt file — preserve it for recovery.
                Log.e(TAG, "failed to parse $FILE_NAME; keeping it as $FILE_NAME.bak", e)
                file.renameTo(File(context.filesDir, "$FILE_NAME.bak"))
                emptyList()
            }
        } else {
            emptyList()
        }
        return loaded.toMutableList().also { cache = it }
    }

    private fun save(context: Context, list: MutableList<GestureMapping>) {
        cache = list
        val atomic = AtomicFile(file(context))
        val out = atomic.startWrite()
        try {
            out.write(json.encodeToString(list.toList()).encodeToByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            Log.e(TAG, "failed to save $FILE_NAME", e)
        }
    }
}
