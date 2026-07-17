package dev.neffly.gesturelauncher.unistroke

/** A raw 2D point used by the recognizer (double precision for the geometry math). */
data class Pt(val x: Double, val y: Double)

/**
 * A preprocessed template the recognizer compares against. [name] carries the owning
 * gesture-mapping id, so several templates (the 3 training strokes) can share one name.
 * [subStrokeCount] is how many pen-lift sub-strokes this training sample was drawn with
 * (1 for single-stroke gestures and for gestures saved before counts were recorded).
 */
class GestureTemplate(val name: String, rawPoints: List<Pt>, val subStrokeCount: Int = 1) {
    /** Unit-magnitude Protractor vector — see [OneDollarRecognizer.vectorize]. */
    val vector: DoubleArray = OneDollarRecognizer.vectorize(rawPoints)

    /** This training sample's raw (pre-normalization) bounding-box diagonal — see
     *  [OneDollarRecognizer.recognize]'s size-ratio guard. */
    val rawDiagonal: Double = OneDollarRecognizer.boundingBoxDiagonal(rawPoints)

    /** Raw chord/path straightness — see [OneDollarRecognizer.recognize]'s straightness guard. */
    val straightness: Double = OneDollarRecognizer.straightness(rawPoints)
}

/** Best match result. [name] is null when there was nothing to match. */
data class MatchResult(val name: String?, val score: Double)
