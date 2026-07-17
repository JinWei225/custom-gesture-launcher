package dev.neffly.gesturelauncher.unistroke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pure-JVM tests for the Protractor recognizer. The key regression pin: a plain straight line
 * must NOT score anywhere near threshold against a "T" template (it used to score 0.7-0.8 with
 * the classic $1 scale-to-square pipeline).
 */
class OneDollarRecognizerTest {

    // --- synthetic strokes -------------------------------------------------

    /** Dense straight line from (0,0) to (0,400). */
    private fun line(n: Int = 50): List<Pt> =
        (0 until n).map { Pt(0.0, 400.0 * it / (n - 1)) }

    /** Single-stroke "T": bar left->right, retrace to the middle, stem down. */
    private fun tShape(): List<Pt> {
        val pts = ArrayList<Pt>()
        for (i in 0..30) pts.add(Pt(300.0 * i / 30, 0.0))          // bar ->
        for (i in 1..15) pts.add(Pt(300.0 - 150.0 * i / 15, 0.0))  // retrace to middle
        for (i in 1..40) pts.add(Pt(150.0, 400.0 * i / 40))        // stem down
        return pts
    }

    /** Two-sub-stroke "T": bar, then (after a pen lift) the stem. Points are concatenated the
     *  same way GestureCanvasView delivers a multi-stroke session. */
    private fun tShapeTwoStroke(): List<Pt> {
        val pts = ArrayList<Pt>()
        for (i in 0..30) pts.add(Pt(300.0 * i / 30, 0.0))
        for (i in 0..40) pts.add(Pt(150.0, 400.0 * i / 40))
        return pts
    }

    /** Circle of radius 150 centered at (200, 200). */
    private fun circle(n: Int = 60): List<Pt> =
        (0 until n).map {
            val a = 2 * Math.PI * it / (n - 1)
            Pt(200.0 + 150.0 * cos(a), 200.0 + 150.0 * sin(a))
        }

    private fun jitter(points: List<Pt>, amount: Double = 6.0, seed: Int = 42): List<Pt> {
        val rnd = Random(seed)
        return points.map {
            Pt(it.x + rnd.nextDouble(-amount, amount), it.y + rnd.nextDouble(-amount, amount))
        }
    }

    private fun rotate(points: List<Pt>, degrees: Double): List<Pt> {
        val r = Math.toRadians(degrees)
        val cx = points.sumOf { it.x } / points.size
        val cy = points.sumOf { it.y } / points.size
        return points.map {
            val dx = it.x - cx; val dy = it.y - cy
            Pt(dx * cos(r) - dy * sin(r) + cx, dx * sin(r) + dy * cos(r) + cy)
        }
    }

    private fun scale(points: List<Pt>, factor: Double): List<Pt> =
        points.map { Pt(it.x * factor, it.y * factor) }

    private fun tTemplate() = GestureTemplate("t", tShape(), 1)

    // --- the regression pin: line vs T --------------------------------------

    @Test
    fun `straight line scores far below threshold against a T template`() {
        val result = OneDollarRecognizer.recognize(line(), listOf(tTemplate()), 1)
        assertTrue(
            "line vs T should be < 0.6 but was ${result.score}",
            result.score < 0.6
        )
    }

    @Test
    fun `straight line fails even harder against a two-stroke T template`() {
        val template = GestureTemplate("t2", tShapeTwoStroke(), 2)
        val result = OneDollarRecognizer.recognize(line(), listOf(template), 1)
        assertTrue(
            "1-stroke line vs 2-stroke T should be < 0.5 but was ${result.score}",
            result.score < 0.5
        )
    }

    // --- genuine redraws must still pass ------------------------------------

    @Test
    fun `jittered redraw of the same T passes the default threshold`() {
        val result = OneDollarRecognizer.recognize(jitter(tShape()), listOf(tTemplate()), 1)
        assertEquals("t", result.name)
        assertTrue("redrawn T should be >= 0.80 but was ${result.score}", result.score >= 0.80)
    }

    @Test
    fun `redraw rotated within 45 degrees still passes`() {
        val candidate = rotate(jitter(tShape(), seed = 7), 20.0)
        val result = OneDollarRecognizer.recognize(candidate, listOf(tTemplate()), 1)
        assertTrue("20-degree-rotated T should be >= 0.80 but was ${result.score}", result.score >= 0.80)
    }

    @Test
    fun `redraw at a somewhat different size still passes`() {
        val candidate = scale(jitter(tShape(), seed = 3), 0.6)
        val result = OneDollarRecognizer.recognize(candidate, listOf(tTemplate()), 1)
        assertTrue("0.6x-scaled T should be >= 0.80 but was ${result.score}", result.score >= 0.80)
    }

    @Test
    fun `two-stroke T redraw matches its two-stroke template`() {
        val template = GestureTemplate("t2", tShapeTwoStroke(), 2)
        val result = OneDollarRecognizer.recognize(jitter(tShapeTwoStroke()), listOf(template), 2)
        assertTrue("2-stroke T redraw should be >= 0.80 but was ${result.score}", result.score >= 0.80)
    }

    // --- guards --------------------------------------------------------------

    @Test
    fun `tiny accidental stroke is scored down by the size guard`() {
        val tiny = scale(tShape(), 0.05) // 5% of the trained size
        val result = OneDollarRecognizer.recognize(tiny, listOf(tTemplate()), 1)
        assertTrue("tiny T-shaped brush should be < 0.5 but was ${result.score}", result.score < 0.5)
    }

    @Test
    fun `unrelated shapes score low`() {
        val result = OneDollarRecognizer.recognize(circle(), listOf(tTemplate()), 1)
        assertTrue("circle vs T should be < 0.6 but was ${result.score}", result.score < 0.6)
    }

    @Test
    fun `best match is picked across templates`() {
        val templates = listOf(
            tTemplate(),
            GestureTemplate("line", line(), 1)
        )
        val result = OneDollarRecognizer.recognize(jitter(line(), seed = 9), templates, 1)
        assertEquals("line", result.name)
        assertTrue("line vs line template should be >= 0.80 but was ${result.score}", result.score >= 0.80)
    }

    // --- plumbing ------------------------------------------------------------

    @Test
    fun `vectorize produces a fixed-length unit vector`() {
        val vec = OneDollarRecognizer.vectorize(tShape())
        assertEquals(64, vec.size) // 32 points x 2 coords
        val mag = kotlin.math.sqrt(vec.sumOf { it * it })
        assertEquals(1.0, mag, 1e-9)
    }

    @Test
    fun `vectorize handles degenerate input without crashing`() {
        val samePoint = List(10) { Pt(5.0, 5.0) }
        val vec = OneDollarRecognizer.vectorize(samePoint)
        assertEquals(64, vec.size)
        assertTrue(vec.all { it == 0.0 })
    }

    @Test
    fun `recognize with no templates returns null`() {
        assertNull(OneDollarRecognizer.recognize(tShape(), emptyList(), 1).name)
    }

    @Test
    fun `isStrokeUsable rejects short strokes`() {
        assertTrue(!OneDollarRecognizer.isStrokeUsable(listOf(Pt(0.0, 0.0), Pt(3.0, 3.0))))
        assertTrue(OneDollarRecognizer.isStrokeUsable(line()))
    }

    @Test
    fun `straightness distinguishes line from T`() {
        assertTrue(OneDollarRecognizer.straightness(line()) > 0.95)
        assertTrue(OneDollarRecognizer.straightness(tShape()) < 0.7)
    }
}
