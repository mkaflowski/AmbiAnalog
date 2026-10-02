package pl.mateuszkaflowski.ambiled.game

import android.content.Context
import pl.mateuszkaflowski.ambiled.ambient.StickColors

/** Per-game colors shipped in `assets/game_colors.txt`, for games without usable artwork. */
object BundledGameColors {

    private const val ASSET = "game_colors.txt"
    private var colors: Map<String, StickColors> = emptyMap()

    fun init(context: Context) {
        colors = context.assets.open(ASSET).bufferedReader().use { parse(it.readText()) }
    }

    fun forId(id: String): StickColors? = colors[id]

    /** `<id> | <left> | <right> | <name>` per line; blank lines and `#` comments are skipped. */
    fun parse(text: String): Map<String, StickColors> =
        text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('|').map(String::trim)
                if (parts.size < 3) return@mapNotNull null
                val left = parseColor(parts[1]) ?: return@mapNotNull null
                val right = parseColor(parts[2]) ?: return@mapNotNull null
                parts[0] to StickColors(left, right)
            }
            .toMap()

    private fun parseColor(value: String): Int? {
        val hex = value.removePrefix("#")
        if (hex.length != 6) return null
        return hex.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
    }
}
