package pl.mateuszkaflowski.ambiled.ambient

import java.nio.ByteBuffer
import kotlin.math.abs

/** ARGB colors picked for the two sticks. */
data class StickColors(val left: Int, val right: Int)

object FrameAnalyzer {

    // Weight of a fully grey pixel relative to the 0..255 chroma bonus of a vivid one.
    private const val BASE_WEIGHT = 32

    // Max channel value still counted as bar black; scaling blurs the bar edge slightly.
    private const val BAR_THRESHOLD = 16

    // Real bars are symmetric; a lone dark edge is part of the scene, not a bar.
    private const val BAR_SYMMETRY_TOLERANCE = 2

    /**
     * Averages the left and right half of an RGBA_8888 frame. Pixels are weighted by chroma,
     * so vivid areas win over grey UI and dark areas, which keeps the LEDs from going muddy.
     *
     * With [ignoreBars], symmetric all-black borders (pillarbox of 4:3 games, letterbox of
     * films) are left out, so they don't dim the result.
     */
    fun analyze(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        ignoreBars: Boolean = false,
    ): StickColors {
        val frame = Frame(buffer, rowStride, pixelStride)
        val area = if (ignoreBars) contentArea(frame, width, height) else Area(0, 0, width, height)
        val half = (area.left + area.right) / 2
        val left = LongArray(4) // r, g, b, weight
        val right = LongArray(4)
        for (y in area.top until area.bottom) {
            for (x in area.left until area.right) {
                val r = frame.red(x, y)
                val g = frame.green(x, y)
                val b = frame.blue(x, y)
                val weight = BASE_WEIGHT + maxOf(r, g, b) - minOf(r, g, b)
                val acc = if (x < half) left else right
                acc[0] += (r * weight).toLong()
                acc[1] += (g * weight).toLong()
                acc[2] += (b * weight).toLong()
                acc[3] += weight.toLong()
            }
        }
        return StickColors(average(left), average(right))
    }

    private fun contentArea(frame: Frame, width: Int, height: Int): Area {
        fun columnBlack(x: Int, top: Int, bottom: Int) = (top until bottom).all { frame.isBlack(x, it) }
        fun rowBlack(y: Int, left: Int, right: Int) = (left until right).all { frame.isBlack(it, y) }

        var leftBar = 0
        while (leftBar < width / 2 && columnBlack(leftBar, 0, height)) leftBar++
        // Entirely black frame: nothing to crop, the result is black either way.
        if (leftBar == width / 2) return Area(0, 0, width, height)
        var rightBar = 0
        while (columnBlack(width - 1 - rightBar, 0, height)) rightBar++
        val side = symmetricBar(leftBar, rightBar)

        var topBar = 0
        while (rowBlack(topBar, side, width - side)) topBar++
        var bottomBar = 0
        while (rowBlack(height - 1 - bottomBar, side, width - side)) bottomBar++
        val vertical = symmetricBar(topBar, bottomBar)

        return Area(side, vertical, width - side, height - vertical)
    }

    private fun symmetricBar(a: Int, b: Int) = if (abs(a - b) <= BAR_SYMMETRY_TOLERANCE) minOf(a, b) else 0

    private fun average(acc: LongArray): Int {
        val weight = acc[3].coerceAtLeast(1)
        val r = (acc[0] / weight).toInt()
        val g = (acc[1] / weight).toInt()
        val b = (acc[2] / weight).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Right and bottom are exclusive. */
    private data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private class Frame(private val buffer: ByteBuffer, private val rowStride: Int, private val pixelStride: Int) {
        private fun channel(x: Int, y: Int, offset: Int) =
            buffer.get(y * rowStride + x * pixelStride + offset).toInt() and 0xFF

        fun red(x: Int, y: Int) = channel(x, y, 0)
        fun green(x: Int, y: Int) = channel(x, y, 1)
        fun blue(x: Int, y: Int) = channel(x, y, 2)

        fun isBlack(x: Int, y: Int) =
            red(x, y) <= BAR_THRESHOLD && green(x, y) <= BAR_THRESHOLD && blue(x, y) <= BAR_THRESHOLD
    }
}
