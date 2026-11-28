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

    /** Adds [mappings], replacing any already stored under the same id, in one write — backup
     *  restore's "Merge" mode, which used to rewrite the file once per gesture. */
    fun upsertAll(context: Context, mappings: List<GestureMapping>) = file.update(context) { list ->
        val incoming = mappings.associateBy { it.id }
        list.map { incoming[it.id] ?: it } + mappings.filter { m -> list.none { it.id == m.id } }
    }

    /** Wipes and replaces the whole store — used by backup restore's "Replace all" mode. */
    fun replaceAll(context: Context, list: List<GestureMapping>) = file.write(context, list)
}
