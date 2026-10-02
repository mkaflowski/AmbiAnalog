package pl.mateuszkaflowski.ambiled.game

/** A Switch game running in a yuzu-derived emulator. */
data class YuzuGame(val title: String, val titleId: String?)

/**
 * Reads the running game from the log of a yuzu-derived emulator (Eden, Eden Nightly…). Games
 * picked in the emulator's own menu arrive in intent extras, which dumpsys doesn't show, but
 * the emulator logs them when starting:
 * ```
 * … [EmulationFragment] Starting view setup for game: ANIMAL WELL
 * … PatchExeFS: Patching ExeFS for title_id=010020D01AD24000
 * ```
 * The emulator's log file (e.g. `files/log/eden_log.txt`) keeps the whole session; logcat is
 * only a fallback, as heavy games flood its ring buffer within a minute.
 */
object YuzuLog {

    /** Emulation activity class, kept by yuzu forks; identifies them regardless of package name. */
    const val EMULATION_ACTIVITY = "org.yuzu.yuzu_emu.activities.EmulationActivity"

    /** Shell script printing just the lines [parse] needs: from the log file, else from logcat. */
    fun shellCommand(packageName: String): String {
        val grep = "grep -hE '$GAME_MARKER|$TITLE_ID_MARKER'"
        // "eden_log.txt" etc.; the rotated "*_log.txt.old.txt" doesn't match.
        val logFile = "/sdcard/Android/data/$packageName/files/log/*_log.txt"
        return "f=\$($grep $logFile 2>/dev/null | tail -n 40); " +
            "if [ -n \"\$f\" ]; then echo \"\$f\"; " +
            "else p=\$(pidof $packageName) && logcat -d --pid=\$p | $grep | tail -n 40; fi"
    }

    private const val GAME_MARKER = "Starting view setup for game: "
    private const val TITLE_ID_MARKER = "Patching ExeFS for title_id="
    private val TITLE_ID = Regex("""title_id=([0-9A-Fa-f]{16})""")

    /** The last game started in [log]; its title id is the one logged after it. */
    fun parse(log: String): YuzuGame? {
        val lines = log.lines()
        val start = lines.indexOfLast { GAME_MARKER in it }
        if (start < 0) return null
        val title = lines[start].substringAfter(GAME_MARKER).trim().ifEmpty { return null }
        val titleId = lines.drop(start + 1).firstNotNullOfOrNull { TITLE_ID.find(it)?.groupValues?.get(1) }
        return YuzuGame(title, titleId?.uppercase())
    }
}
