package pl.mateuszkaflowski.ambiled.game

/** The activity on top of a display, as reported by `dumpsys activity activities`. */
data class TopActivity(
    val packageName: String,
    /** Fully qualified class name. */
    val activity: String,
    val dataUri: String?,
    /** Who started it, e.g. the launcher, or the emulator itself when a game is picked in its own menu. */
    val launchedFromPackage: String?,
)

object TopActivityParser {

    private val HIST = Regex("""\* Hist\s+#\d+: ActivityRecord\{\S+ u\d+ ([^/\s]+)/([^\s}]+)""")
    private val DATA = Regex("""\bdat=(\S+)""")
    private val LAUNCHED_FROM = Regex("""\blaunchedFromPackage=(\S+)""")

    /**
     * Finds the top activity of [displayId]. Display sections are listed "from top to bottom",
     * but not in display order (the Thor lists its bottom screen first), so the section is
     * looked up by number. The shell's dumpsys shows the full data URI, unlike logcat.
     */
    fun parse(dumpsys: String, displayId: Int = 0): TopActivity? {
        val lines = dumpsys.lines()
        val start = lines.indexOfFirst { it.startsWith("Display #$displayId ") }
        if (start < 0) return null
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (line.startsWith("Display #") || (line.isNotBlank() && !line.startsWith(" "))) return null
            val record = HIST.find(line) ?: continue
            val packageName = record.groupValues[1]
            val activity = record.groupValues[2].let { if (it.startsWith(".")) packageName + it else it }
            // The record's details follow within a few lines, up to its Intent.
            val details = lines.subList(i + 1, minOf(i + 8, lines.size))
            val intent = details.firstOrNull { it.trimStart().startsWith("Intent {") }
            val launchedFrom = details.firstNotNullOfOrNull { LAUNCHED_FROM.find(it)?.groupValues?.get(1) }
            return TopActivity(
                packageName = packageName,
                activity = activity,
                dataUri = intent?.let { DATA.find(it)?.groupValues?.get(1) },
                launchedFromPackage = launchedFrom?.takeIf { it != "null" },
            )
        }
        return null
    }
}
