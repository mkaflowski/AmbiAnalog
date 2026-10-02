package pl.mateuszkaflowski.ambiled.game

import android.content.Context
import android.content.SharedPreferences
import pl.mateuszkaflowski.ambiled.ambient.StickColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** A game recognised on the top screen, with the colors game mode shows for it. */
data class CurrentGame(
    /** "rom:<platform>/<rom name>" or "app:<package>". */
    val id: String,
    val title: String,
    /** Platform folder for ROMs, e.g. "N3DS"; null for Android games. */
    val platform: String?,
    val packageName: String,
    /** Cover the automatic colors come from, if a frontend downloaded one. */
    val artwork: File?,
    val autoColors: StickColors?,
    /** Assigned to this game in the app's bundled list ([BundledGameColors]). */
    val presetColors: StickColors?,
    val customColors: StickColors?,
) {
    val colors: StickColors? get() = customColors ?: presetColors ?: autoColors
}

/** What game mode currently shows; null when no game is on the top screen. */
object GameStatus {
    private val _current = MutableStateFlow<CurrentGame?>(null)
    val current: StateFlow<CurrentGame?> = _current.asStateFlow()

    internal fun set(game: CurrentGame?) {
        _current.value = game
    }

    internal fun updateCustom(id: String, colors: StickColors?) {
        _current.update { if (it?.id == id) it.copy(customColors = colors) else it }
    }
}

/** Per-game colors: the user's own choice, plus a cache of the ones computed from covers. */
object GameProfiles {

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("game_profiles", Context.MODE_PRIVATE)
    }

    fun custom(id: String): StickColors? = read("custom:$id")

    /** Null goes back to the colors from the cover. */
    fun setCustom(id: String, colors: StickColors?) {
        write("custom:$id", colors)
        GameStatus.updateCustom(id, colors)
    }

    // Bump when the way colors are computed from artwork changes, so old results are redone.
    private const val AUTO_VERSION = 3

    fun cachedAuto(id: String): StickColors? = read("auto$AUTO_VERSION:$id")

    fun cacheAuto(id: String, colors: StickColors) = write("auto$AUTO_VERSION:$id", colors)

    private fun read(key: String): StickColors? {
        val parts = prefs.getString(key, null)?.split(",") ?: return null
        return StickColors(parts[0].toLong(16).toInt(), parts[1].toLong(16).toInt())
    }

    private fun write(key: String, colors: StickColors?) {
        prefs.edit().apply {
            if (colors == null) remove(key)
            else putString(key, "%08x,%08x".format(colors.left, colors.right))
        }.apply()
    }
}
