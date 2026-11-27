package dev.neffly.gesturelauncher.data

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One JSON file in the app's files dir holding a list of [T], cached in memory. Shared by every
 * small user-authored list (gestures, quicklinks, pie apps), so they all get the same crash
 * safety.
 *
 * Writes go through [AtomicFile] (write-to-temp + rename), so a crash or power loss mid-write
 * can never leave a truncated file behind. If the file is ever unparseable anyway, it's kept as
 * `<name>.corrupt` instead of being silently overwritten with an empty list — not `.bak`, which
 * is the name [AtomicFile] keeps for itself and clears on the next write.
 */
internal class JsonListFile<T>(private val fileName: String, element: KSerializer<T>) {

    private val serializer = ListSerializer(element)

    @Volatile
    private var cache: List<T>? = null

    @Synchronized
    fun read(context: Context): List<T> {
        cache?.let { return it }
        val file = File(context.filesDir, fileName)
        val loaded = if (file.exists()) {
            runCatching {
                json.decodeFromString(serializer, AtomicFile(file).readFully().decodeToString())
            }.getOrElse { e ->
                Log.e(TAG, "failed to parse $fileName; keeping it as $fileName.corrupt", e)
                file.renameTo(File(context.filesDir, "$fileName.corrupt"))
                emptyList()
            }
        } else {
            emptyList()
        }
        return loaded.also { cache = it }
    }

    @Synchronized
    fun write(context: Context, list: List<T>) {
        val snapshot = list.toList()
        cache = snapshot
        val atomic = AtomicFile(File(context.filesDir, fileName))
        val out = atomic.startWrite()
        try {
            out.write(json.encodeToString(serializer, snapshot).encodeToByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            Log.e(TAG, "failed to save $fileName", e)
        }
    }

    @Synchronized
    fun update(context: Context, transform: (List<T>) -> List<T>) = write(context, transform(read(context)))

    private companion object {
        const val TAG = "JsonListFile"
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
