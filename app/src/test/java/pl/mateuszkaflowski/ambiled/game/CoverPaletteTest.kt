package pl.mateuszkaflowski.ambiled.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPaletteTest {

    private fun hue(color: Int) = CoverPalette.toHsv(color)[0]
    private fun saturation(color: Int) = CoverPalette.toHsv(color)[1]
    private fun value(color: Int) = CoverPalette.toHsv(color)[2]

    @Test
    fun `gold cover with a teal accent gives gold and teal`() {
        // Roughly the Layton "Azran Legacy" cover: mostly gold, some teal, dark brown frame.
        val colors = CoverPalette.pick(
            listOf(
                Swatch(0xFFC6A72E.toInt(), 900),
                Swatch(0xFFA9771A.toInt(), 400),
                Swatch(0xFF2C7A78.toInt(), 150),
                Swatch(0xFF3A2A1E.toInt(), 300),
            )
        )!!
        assertEquals(48f, hue(colors.left), 3f)
        assertEquals(179f, hue(colors.right), 3f)
        assertEquals(1f, value(colors.left), 0.01f)
        assertEquals(1f, value(colors.right), 0.01f)
    }

    @Test
    fun `monochrome cover uses the same color on both sticks`() {
        val colors = CoverPalette.pick(
            listOf(Swatch(0xFF1E5AC8.toInt(), 500), Swatch(0xFF2A66D0.toInt(), 300))
        )!!
        assertEquals(colors.left, colors.right)
    }

    @Test
    fun `grey cover stays unsaturated`() {
        val colors = CoverPalette.pick(listOf(Swatch(0xFF808080.toInt(), 1000)))!!
        assertTrue(saturation(colors.left) < 0.05f)
    }

    @Test
    fun `no swatches gives null`() {
        assertNull(CoverPalette.pick(emptyList()))
    }

    @Test
    fun `hsv round trip`() {
        listOf(0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFC6A72E, 0xFF2C7A78).map { it.toInt() }.forEach {
            val hsv = CoverPalette.toHsv(it)
            assertEquals(it, CoverPalette.fromHsv(hsv[0], hsv[1], hsv[2]))
        }
    }
}
