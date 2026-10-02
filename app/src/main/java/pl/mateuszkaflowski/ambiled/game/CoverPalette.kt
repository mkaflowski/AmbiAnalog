package pl.mateuszkaflowski.ambiled.game

import pl.mateuszkaflowski.ambiled.ambient.StickColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One color of an image palette and how many pixels it covers. */
data class Swatch(val rgb: Int, val population: Int)

/** Turns an image palette into a pair of LED colors. */
object CoverPalette {

    private const val MIN_COLORFUL_SATURATION = 0.25f
    private const val MIN_COLORFUL_VALUE = 0.25f
    // Second stick gets a clearly different hue when the cover has one.
    private const val MIN_ACCENT_HUE_DISTANCE = 45f
    private const val LED_SATURATION_BOOST = 1.25f

    /**
     * Left stick: the most prominent colorful swatch. Right stick: the most prominent one with
     * a clearly different hue, or the same color if the cover is monochrome. Both are pushed to
     * full value, since LED brightness is a separate setting.
     */
    fun pick(swatches: List<Swatch>): StickColors? {
        if (swatches.isEmpty()) return null
        val hsv = swatches.map { it to toHsv(it.rgb) }
        val colorful = hsv.filter { (_, c) -> c[1] >= MIN_COLORFUL_SATURATION && c[2] >= MIN_COLORFUL_VALUE }
        fun score(entry: Pair<Swatch, FloatArray>) =
            entry.first.population * (0.3f + entry.second[1]) * (0.5f + entry.second[2])

        val primary = colorful.maxByOrNull(::score) ?: hsv.maxBy { it.first.population }
        val accent = colorful
            .filter { hueDistance(it.second[0], primary.second[0]) >= MIN_ACCENT_HUE_DISTANCE }
            .maxByOrNull(::score) ?: primary
        return StickColors(forLed(primary.second), forLed(accent.second))
    }

    private fun forLed(hsv: FloatArray): Int =
        fromHsv(hsv[0], min(1f, hsv[1] * LED_SATURATION_BOOST), 1f)

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    // Plain-Kotlin HSV conversions so the selection stays unit-testable on the JVM.
    internal fun toHsv(rgb: Int): FloatArray {
        val r = (rgb shr 16 and 0xFF) / 255f
        val g = (rgb shr 8 and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val hue = when {
            delta == 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }.let { if (it < 0) it + 360f else it }
        val saturation = if (maxC == 0f) 0f else delta / maxC
        return floatArrayOf(hue, saturation, maxC)
    }

    internal fun fromHsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2f - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(value: Float) = ((value + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
