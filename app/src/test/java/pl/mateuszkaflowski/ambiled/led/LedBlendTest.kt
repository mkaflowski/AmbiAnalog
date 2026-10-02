package pl.mateuszkaflowski.ambiled.led

import org.junit.Assert.assertEquals
import org.junit.Test

class LedBlendTest {

    @Test
    fun `color endpoints and midpoint`() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        assertEquals(red, LedBlend.color(red, blue, 0f))
        assertEquals(blue, LedBlend.color(red, blue, 1f))
        assertEquals(0xFF800080.toInt(), LedBlend.color(red, blue, 0.5f))
    }

    @Test
    fun `brightness is interpolated too`() {
        val from = LedOutput(0xFF000000.toInt(), 0xFF000000.toInt(), 0.1f)
        val to = LedOutput(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0.5f)
        assertEquals(0.3f, LedBlend.output(from, to, 0.5f).brightness, 1e-6f)
    }

    @Test
    fun `easing starts and ends flat and is clamped`() {
        assertEquals(0f, LedBlend.ease(-1f), 0f)
        assertEquals(0.5f, LedBlend.ease(0.5f), 1e-6f)
        assertEquals(1f, LedBlend.ease(2f), 0f)
        // Slow start: the first tenth of the time covers less than a tenth of the way.
        assertEquals(true, LedBlend.ease(0.1f) < 0.1f)
    }
}
