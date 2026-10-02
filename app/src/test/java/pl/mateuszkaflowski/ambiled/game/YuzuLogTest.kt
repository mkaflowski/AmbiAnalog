package pl.mateuszkaflowski.ambiled.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YuzuLogTest {

    // Filtered logcat of Eden on the Thor, starting ANIMAL WELL from Eden's own menu.
    private val animalWell = """
        09-30 19:35:58.069  3756  3756 I YuzuNative: [1976.759684] Config <Info> frontend_common/config.cpp:77:WriteToIni: Writing Game Specific configuration
        09-30 19:35:58.080  3756  3756 I YuzuNative: [1976.770168] Frontend <Info> main/jni/native_log.cpp:22:Java_org_yuzu_yuzu_1emu_utils_Log_info: [EmulationFragment] Starting view setup for game: ANIMAL WELL
        09-30 19:35:58.299  3756  9453 I YuzuNative: [1976.988873] Loader <Info> core/file_sys/patch_manager.cpp:173:PatchExeFS: Patching ExeFS for title_id=010020D01AD24000
    """.trimIndent()

    @Test
    fun `reads title and title id`() {
        assertEquals(YuzuGame("ANIMAL WELL", "010020D01AD24000"), YuzuLog.parse(animalWell))
    }

    @Test
    fun `the most recently started game wins`() {
        val earlier = """
            I YuzuNative: [EmulationFragment] Starting view setup for game: Celeste
            I YuzuNative: PatchExeFS: Patching ExeFS for title_id=01002b30028f6000
        """.trimIndent()
        assertEquals(YuzuGame("ANIMAL WELL", "010020D01AD24000"), YuzuLog.parse("$earlier\n$animalWell"))
    }

    @Test
    fun `title id is optional`() {
        assertEquals(
            YuzuGame("Celeste", null),
            YuzuLog.parse("I YuzuNative: [EmulationFragment] Starting view setup for game: Celeste"),
        )
    }

    @Test
    fun `no game start gives null`() {
        assertNull(YuzuLog.parse("I YuzuNative: Loader <Info> something else"))
    }
}
