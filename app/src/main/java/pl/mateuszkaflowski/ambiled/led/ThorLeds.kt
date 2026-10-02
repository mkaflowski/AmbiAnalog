package pl.mateuszkaflowski.ambiled.led

import android.content.Context
import android.graphics.Color
import android.provider.Settings
import java.io.File

enum class Stick(val sysfsDir: String) {
    LEFT("/sys/class/sn3112l/led"),
    RIGHT("/sys/class/sn3112r/led"),
}

/**
 * Joystick LED control on the AYN Thor, reverse-engineered from com.odin.settings.
 *
 * Hardware: SN3112 drivers, `enable` takes "1"/"0", `brightness` takes "1-R:G:B"
 * (RGB already multiplied by brightness). Writable by regular apps, fast enough per frame.
 *
 * OdinSettings keeps its own state in Settings.System keys (readable, but not writable
 * by us) and re-applies it on screen on, boot and whenever those keys change.
 */
object ThorLeds {

    private const val RING_INDEX = 1

    const val KEY_COLORS = "joystick_led_light_picker_color"
    const val KEY_ENABLED = "joystick_light_enabled"
    const val KEY_BRIGHTNESS = "led_light_brightness_percent"
    val SYSTEM_KEYS = listOf(KEY_COLORS, KEY_ENABLED, KEY_BRIGHTNESS)

    // Defaults used by OdinSettings when a key is missing.
    private const val SYSTEM_DEFAULT_COLOR = 0xFF2BE0D8.toInt()
    private const val SYSTEM_DEFAULT_BRIGHTNESS = 0.5f

    fun turnOff() {
        Stick.entries.forEach { writeEnabled(it, false) }
    }

    /** Hands the LEDs back to whatever OdinSettings / the Quick Settings tile has configured. */
    fun restoreSystem(context: Context) {
        val system = readSystemState(context)
        writeEnabled(Stick.LEFT, system.leftEnabled)
        writeEnabled(Stick.RIGHT, system.rightEnabled)
        writeColor(Stick.LEFT, system.left, system.brightness)
        writeColor(Stick.RIGHT, system.right, system.brightness)
    }

    fun readSystemState(context: Context): SystemLedState {
        val resolver = context.contentResolver
        val colors = Settings.System.getString(resolver, KEY_COLORS)?.split(",")
        val enabled = Settings.System.getString(resolver, KEY_ENABLED)?.split(",")
        val brightness = Settings.System.getString(resolver, KEY_BRIGHTNESS)?.toFloatOrNull()
        return SystemLedState(
            leftEnabled = enabled?.getOrNull(0) == "1",
            rightEnabled = enabled?.getOrNull(1) == "1",
            left = parseColor(colors?.getOrNull(0)),
            right = parseColor(colors?.getOrNull(1)),
            brightness = brightness ?: SYSTEM_DEFAULT_BRIGHTNESS,
        )
    }

    /**
     * The exact value written to the driver. Callers updating often can compare it to the
     * previous one and skip the write: each one is an I2C transfer taking ~3.5 ms.
     */
    fun colorValue(color: Int, brightness: Float): String {
        val r = (Color.red(color) * brightness).toInt()
        val g = (Color.green(color) * brightness).toInt()
        val b = (Color.blue(color) * brightness).toInt()
        return "$RING_INDEX-$r:$g:$b"
    }

    fun writeColorValue(stick: Stick, value: String) {
        File("${stick.sysfsDir}/brightness").writeText(value)
    }

    private fun writeColor(stick: Stick, color: Int, brightness: Float) {
        writeColorValue(stick, colorValue(color, brightness))
    }

    fun writeEnabled(stick: Stick, enabled: Boolean) {
        File("${stick.sysfsDir}/enable").writeText(if (enabled) "1" else "0")
    }

    private fun parseColor(value: String?): Int =
        value?.let { runCatching { Color.parseColor(it.trim()) }.getOrNull() } ?: SYSTEM_DEFAULT_COLOR
}

data class SystemLedState(
    val leftEnabled: Boolean,
    val rightEnabled: Boolean,
    val left: Int,
    val right: Int,
    val brightness: Float,
)
