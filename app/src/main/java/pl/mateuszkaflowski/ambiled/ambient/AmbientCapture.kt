package pl.mateuszkaflowski.ambiled.ambient

import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import pl.mateuszkaflowski.ambiled.led.LedRepository
import pl.mateuszkaflowski.ambiled.led.LedState
import pl.mateuszkaflowski.ambiled.led.Stick
import pl.mateuszkaflowski.ambiled.led.ThorLeds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.EnumMap

/** Live ambient light status for the UI. */
object AmbientStatus {
    internal val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    internal val _colors = MutableStateFlow<StickColors?>(null)
    val colors: StateFlow<StickColors?> = _colors.asStateFlow()
}

/**
 * Mirrors the default display (the Thor's top screen) into a tiny buffer and drives the
 * joystick LEDs from it. Frames only arrive when the screen content changes; a fixed-rate
 * tick fades the LEDs towards the latest frame's colors.
 *
 * The mirror would otherwise be composed at the panel's 120 Hz, costing SurfaceFlinger GPU
 * work on every frame, so the surface is detached after each captured frame and reattached
 * once the next sample is due (see [LedState.ambientFps]). Toggling has its own cost, so
 * it only pays off at low rates; at 0 the surface stays attached.
 */
class AmbientCapture(
    private val projection: MediaProjection,
    /** Called on the main thread when the projection ends, e.g. revoked by the system. */
    private val onStopped: () -> Unit,
) {
    private val thread = HandlerThread("ambient-light").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val imageReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
    private var virtualDisplay: VirtualDisplay? = null
    private var released = false

    // Touched only on the capture thread.
    private var target: StickColors? = null
    private val currentLeft = FloatArray(3)
    private val currentRight = FloatArray(3)
    private val lastValues = EnumMap<Stick, String>(Stick::class.java)
    private var lastFullWriteAt = 0L
    private var lastPublishedAt = 0L

    @Volatile
    var screenOn = true

    @Volatile
    private var forceFullWrite = true

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            release()
            onStopped()
        }
    }

    private val resumeCapture = Runnable { virtualDisplay?.surface = imageReader.surface }

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        AmbientStatus._running.value = true
        projection.registerCallback(projectionCallback, mainHandler)
        imageReader.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                target = boostSaturation(
                    FrameAnalyzer.analyze(
                        plane.buffer, WIDTH, HEIGHT, plane.rowStride, plane.pixelStride,
                        ignoreBars = LedRepository.state.value.ambientIgnoreBars,
                    )
                )
            }
            val fps = LedRepository.state.value.ambientFps
            if (fps > 0) {
                virtualDisplay?.surface = null
                handler.postDelayed(resumeCapture, 1000L / fps)
            }
        }, handler)
        virtualDisplay = projection.createVirtualDisplay(
            "ThorAmbientLight", WIDTH, HEIGHT, DENSITY_DPI,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, handler,
        )
        handler.post(tick)
    }

    /** Ends the projection; [onStopped] follows through the projection callback. */
    fun stop() {
        projection.stop()
    }

    /** Rewrites enable + colors on the next tick, after something else touched the LEDs. */
    fun refresh() {
        forceFullWrite = true
    }

    private fun release() {
        if (released) return
        released = true
        AmbientStatus._running.value = false
        AmbientStatus._colors.value = null
        projection.unregisterCallback(projectionCallback)
        handler.removeCallbacksAndMessages(null)
        // Close on the capture thread so it can't race a frame being analyzed.
        handler.post {
            virtualDisplay?.release()
            imageReader.close()
            thread.quitSafely()
        }
    }

    private fun step() {
        val target = target ?: return
        val settings = LedRepository.state.value
        val alpha = 1f - settings.ambientSmoothing * MAX_SMOOTHING
        val left = fade(currentLeft, target.left, alpha)
        val right = fade(currentRight, target.right, alpha)
        val now = SystemClock.uptimeMillis()
        if (now - lastPublishedAt >= UI_INTERVAL_MS) {
            AmbientStatus._colors.value = StickColors(left, right)
            lastPublishedAt = now
        }

        // Dimmed from the tile: keep capturing, so the colors are right when lit again.
        if (settings.lightsOff) return
        // OdinSettings switches the LEDs off with the screen; don't fight it.
        if (!screenOn) return
        // Periodically rewrite everything, in case OdinSettings changed the LEDs meanwhile.
        val fullWrite = forceFullWrite || now - lastFullWriteAt >= FULL_WRITE_INTERVAL_MS
        runCatching {
            if (fullWrite) {
                Stick.entries.forEach { ThorLeds.writeEnabled(it, true) }
                forceFullWrite = false
                lastFullWriteAt = now
            }
            writeIfChanged(Stick.LEFT, left, settings.brightness, fullWrite)
            writeIfChanged(Stick.RIGHT, right, settings.brightness, fullWrite)
        }.onFailure { Log.e(TAG, "Failed to write LEDs", it) }
    }

    private fun writeIfChanged(stick: Stick, color: Int, brightness: Float, force: Boolean) {
        val value = ThorLeds.colorValue(color, brightness)
        if (!force && value == lastValues[stick]) return
        ThorLeds.writeColorValue(stick, value)
        lastValues[stick] = value
    }

    /** Moves [current] (r, g, b as floats) towards [target] and returns the resulting color. */
    private fun fade(current: FloatArray, target: Int, alpha: Float): Int {
        current[0] += (Color.red(target) - current[0]) * alpha
        current[1] += (Color.green(target) - current[1]) * alpha
        current[2] += (Color.blue(target) - current[2]) * alpha
        return Color.rgb(current[0].toInt(), current[1].toInt(), current[2].toInt())
    }

    private fun boostSaturation(colors: StickColors): StickColors {
        val boost = 1f + LedRepository.state.value.ambientSaturation * MAX_SATURATION_BOOST
        fun boosted(color: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[1] = (hsv[1] * boost).coerceAtMost(1f)
            return Color.HSVToColor(hsv)
        }
        return StickColors(boosted(colors.left), boosted(colors.right))
    }

    private companion object {
        const val TAG = "AmbientCapture"
        // 16:9 like the top screen; averaging needs very few pixels and the GPU does the scaling.
        const val WIDTH = 64
        const val HEIGHT = 36
        const val DENSITY_DPI = 160
        const val TICK_MS = 33L
        const val UI_INTERVAL_MS = 66L
        const val FULL_WRITE_INTERVAL_MS = 1000L
        // At smoothing = 1 the LEDs move 10 % of the way per tick (~0.3 s time constant).
        const val MAX_SMOOTHING = 0.9f
        const val MAX_SATURATION_BOOST = 2f
    }
}
