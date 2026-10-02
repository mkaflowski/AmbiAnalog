package pl.mateuszkaflowski.ambiled.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CocoonLaunchLogTest {

    // Tail of a real log from the Thor; starts mid-line, like `tail -c` output does.
    private val log = javaClass.classLoader!!.getResource("cocoon_launch_debug.txt").readText()

    @Test
    fun `a full launch has its ROM`() {
        val launch = CocoonLaunchLog.parse(log).single { it.title == "Breath of Fire II" }
        val rom = RomInfo.fromUri(launch.romUri!!)!!
        assertEquals("gba", rom.platform)
        assertEquals("Breath of Fire II (USA)", rom.romName)
    }

    @Test
    fun `a swap in a running emulator has its ROM too`() {
        // Last RetroArch entry is a "Swap:", logged without a "ROM URI:" line.
        val launch = CocoonLaunchLog.lastLaunch(log, "com.retroarch.aarch64")!!
        assertEquals("Super Mario Advance", launch.title)
        val rom = RomInfo.fromUri(launch.romUri!!)!!
        assertEquals("gba", rom.platform)
        assertEquals("Super Mario Advance (USA, Europe)", rom.romName)
    }

    @Test
    fun `launches without a Launch header are still found`() {
        val titles = CocoonLaunchLog.parse(log).map { it.title }
        assertEquals(
            listOf("Kirby Planet Robobot", "14 Robotron 2084", "Breath of Fire", "Breath of Fire II", "Super Mario Advance"),
            titles,
        )
    }

    @Test
    fun `each emulator gets its own last launch`() {
        val launch = CocoonLaunchLog.lastLaunch(log, "org.azahar_emu.azahar")!!
        assertEquals("Kirby Planet Robobot", launch.title)
        assertEquals("Kirby_ Planet Robobot (CTR-P-AT3A)", RomInfo.fromUri(launch.romUri!!)!!.romName)
    }

    @Test
    fun `unknown package gives null`() {
        assertNull(CocoonLaunchLog.lastLaunch(log, "com.example.unknown"))
    }
}
