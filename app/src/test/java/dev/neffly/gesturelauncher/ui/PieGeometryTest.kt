package dev.neffly.gesturelauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PieGeometryTest {

    @Test
    fun `inside the dead zone nothing is picked`() {
        assertEquals(-1, PieGeometry.indexAt(5f, 5f, count = 6, deadZonePx = 20f))
    }

    @Test
    fun `the first item is straight up and the rest go clockwise`() {
        // Screen coordinates: y grows downwards, so "up" is negative y.
        assertEquals(0, PieGeometry.indexAt(0f, -100f, count = 4, deadZonePx = 20f))
        assertEquals(1, PieGeometry.indexAt(100f, 0f, count = 4, deadZonePx = 20f))
        assertEquals(2, PieGeometry.indexAt(0f, 100f, count = 4, deadZonePx = 20f))
        assertEquals(3, PieGeometry.indexAt(-100f, 0f, count = 4, deadZonePx = 20f))
    }

    @Test
    fun `just left of the top still picks the first item`() {
        assertEquals(0, PieGeometry.indexAt(-10f, -100f, count = 8, deadZonePx = 20f))
    }

    @Test
    fun `a single item is picked from any direction`() {
        assertEquals(0, PieGeometry.indexAt(0f, 100f, count = 1, deadZonePx = 20f))
        assertEquals(0, PieGeometry.indexAt(-100f, 3f, count = 1, deadZonePx = 20f))
    }

    @Test
    fun `item angles and picking agree`() {
        val count = 7
        for (i in 0 until count) {
            val angle = PieGeometry.angleOf(i, count)
            val dx = (100 * kotlin.math.cos(angle)).toFloat()
            val dy = (100 * kotlin.math.sin(angle)).toFloat()
            assertEquals(i, PieGeometry.indexAt(dx, dy, count, deadZonePx = 20f))
        }
    }

    @Test
    fun `the ring grows so icons never overlap`() {
        assertEquals(80f, PieGeometry.radius(3, itemPx = 50f, gapPx = 10f, minPx = 80f))
        val r = PieGeometry.radius(12, itemPx = 50f, gapPx = 10f, minPx = 80f)
        assertTrue(2 * Math.PI * r >= 12 * 60 - 0.01)
    }

    @Test
    fun `with room to spare the pie sits on the finger at full size`() {
        val fit = PieGeometry.fit(500f, 800f, width = 1000f, height = 1600f, reach = 200f, minScale = 0.5f)
        assertEquals(PieGeometry.Fit(500f, 800f, 1f), fit)
    }

    @Test
    fun `near an edge the pie shrinks but stays on the finger`() {
        val fit = PieGeometry.fit(150f, 800f, width = 1000f, height = 1600f, reach = 200f, minScale = 0.5f)
        assertEquals(150f, fit.centerX)
        assertEquals(800f, fit.centerY)
        assertEquals(0.75f, fit.scale)
    }

    @Test
    fun `past its smallest size the pie moves just enough to stay in view`() {
        val fit = PieGeometry.fit(40f, 800f, width = 1000f, height = 1600f, reach = 200f, minScale = 0.5f)
        assertEquals(0.5f, fit.scale)
        assertEquals(100f, fit.centerX)
        assertEquals(800f, fit.centerY)
    }
}
