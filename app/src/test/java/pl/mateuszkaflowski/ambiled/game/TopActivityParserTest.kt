package pl.mateuszkaflowski.ambiled.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopActivityParserTest {

    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name).readText()

    @Test
    fun `finds the emulator and ROM on the top screen`() {
        val top = TopActivityParser.parse(fixture("dumpsys_game_on_top.txt"))!!
        assertEquals("org.azahar_emu.azahar", top.packageName)
        assertEquals("org.citra.citra_emu.activities.EmulationActivity", top.activity)
        assertEquals("rip.moth.cocoonshell", top.launchedFromPackage)
        assertEquals(
            "content://com.android.externalstorage.documents/tree/3233-6631%3AROMS/document/" +
                "3233-6631%3AROMS%2FN3DS%2FProfessor%20Layton%20and%20the%20Azran%20Legacy%20(Europe).3ds",
            top.dataUri,
        )
    }

    @Test
    fun `display 0 is found even when listed after the bottom screen`() {
        val top = TopActivityParser.parse(fixture("dumpsys_home_on_top.txt"))!!
        assertEquals("rip.moth.cocoonshell", top.packageName)
        // Relative class names are expanded.
        assertEquals("rip.moth.cocoonshell.MainActivity", top.activity)
        assertNull(top.dataUri)
        // "launchedFromPackage=null" for the home screen.
        assertNull(top.launchedFromPackage)
    }

    @Test
    fun `other displays can be queried`() {
        val top = TopActivityParser.parse(fixture("dumpsys_home_on_top.txt"), displayId = 4)!!
        assertEquals("com.example.thor_led_manager", top.packageName)
    }

    @Test
    fun `missing display gives null`() {
        assertNull(TopActivityParser.parse(fixture("dumpsys_home_on_top.txt"), displayId = 7))
    }
}
