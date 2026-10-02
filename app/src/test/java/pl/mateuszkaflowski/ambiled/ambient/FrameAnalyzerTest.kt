package pl.mateuszkaflowski.ambiled.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class FrameAnalyzerTest {

    /** Builds an RGBA_8888 frame; [pixel] returns 0xRRGGBB for (x, y). */
    private fun frame(width: Int, height: Int, rowPadding: Int = 0, pixel: (Int, Int) -> Int): Pair<ByteBuffer, Int> {
        val rowStride = width * 4 + rowPadding
        val buffer = ByteBuffer.allocate(rowStride * height)
        for (y in 0 until height) for (x in 0 until width) {
            val rgb = pixel(x, y)
            val i = y * rowStride + x * 4
            buffer.put(i, (rgb shr 16).toByte())
            buffer.put(i + 1, (rgb shr 8).toByte())
            buffer.put(i + 2, rgb.toByte())
            buffer.put(i + 3, 0xFF.toByte())
        }
        return buffer to rowStride
    }

    @Test
    fun `left and right halves are analyzed separately`() {
        val (buffer, stride) = frame(4, 2) { x, _ -> if (x < 2) 0xFF0000 else 0x0000FF }
        val colors = FrameAnalyzer.analyze(buffer, 4, 2, stride, 4)
        assertEquals(0xFFFF0000.toInt(), colors.left)
        assertEquals(0xFF0000FF.toInt(), colors.right)
    }

    @Test
    fun `row padding is skipped`() {
        val (buffer, stride) = frame(2, 2, rowPadding = 8) { _, _ -> 0x00FF00 }
        val colors = FrameAnalyzer.analyze(buffer, 2, 2, stride, 4)
        assertEquals(0xFF00FF00.toInt(), colors.left)
        assertEquals(0xFF00FF00.toInt(), colors.right)
    }

    @Test
    fun `vivid pixels outweigh grey ones`() {
        // Left half: one red pixel and one mid-grey pixel.
        val (buffer, stride) = frame(4, 1) { x, _ -> if (x == 0) 0xFF0000 else 0x808080 }
        val left = FrameAnalyzer.analyze(buffer, 4, 1, stride, 4).left
        val red = (left shr 16) and 0xFF
        val green = (left shr 8) and 0xFF
        // A plain average would give (191, 64, 64); weighting pulls it clearly towards red.
        assertTrue("red=$red green=$green", red > 220 && green < 40)
    }

    @Test
    fun `pillarbox bars dim the colors unless ignored`() {
        // 16 wide: 4 black columns each side, red then blue content in between.
        val (buffer, stride) = frame(16, 4) { x, _ ->
            when (x) {
                in 4..7 -> 0xFF0000
                in 8..11 -> 0x0000FF
                else -> 0x000000
            }
        }
        val counted = FrameAnalyzer.analyze(buffer, 16, 4, stride, 4)
        assertTrue("counted=${Integer.toHexString(counted.left)}", (counted.left shr 16 and 0xFF) < 230)

        val ignored = FrameAnalyzer.analyze(buffer, 16, 4, stride, 4, ignoreBars = true)
        assertEquals(0xFFFF0000.toInt(), ignored.left)
        assertEquals(0xFF0000FF.toInt(), ignored.right)
    }

    @Test
    fun `letterbox bars are ignored`() {
        // 8x8: two black rows top and bottom, green in between.
        val (buffer, stride) = frame(8, 8) { _, y -> if (y in 2..5) 0x00FF00 else 0x000000 }
        val colors = FrameAnalyzer.analyze(buffer, 8, 8, stride, 4, ignoreBars = true)
        assertEquals(0xFF00FF00.toInt(), colors.left)
        assertEquals(0xFF00FF00.toInt(), colors.right)
    }

    @Test
    fun `dark area on one side only is not treated as a bar`() {
        // Left 6 columns black (dark scene), no black on the right: nothing may be cropped.
        val (buffer, stride) = frame(16, 4) { x, _ -> if (x < 6) 0x000000 else 0x00FF00 }
        val ignored = FrameAnalyzer.analyze(buffer, 16, 4, stride, 4, ignoreBars = true)
        val counted = FrameAnalyzer.analyze(buffer, 16, 4, stride, 4)
        assertEquals(counted, ignored)
    }

    @Test
    fun `black frame stays black`() {
        val (buffer, stride) = frame(8, 4) { _, _ -> 0x000000 }
        val colors = FrameAnalyzer.analyze(buffer, 8, 4, stride, 4, ignoreBars = true)
        assertEquals(0xFF000000.toInt(), colors.left)
        assertEquals(0xFF000000.toInt(), colors.right)
    }
}
