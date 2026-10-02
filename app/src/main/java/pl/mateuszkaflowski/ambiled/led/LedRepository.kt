package pl.mateuszkaflowski.ambiled.led

import pl.mateuszkaflowski.ambiled.R
import androidx.annotation.StringRes
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import org.json.JSONArray
import org.json.JSONObject

data class LedState(
    /** When false the app leaves the LEDs to the system (OdinSettings). */
    val enabled: Boolean,
    val left: Int,
    val right: Int,
    /** 0..1, multiplied into RGB the same way OdinSettings does. */
    val brightness: Float,
    /** 0 = LEDs follow the screen instantly, 1 = slowest fade. */
    val ambientSmoothing: Float = 0.5f,
    /** 0 = colors as on screen, 1 = strongest saturation boost. */
    val ambientSaturation: Float = 0.3f,
    /** Screen samples per second, one of [AMBIENT_FPS_OPTIONS]; 0 = every frame. */
    val ambientFps: Int = DEFAULT_AMBIENT_FPS,
    /** Leave out black pillarbox / letterbox bars so they don't dim the LEDs. */
    val ambientIgnoreBars: Boolean = false,
    /** Game mode: colors follow the game on the top screen, static colors otherwise. */
    val gameMode: Boolean = false,
    /** LEDs dimmed to zero (Quick Settings tile) while the app keeps control. */
    val lightsOff: Boolean = false,
)

/**
 * Measured on the Thor while gaming (extra CPU, % of one core): 5/s ~6 %, 10/s ~11 %,
 * unlimited ~13 %. 20-30/s cost more than unlimited because detaching and reattaching the
 * capture surface is itself expensive, so they're not offered.
 */
val AMBIENT_FPS_OPTIONS = listOf(5, 10, 0)
const val DEFAULT_AMBIENT_FPS = 5

data class Preset(
    val name: String,
    val left: Int,
    val right: Int,
    val builtIn: Boolean = false,
    /** Translated name for built-in presets; [name] is used otherwise. */
    @StringRes val nameRes: Int? = null,
)

val BuiltInPresets = listOf(
    Preset("Green / Magenta", 0xFF00FF00.toInt(), 0xFFFF00FF.toInt(), builtIn = true, nameRes = R.string.preset_green_magenta),
    Preset("Cyan / Orange", 0xFF00FFFF.toInt(), 0xFFFF8000.toInt(), builtIn = true, nameRes = R.string.preset_cyan_orange),
    Preset("Red / Blue", 0xFFFF0000.toInt(), 0xFF0000FF.toInt(), builtIn = true, nameRes = R.string.preset_red_blue),
    Preset("Pink", 0xFFFF008C.toInt(), 0xFFFF008C.toInt(), builtIn = true, nameRes = R.string.preset_pink),
    Preset("Odin", 0xFF2BE0D8.toInt(), 0xFF2BE0D8.toInt(), builtIn = true),
    Preset("White", 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), builtIn = true, nameRes = R.string.preset_white),
    // Captured from ambient light on a BOTW scene; deliberately dark, which makes it calm.
    Preset("Calm Violet", 0xFF312944.toInt(), 0xFF5A4726.toInt(), builtIn = true),
    // Colors game mode picked from the Eden emulator's icon.
    Preset("Eden", 0xFF8A40FF.toInt(), 0xFFFF008F.toInt(), builtIn = true),
)

/** Single source of truth for the LED configuration, shared by the UI and [LedService]. */
object LedRepository {

    private const val KEY_ENABLED = "enabled"
    private const val KEY_LEFT = "left"
    private const val KEY_RIGHT = "right"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_PRESETS = "presets"
    // Stored under the feature's old name; kept so existing settings survive the rename.
    private const val KEY_AMBIENT_SMOOTHING = "ambilight_smoothing"
    private const val KEY_AMBIENT_SATURATION = "ambilight_saturation"
    private const val KEY_AMBIENT_FPS = "ambilight_fps"
    private const val KEY_AMBIENT_IGNORE_BARS = "ambilight_ignore_bars"
    private const val KEY_GAME_MODE = "game_mode"
    private const val KEY_LIGHTS_OFF = "lights_off"

    private lateinit var prefs: SharedPreferences

    private val _state = MutableStateFlow(LedState(false, 0, 0, 0f))
    val state: StateFlow<LedState> = _state.asStateFlow()

    private val _userPresets = MutableStateFlow<List<Preset>>(emptyList())
    val userPresets: StateFlow<List<Preset>> = _userPresets.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("led", Context.MODE_PRIVATE)
        // First launch starts from whatever the system currently shows.
        val system = ThorLeds.readSystemState(context)
        _state.value = LedState(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            left = prefs.getInt(KEY_LEFT, system.left),
            right = prefs.getInt(KEY_RIGHT, system.right),
            brightness = prefs.getFloat(KEY_BRIGHTNESS, system.brightness),
            ambientSmoothing = prefs.getFloat(KEY_AMBIENT_SMOOTHING, 0.5f),
            ambientSaturation = prefs.getFloat(KEY_AMBIENT_SATURATION, 0.3f),
            ambientFps = prefs.getInt(KEY_AMBIENT_FPS, DEFAULT_AMBIENT_FPS)
                .takeIf { it in AMBIENT_FPS_OPTIONS } ?: DEFAULT_AMBIENT_FPS,
            ambientIgnoreBars = prefs.getBoolean(KEY_AMBIENT_IGNORE_BARS, false),
            gameMode = prefs.getBoolean(KEY_GAME_MODE, false),
            lightsOff = prefs.getBoolean(KEY_LIGHTS_OFF, false),
        )
        _userPresets.value = decodePresets(prefs.getString(KEY_PRESETS, null))
    }

    fun update(transform: (LedState) -> LedState) {
        val new = _state.updateAndGet(transform)
        prefs.edit()
            .putBoolean(KEY_ENABLED, new.enabled)
            .putInt(KEY_LEFT, new.left)
            .putInt(KEY_RIGHT, new.right)
            .putFloat(KEY_BRIGHTNESS, new.brightness)
            .putFloat(KEY_AMBIENT_SMOOTHING, new.ambientSmoothing)
            .putFloat(KEY_AMBIENT_SATURATION, new.ambientSaturation)
            .putInt(KEY_AMBIENT_FPS, new.ambientFps)
            .putBoolean(KEY_AMBIENT_IGNORE_BARS, new.ambientIgnoreBars)
            .putBoolean(KEY_GAME_MODE, new.gameMode)
            .putBoolean(KEY_LIGHTS_OFF, new.lightsOff)
            .apply()
    }

    fun savePreset(name: String) {
        val current = _state.value
        setUserPresets(_userPresets.value + Preset(name, current.left, current.right))
    }

    fun deletePreset(preset: Preset) {
        setUserPresets(_userPresets.value - preset)
    }

    private fun setUserPresets(presets: List<Preset>) {
        _userPresets.value = presets
        prefs.edit().putString(KEY_PRESETS, encodePresets(presets)).apply()
    }

    private fun encodePresets(presets: List<Preset>): String =
        JSONArray(presets.map {
            JSONObject().put("name", it.name).put("left", it.left).put("right", it.right)
        }).toString()

    private fun decodePresets(json: String?): List<Preset> {
        if (json == null) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            Preset(o.getString("name"), o.getInt("left"), o.getInt("right"))
        }
    }
}
