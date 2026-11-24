package dev.neffly.gesturelauncher.data

import android.content.Context

/** Gesture -> action mappings, persisted as `gestures.json` (see [JsonListFile]). */
object GestureStore {

    private val file = JsonListFile("gestures.json", GestureMapping.serializer())

    fun all(context: Context): List<GestureMapping> = file.read(context)

    fun add(context: Context, mapping: GestureMapping) = file.update(context) { it + mapping }

    fun update(context: Context, mapping: GestureMapping) = file.update(context) { list ->
        if (list.any { it.id == mapping.id }) list.map { if (it.id == mapping.id) mapping else it }
        else list + mapping
    }

    fun remove(context: Context, id: String) = file.update(context) { list -> list.filterNot { it.id == id } }

    /** Wipes and replaces the whole store — used by backup restore's "Replace all" mode. */
    fun replaceAll(context: Context, list: List<GestureMapping>) = file.write(context, list)
}
