package pl.mateuszkaflowski.ambiled.game

/**
 * Reads the running game from Banana Native's session log. The game runs in the app's `:game`
 * process and gets its ROM in intent extras, which dumpsys doesn't show, but every start writes
 * `files/logs/<TITLE ID>-<date>-<time>.log`, headed by the launch environment:
 * ```
 * [nxlaunch] env DM_ROMFS=/storage/…/Donkey Kong Bananza + Update 3.2.0 + DLC.nup:update/458752/romfs
 * ```
 * The newest log belongs to the game on screen.
 */
object BananaNativeLog {

    /** Release and test builds of the app. */
    val PACKAGES = setOf("dev.bananative.android", "dev.nxnative.android")

    /** Game activity class, whatever the application ID (release and test builds differ). */
    const val GAME_ACTIVITY_SUFFIX = ".GameActivity"

    /** Shell script printing the newest log's path, then its ROM line. */
    fun shellCommand(packageName: String): String =
        "f=\$(ls -t /sdcard/Android/data/$packageName/files/logs/*.log 2>/dev/null | head -n 1); " +
            "[ -n \"\$f\" ] && echo \"\$f\" && grep -m 1 'DM_ROMFS=' \"\$f\""

    private val FILE_NAME = Regex("""/([0-9A-Fa-f]{16})-[^/]*\.log\s*$""", RegexOption.MULTILINE)
    private val ROMFS = Regex("""DM_ROMFS=(.+)""")
    // " + Update 3.2.0 + DLC" and "[tags]" / "(tags)" are dump packaging, not the title.
    private val SUFFIXES = Regex("""\s*(\+.*|\[.*|\(.*)$""")

    fun parse(output: String): YuzuGame? {
        val titleId = FILE_NAME.find(output)?.groupValues?.get(1)?.uppercase() ?: return null
        val title = ROMFS.find(output)?.groupValues?.get(1)?.let(::titleFromRomfs)
        return YuzuGame(title ?: titleId, titleId)
    }

    private fun titleFromRomfs(value: String): String? {
        // "<path to .nup>:update/…/romfs" or a plain folder path.
        val path = value.trim().substringBefore(".nup:").removeSuffix(".nup")
        return path.substringAfterLast('/').replace(SUFFIXES, "").trim().ifEmpty { null }
    }
}
