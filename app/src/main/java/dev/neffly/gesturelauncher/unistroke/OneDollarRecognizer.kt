package dev.neffly.gesturelauncher.unistroke

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Protractor variant of the $1 Unistroke Recognizer (Li, 2010; Wobbrock et al., 2007).
 *
 * A drawn stroke is resampled to a fixed number of points, rotated to a canonical "indicative
 * angle", translated to the origin and flattened into a vector normalized to unit magnitude.
 * Candidates are compared to each template by cosine similarity, with the optimal aligning
 * rotation computed in closed form (one O(n) pass per template — no iterative angle search).
 *
 * Unlike classic $1's scale-to-square step, the uniform vector normalization here **preserves
 * aspect ratio**, so a near-1D stroke (a straight line) is not stretched into a full square and
 * can no longer masquerade as a genuinely 2D shape like a "T". Two additional guards sharpen
 * that further — see [recognize].
 */
object OneDollarRecognizer {

    private const val NUM_POINTS = 32
    private val ANGLE_RANGE = Math.toRadians(45.0)

    /** Angular distance (radians) at or beyond which the score bottoms out at 0. Chosen so the
     *  0.60..0.95 sensitivity slider keeps meaningful spread: a sloppy-but-real redraw
     *  (similarity ~0.97) lands around 0.85, an unrelated shape well below 0.6. */
    private val SCORE_ANGLE_CEILING = Math.PI / 2.0

    /** Minimum raw points / path length for a stroke to be considered a real gesture. This floor
     *  is what separates "barely brushed the screen" from getting scored at all. */
    const val MIN_POINTS = 8
    const val MIN_PATH_LENGTH = 150.0

    /** Below this fraction of the matched template's own raw (un-normalized) size, [recognize]
     *  scales the score down instead of trusting the shape match alone. */
    private const val SIZE_RATIO_FLOOR = 0.4

    /** Allowed difference in raw straightness (chord/path-length ratio) between candidate and
     *  template before the score gets scaled down — see [recognize]. */
    private const val STRAIGHTNESS_TOLERANCE = 0.2

    /** Score multiplier when the candidate's pen-lift sub-stroke count differs from the
     *  template's (e.g. a single-stroke line against a two-stroke "T"). */
    private const val SUB_STROKE_MISMATCH_FACTOR = 0.5

    fun isStrokeUsable(points: List<Pt>): Boolean =
        points.size >= MIN_POINTS && pathLength(points) >= MIN_PATH_LENGTH

    /** Full normalization pipeline: resample, rotate to indicative angle, translate to origin,
     *  flatten to (x0, y0, x1, y1, …) and normalize to unit magnitude. Uniform scaling — the
     *  stroke's aspect ratio survives. Also used to build [GestureTemplate]s. */
    fun vectorize(points: List<Pt>): DoubleArray {
        var p = resample(points, NUM_POINTS)
        p = rotateBy(p, -indicativeAngle(p))
        p = translateToOrigin(p)
        val vec = DoubleArray(p.size * 2)
        var magSq = 0.0
        for (i in p.indices) {
            vec[2 * i] = p[i].x
            vec[2 * i + 1] = p[i].y
            magSq += p[i].x * p[i].x + p[i].y * p[i].y
        }
        val mag = sqrt(magSq)
        if (mag > 0.0) for (i in vec.indices) vec[i] /= mag
        return vec
    }

    /**
     * Match a freshly drawn stroke against a set of preprocessed templates.
     *
     * The raw cosine-similarity score is adjusted per template by three guards before picking
     * the best match, all aimed at "wrong stroke happens to score high":
     *  1. **Size**: a candidate drastically smaller than the template's raw on-screen size (an
     *     accidental brush tracing a similar shape) is scaled down — shape matching alone is
     *     scale-invariant and can't see this.
     *  2. **Straightness**: chord-length / path-length on the raw points. A straight line is
     *     ~1.0, a "T" stroke far less; scale-preserving vectorization already separates them,
     *     and this catches the residual overlap (e.g. line vs. shallow "L").
     *  3. **Sub-strokes**: a pen-lift count mismatch with the template (1-stroke line vs. a
     *     2-stroke "T") halves the score.
     *
     * [candidateSubStrokes] is the number of pen-lift sub-strokes the candidate was drawn with
     * (1 when unknown — guards degrade gracefully for templates saved before counts existed).
     */
    fun recognize(
        points: List<Pt>,
        templates: List<GestureTemplate>,
        candidateSubStrokes: Int = 1
    ): MatchResult {
        if (points.size < 2 || templates.isEmpty()) return MatchResult(null, 0.0)
        val candidate = vectorize(points)
        val candidateDiagonal = boundingBoxDiagonal(points)
        val candidateStraightness = straightness(points)

        var bestScore = 0.0
        var bestName: String? = null
        for (t in templates) {
            val sim = optimalCosineSimilarity(candidate, t.vector)
            var score = 1.0 - acos(sim.coerceIn(0.0, 1.0)) / SCORE_ANGLE_CEILING
            score = score.coerceIn(0.0, 1.0)

            if (t.rawDiagonal > 0.0) {
                val sizeRatio = (candidateDiagonal / t.rawDiagonal).coerceAtMost(1.0)
                if (sizeRatio < SIZE_RATIO_FLOOR) {
                    score *= (sizeRatio / SIZE_RATIO_FLOOR).coerceIn(0.0, 1.0)
                }
            }

            val straightnessDiff = abs(candidateStraightness - t.straightness)
            if (straightnessDiff > STRAIGHTNESS_TOLERANCE) {
                score *= STRAIGHTNESS_TOLERANCE / straightnessDiff
            }

            if (candidateSubStrokes != t.subStrokeCount) {
                score *= SUB_STROKE_MISMATCH_FACTOR
            }

            if (score > bestScore) {
                bestScore = score
                bestName = t.name
            }
        }
        return MatchResult(bestName, bestScore)
    }

