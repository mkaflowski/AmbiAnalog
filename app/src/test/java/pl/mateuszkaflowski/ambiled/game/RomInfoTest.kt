package pl.mateuszkaflowski.ambiled.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RomInfoTest {

    @Test
    fun `parses a storage document uri`() {
        val rom = RomInfo.fromUri(
            "content://com.android.externalstorage.documents/tree/3233-6631%3AROMS/document/" +
                "3233-6631%3AROMS%2FN3DS%2FProfessor%20Layton%20and%20the%20Azran%20Legacy%20(Europe).3ds"
        )!!
        assertEquals("N3DS", rom.platform)
        assertEquals("Professor Layton and the Azran Legacy (Europe)", rom.romName)
        assertEquals("Professor Layton and the Azran Legacy", rom.title)
    }

    @Test
    fun `parses a file uri`() {
        val rom = RomInfo.fromUri("file:///storage/3233-6631/ROMS/snes/Super%20Metroid%20(Japan%2C%20USA).sfc")!!
        assertEquals("snes", rom.platform)
        assertEquals("Super Metroid (Japan, USA)", rom.romName)
        assertEquals("Super Metroid", rom.title)
    }

    @Test
    fun `title cleanup keeps plus signs and fixes colon placeholders`() {
        val rom = RomInfo.fromUri("file:///x/N3DS/Kirby_%20Planet%20Robobot%20(CTR-P-AT3A)%20[v1.1]+.cci")!!
        assertEquals("Kirby_ Planet Robobot (CTR-P-AT3A) [v1.1]+", rom.romName)
        assertEquals("Kirby: Planet Robobot+", rom.title)
    }

    @Test
    fun `colon in a file name is kept`() {
        val rom = RomInfo.fromUri("file:///storage/emulated/0/ROMS/snes/Zelda%3A%20A%20Link%20to%20the%20Past.sfc")!!
        assertEquals("snes", rom.platform)
        assertEquals("Zelda: A Link to the Past", rom.romName)
    }

    @Test
    fun `ROM in a subfolder keeps the system folder and the relative path`() {
        val rom = RomInfo.fromUri(
            "content://com.android.externalstorage.documents/tree/3233-6631%3AROMS%2Ffamicom/document/" +
                "3233-6631%3AROMS%2Ffamicom%2FNinja%20Turtles%2FTeenage%20Mutant%20Ninja%20Turtles%20(Japan).nes"
        )!!
        assertEquals("famicom", rom.platform)
        assertEquals("Ninja Turtles/Teenage Mutant Ninja Turtles (Japan)", rom.relativePath)
        assertEquals("Teenage Mutant Ninja Turtles (Japan)", rom.romName)
        assertEquals("Teenage Mutant Ninja Turtles", rom.title)
    }

    @Test
    fun `uri without a folder gives null`() {
        assertNull(RomInfo.fromUri("content://some.provider/12345"))
    }
}
