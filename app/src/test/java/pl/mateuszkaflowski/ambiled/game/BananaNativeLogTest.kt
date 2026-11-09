package pl.mateuszkaflowski.ambiled.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BananaNativeLogTest {

    private val bananza = """
        /sdcard/Android/data/dev.bananative.android/files/logs/040074A01BF12000-20261108-112613.log
        [nxlaunch] env DM_ROMFS=/storage/3233-6631/Switch/Donkey Kong Bananza + Update 3.2.0 + DLC.nup:update/458752/romfs
    """.trimIndent()

    @Test
    fun `reads title id from the file name and title from the ROM`() {
        assertEquals(YuzuGame("Donkey Kong Bananza", "040074A01BF12000"), BananaNativeLog.parse(bananza))
    }

    @Test
    fun `falls back to the title id without a ROM line`() {
        val game = BananaNativeLog.parse("/x/files/logs/040074a01bf12000-20261108-112613.log")
        assertEquals(YuzuGame("040074A01BF12000", "040074A01BF12000"), game)
    }

    @Test
    fun `no log means no game`() {
        assertNull(BananaNativeLog.parse(""))
    }
}