    // --- geometry helpers -------------------------------------------------

    /** Cosine similarity between two unit vectors at the optimal aligning rotation, computed in
     *  closed form (Protractor). The rotation is clamped to ±45° so wildly different orientations
     *  can't be spun into a match. */
    private fun optimalCosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
        val n = minOf(a.size, b.size) / 2
        if (n == 0) return 0.0
        var dot = 0.0
        var cross = 0.0
        for (i in 0 until n) {
            val ax = a[2 * i]; val ay = a[2 * i + 1]
            val bx = b[2 * i]; val by = b[2 * i + 1]
            dot += ax * bx + ay * by
            cross += ax * by - ay * bx
        }
        val angle = atan2(cross, dot).coerceIn(-ANGLE_RANGE, ANGLE_RANGE)
        return dot * cos(angle) + cross * sin(angle)
    }

    private fun pathLength(points: List<Pt>): Double {
        var d = 0.0
        for (i in 1 until points.size) d += distance(points[i - 1], points[i])
        return d
    }

    /** Diagonal of a stroke's bounding box in its own raw (un-normalized) coordinates — how
     *  physically large it was on screen. Used by the size guard in [recognize]. */
    fun boundingBoxDiagonal(points: List<Pt>): Double {
        if (points.isEmpty()) return 0.0
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return hypot(maxX - minX, maxY - minY)
    }

    /** Chord length / path length on raw points: ~1.0 for a straight line, near 0 for a closed
     *  shape. Cheap 1D summary of "how line-like" a stroke is, used by the straightness guard. */
    fun straightness(points: List<Pt>): Double {
        if (points.size < 2) return 1.0
        val len = pathLength(points)
        if (len <= 0.0) return 1.0
        return (distance(points.first(), points.last()) / len).coerceIn(0.0, 1.0)
    }

    /** Even resampling to [n] points along the path. Two-pointer walk — O(input + n), no list
     *  insertion (the classic pseudocode's insert-into-source approach is accidentally O(n²)). */
    private fun resample(points: List<Pt>, n: Int): List<Pt> {
        if (points.size < 2) return List(n) { points.firstOrNull() ?: Pt(0.0, 0.0) }
        val interval = pathLength(points) / (n - 1)
        if (interval <= 0.0) return List(n) { points[0] }
        val out = ArrayList<Pt>(n)
        out.add(points[0])
        var accumulated = 0.0
        var prev = points[0]
        var i = 1
        while (i < points.size && out.size < n) {
            val curr = points[i]
            val d = distance(prev, curr)
            if (d > 0.0 && accumulated + d >= interval) {
                val ratio = (interval - accumulated) / d
                val q = Pt(prev.x + ratio * (curr.x - prev.x), prev.y + ratio * (curr.y - prev.y))
                out.add(q)
                prev = q // continue from the interpolated point without consuming curr
                accumulated = 0.0
            } else {
                accumulated += d
                prev = curr
                i++
            }
        }
        // Rounding can leave us one short; pad with the last point.
        while (out.size < n) out.add(points.last())
        return out
    }

    private fun indicativeAngle(points: List<Pt>): Double {
        val c = centroid(points)
        return atan2(c.y - points[0].y, c.x - points[0].x)
    }

    private fun rotateBy(points: List<Pt>, radians: Double): List<Pt> {
        val c = centroid(points)
        val cos = cos(radians)
        val sin = sin(radians)
        return points.map { p ->
            val dx = p.x - c.x
            val dy = p.y - c.y
            Pt(dx * cos - dy * sin + c.x, dx * sin + dy * cos + c.y)
        }
    }

    private fun translateToOrigin(points: List<Pt>): List<Pt> {
        val c = centroid(points)
        return points.map { Pt(it.x - c.x, it.y - c.y) }
    }

    private fun centroid(points: List<Pt>): Pt {
        var sx = 0.0; var sy = 0.0
        for (p in points) { sx += p.x; sy += p.y }
        return Pt(sx / points.size, sy / points.size)
    }

    private fun distance(a: Pt, b: Pt): Double = hypot(a.x - b.x, a.y - b.y)
}
