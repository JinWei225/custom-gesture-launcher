package dev.neffly.gesturelauncher.data

import android.graphics.PointF
import dev.neffly.gesturelauncher.unistroke.GestureTemplate
import dev.neffly.gesturelauncher.unistroke.Pt
import kotlinx.serialization.Serializable

/** A single 2D sample point of a drawn stroke (screen-independent once normalized on match). */
@Serializable
data class SPoint(val x: Float, val y: Float)

/** What a recognized gesture does. */
@Serializable
enum class GestureAction { LAUNCH_APP, OPEN_DRAWER, OPEN_URL, QUICK_SEARCH, NOTIFICATIONS, QUICK_SETTINGS }

/**
 * One gesture -> action mapping. Stores the raw strokes from the 3x training pass as separate
 * templates (kept individually rather than averaged for more robust $1 matching).
 *
 * [packageName]/[componentName] are only meaningful for [GestureAction.LAUNCH_APP] (empty string
 * otherwise); [url] is only set for [GestureAction.OPEN_URL]. [label] is a free-form display
 * string reused for every action type (the app's name, "Open the app drawer", or the URL's host).
 *
 * Every field but [url] is required, and the strokes and their counts have to line up: a file
 * that breaks either fails to decode, and the store keeps it aside as unreadable (see
 * JsonListFile) rather than handing the home screen a gesture it would crash on.
 */
@Serializable
data class GestureMapping(
    val id: String,
    val packageName: String,
    val componentName: String, // ComponentName.flattenToString()
    val label: String,
    val templates: List<List<SPoint>>,
    /** Per-template point-count of each sub-stroke (mirrors [templates] by index), so previews can
     *  render the pen-lift gaps instead of a connecting line, and the recognizer can compare
     *  pen-lift counts. */
    val subStrokeLengths: List<List<Int>>,
    val action: GestureAction,
    val url: String? = null
) {
    init {
        require(subStrokeLengths.size == templates.size && subStrokeLengths.all { it.isNotEmpty() }) {
            "every template needs its sub-stroke lengths"
        }
    }

    /** True if any training attempt was drawn as multiple pen-lift sub-strokes. Gates whether the
     *  home screen waits out the multi-stroke gap-timeout before recognizing (see MainActivity).
     *  Derived rather than stored, so it can't disagree with the strokes it describes. */
    val isMultiStroke: Boolean get() = subStrokeLengths.any { it.size > 1 }
}

/**
 * Every trained stroke in these mappings, as recognizer templates tagged with the id of the
 * gesture they belong to.
 *
 * One implementation because every screen that recognizes has to build these identically: the home
 * screen matching for real, and the sensitivity screen's live test showing what the home screen
 * *would* match. A template set built differently in the two would make the test lie.
 */
fun List<GestureMapping>.toTemplates(): List<GestureTemplate> = flatMap { mapping ->
    mapping.templates.mapIndexed { index, stroke ->
        GestureTemplate(
            mapping.id,
            stroke.toPt(),
            mapping.subStrokeLengths[index].size
        )
    }
}

/** Whether any of these was drawn with a pen lift, which is what makes waiting out the
 *  multi-stroke gap worth the latency it costs every other gesture. */
fun List<GestureMapping>.anyMultiStroke(): Boolean = any { it.isMultiStroke }

/** The most pen-lift sub-strokes any trained sample has — once a session reaches it there is
 *  nothing further to wait for. 0 with no gestures. */
fun List<GestureMapping>.maxExpectedSubStrokes(): Int =
    maxOfOrNull { m -> m.subStrokeLengths.maxOf { it.size } } ?: 0

/** The recognizer works in doubles; the canvas and the stored gestures both work in floats. */
@JvmName("pointFToPt")
fun List<PointF>.toPt(): List<Pt> = map { Pt(it.x.toDouble(), it.y.toDouble()) }

@JvmName("sPointToPt")
fun List<SPoint>.toPt(): List<Pt> = map { Pt(it.x.toDouble(), it.y.toDouble()) }
