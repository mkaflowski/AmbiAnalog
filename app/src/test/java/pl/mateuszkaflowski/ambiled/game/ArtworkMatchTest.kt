package pl.mateuszkaflowski.ambiled.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkMatchTest {

    private val tmnt = RomInfo("famicom", "Ninja Turtles/Teenage Mutant Ninja Turtles (Japan)")

    @Test
    fun `patterns search every system by relative path`() {
        assertEquals(
            listOf(
                "*/icon/Ninja Turtles/Teenage Mutant Ninja Turtles (Japan).*",
                "*/hero/Ninja Turtles/Teenage Mutant Ninja Turtles (Japan).*",
            ),
            ArtworkMatch.patternsFor(tmnt),
        )
    }

    @Test
    fun `glob characters in ROM names are escaped`() {
        val rom = RomInfo("snes", "Super Metroid (Japan, USA) [!]")
        assertEquals("*/icon/Super Metroid (Japan, USA) \\[!\\].*", ArtworkMatch.patternsFor(rom).first())
    }

    @Test
    fun `icon is preferred over hero`() {
        // Real `find` output on the Thor: Cocoon files the "famicom" ROM under "nes".
        val found = listOf(
            "/sdcard/Cocoon/downloaded_media/nes/hero/Ninja Turtles/Teenage Mutant Ninja Turtles (Japan).jpg",
            "/sdcard/Cocoon/downloaded_media/nes/icon/Ninja Turtles/Teenage Mutant Ninja Turtles (Japan).png",
            "",
        )
        assertEquals(found[1], ArtworkMatch.pick(found, tmnt.platform))
    }

    @Test
    fun `same system wins over another system's icon`() {
        val found = listOf(
            "/m/nes/icon/Ninja Turtles/Teenage Mutant Ninja Turtles (Japan).png",
            "/m/famicom/hero/Ninja Turtles/Teenage Mutant Ninja Turtles (Japan).jpg",
        )
        assertEquals(found[1], ArtworkMatch.pick(found, tmnt.platform))
    }

    @Test
    fun `switch artwork is matched by title id`() {
        assertEquals(
            "*/icon/*\\[010020D01AD24000\\]*",
            ArtworkMatch.patternsForTitleId("010020D01AD24000").first(),
        )
    }

    @Test
    fun `nothing found gives null`() {
        assertNull(ArtworkMatch.pick(listOf("", "  "), tmnt.platform))
    }
}
