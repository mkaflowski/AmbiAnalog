package pl.mateuszkaflowski.ambiled.game

/**
 * Locates artwork in an ES-DE style media folder: `<root>/<system>/<type>/<relative path>.<ext>`.
 * The frontend's system folder isn't always the ROM folder (Cocoon files ROMs from "famicom"
 * under "nes"), so artwork is searched under every system.
 */
object ArtworkMatch {

    /** Preferred first: a cover-like tile, then the wide background. */
    val TYPES = listOf("icon", "hero")

    /** `find -path` patterns for [rom]'s artwork of every type in [TYPES]. */
    fun patternsFor(rom: RomInfo): List<String> =
        TYPES.map { type -> "*/$type/${globEscape(rom.relativePath)}.*" }

    /**
     * Patterns for a Switch title id, which scrapers put in file names, e.g.
     * "ANIMAL WELL [010020D01AD24000][USA][v0].jpg".
     */
    fun patternsForTitleId(titleId: String): List<String> =
        TYPES.map { type -> "*/$type/*${globEscape("[$titleId]")}*" }

    /** Best of the `find` results: [preferredSystem] first, then by [TYPES] order. */
    fun pick(paths: List<String>, preferredSystem: String?): String? =
        paths.map(String::trim).filter(String::isNotEmpty).minByOrNull { path ->
            val sameSystem = preferredSystem != null && path.contains("/$preferredSystem/", ignoreCase = true)
            val typeRank = TYPES.indexOfFirst { path.contains("/$it/") }.let { if (it < 0) TYPES.size else it }
            (if (sameSystem) 0 else 10) + typeRank
        }

    /** ROM names often contain "[!]"-style tags, which `find` would read as a character class. */
    private fun globEscape(value: String) = value.replace(Regex("""[\\*?\[\]]"""), """\\$0""")
}
