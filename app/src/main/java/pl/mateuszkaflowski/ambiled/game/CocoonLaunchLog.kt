package pl.mateuszkaflowski.ambiled.game

/** One game start recorded by the Cocoon launcher. */
data class CocoonLaunch(val title: String, val romUri: String?, val playerPackage: String?)

/**
 * Parses Cocoon's `/sdcard/Cocoon/launch_debug.log`. It records every game start with the
 * ROM and the emulator, which matters for emulators like RetroArch that get the ROM in intent
 * extras: `dumpsys` shows those only as "(has extras)".
 *
 * ```
 * [..] [INFO] Game: Breath of Fire II | File: Breath of Fire II (USA).zip
 * [..] [INFO] ROM URI: content://…%2Fgba%2FBreath%20of%20Fire%20II%20(USA).zip
 * [..] [INFO] Player: RetroArch (64 bits) - mgba (com.retroarch.aarch64/com.retroarch…RetroActivityFuture)
 * ```
 * Not every start has a "Launch:" header, so entries are keyed on the "Game:" line. Swapping
 * the game in a running emulator ("Swap:") logs no "ROM URI:", only `{file.uri} → …`.
 */
object CocoonLaunchLog {

    const val PATH = "/sdcard/Cocoon/launch_debug.log"

    private val GAME = Regex("""\] Game: (.+?) \| File: """)
    private val ROM_URI = Regex("""\] (?:ROM URI:|\{file\.uri\} →) (\S+)""")
    private val PLAYER = Regex("""\] Player: .*\(([\w.]+)/[\w.$]+\)\s*$""")

    fun parse(log: String): List<CocoonLaunch> {
        val launches = mutableListOf<CocoonLaunch>()
        var current: CocoonLaunch? = null
        for (line in log.lineSequence()) {
            val game = GAME.find(line)
            if (game != null) {
                current?.let(launches::add)
                current = CocoonLaunch(game.groupValues[1].trim(), null, null)
                continue
            }
            val entry = current ?: continue
            // Launches log the URI twice; the first one is enough.
            if (entry.romUri == null) ROM_URI.find(line)?.let { current = entry.copy(romUri = it.groupValues[1]) }
            PLAYER.find(line)?.let { current = entry.copy(playerPackage = it.groupValues[1]) }
        }
        current?.let(launches::add)
        return launches
    }

    /** The most recent start that ran in [packageName], i.e. what that emulator is showing. */
    fun lastLaunch(log: String, packageName: String): CocoonLaunch? =
        parse(log).lastOrNull { it.playerPackage == packageName }
}
