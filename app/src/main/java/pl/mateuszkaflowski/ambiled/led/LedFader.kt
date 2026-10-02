package pl.mateuszkaflowski.ambiled.led

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.EnumMap

/** What the sticks show: colors plus the brightness they're scaled by. */
data class LedOutput(val left: Int, val right: Int, val brightness: Float)

/** Pure color math for [LedFader]. */
object LedBlend {

    /** Per-channel interpolation of two ARGB colors; [t] in 0..1. */
    fun color(from: Int, to: Int, t: Float): Int {
        fun channel(shift: Int): Int {
            val a = from shr shift and 0xFF
            val b = to shr shift and 0xFF
            return (a + (b - a) * t + 0.5f).toInt().coerceIn(0, 255) shl shift
        }
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }

    fun output(from: LedOutput, to: LedOutput, t: Float) = LedOutput(
        color(from.left, to.left, t),
        color(from.right, to.right, t),
        from.brightness + (to.brightness - from.brightness) * t,
    )

    /** Smoothstep: starts and ends slowly, so a fade has no visible kink. */
    fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}

/**
 * Writes static colors to the LEDs, either at once or as a short fade (used when game mode
 * switches games). All writes run on one background thread, since each is an I2C transfer of
 * ~3.5 ms; a new request cancels a fade still in progress and continues from where it got to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedFader(private val scope: CoroutineScope) {

    private val writer = Dispatchers.IO.limitedParallelism(1)
    private var job: Job? = null

    // Touched only on [writer].
    private var shown: LedOutput? = null
    private val lastValues = EnumMap<Stick, String>(Stick::class.java)

    fun set(target: LedOutput) = start { write(target, enable = true) }

    fun fadeTo(target: LedOutput, durationMs: Long = FADE_MS) = start {
        val from = shown
        if (from == null || from == target) {
            write(target, enable = true)
            return@start
        }
        Stick.entries.forEach { ThorLeds.writeEnabled(it, true) }
        val startedAt = SystemClock.uptimeMillis()
        while (isActive) {
            val t = (SystemClock.uptimeMillis() - startedAt).toFloat() / durationMs
            write(LedBlend.output(from, target, LedBlend.ease(t)), enable = false)
            if (t >= 1f) break
            delay(FRAME_MS)
        }
    }

    /**
     * Stops a fade and forgets what's shown, when something else takes over the LEDs (ambient
     * light, switching them off); the next [set] or [fadeTo] then starts from a full write.
     */
    fun release() = start {
        shown = null
        lastValues.clear()
    }

    private fun start(block: suspend CoroutineScope.() -> Unit) {
        job?.cancel()
        job = scope.launch(writer, block = block)
    }

    private fun write(output: LedOutput, enable: Boolean) {
        runCatching {
            if (enable) {
                Stick.entries.forEach { ThorLeds.writeEnabled(it, true) }
                lastValues.clear() // Full write, in case something else changed the LEDs.
            }
            writeIfChanged(Stick.LEFT, output.left, output.brightness)
            writeIfChanged(Stick.RIGHT, output.right, output.brightness)
            shown = output
        }.onFailure { Log.e(TAG, "Failed to write LEDs", it) }
    }

    private fun writeIfChanged(stick: Stick, color: Int, brightness: Float) {
        val value = ThorLeds.colorValue(color, brightness)
        if (value == lastValues[stick]) return
        ThorLeds.writeColorValue(stick, value)
        lastValues[stick] = value
    }

    private companion object {
        const val TAG = "LedFader"
        const val FADE_MS = 1000L
        const val FRAME_MS = 33L
    }
}
