package pl.mateuszkaflowski.ambiled.game

import pl.mateuszkaflowski.ambiled.ambient.StickColors
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class BundledGameColorsTest {

    @Test
    fun `parses entries and skips comments and broken lines`() {
        val colors = BundledGameColors.parse(
            """
            # comment
            switch:01007EF00011E000 | #62ff62 | #79e6ff | The Legend of Zelda: Breath of the Wild

            rom:snes/Super Metroid (Japan, USA) | #FF0000 | #0000ff
            broken line
            switch:0000000000000000 | #12345 | #000000 | too short color
            """.trimIndent()
        )
        assertEquals(
            mapOf(
                "switch:01007EF00011E000" to StickColors(0xFF62FF62.toInt(), 0xFF79E6FF.toInt()),
                "rom:snes/Super Metroid (Japan, USA)" to StickColors(0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
            ),
            colors,
        )
    }

    @Test
    fun `shipped file is valid`() {
        // Unit tests run from the module directory.
        val text = File("src/main/assets/game_colors.txt").readText()
        val entries = text.lines().count { it.isNotBlank() && !it.trimStart().startsWith("#") }
        assertEquals(entries, BundledGameColors.parse(text).size)
    }
}
