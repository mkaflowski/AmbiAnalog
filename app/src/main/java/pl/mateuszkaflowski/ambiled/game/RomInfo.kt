package pl.mateuszkaflowski.ambiled.game

import java.io.ByteArrayOutputStream

/**
 * A ROM identified from the data URI a frontend launched an emulator with, e.g.
 * `content://…/document/3233-6631%3AROMS%2FN3DS%2FProfessor%20Layton…(Europe).3ds`.
 */
data class RomInfo(
    /** System folder right under the ROMs root, e.g. "N3DS" or "famicom". */
    val platform: String,
    /**
     * Path below the system folder without extension, e.g. "Ninja Turtles/Teenage Mutant Ninja
     * Turtles (Japan)". Frontends mirror it in their media folders.
     */
    val relativePath: String,
) {
    /** File name without extension, e.g. "Professor Layton and the Azran Legacy (Europe)". */
    val romName: String get() = relativePath.substringAfterLast('/')

    /** Human-readable name: region/version tags dropped, "Kirby_ Planet" -> "Kirby: Planet". */
    val title: String
        get() = romName
            .replace(TAGS, "")
            .replace("_ ", ": ")
            .trim()
            .ifEmpty { romName }

    companion object {
        private val TAGS = Regex("""\s*[(\[][^)\]]*[)\]]""")
        private const val ROMS_ROOT = "roms"

        fun fromUri(uri: String): RomInfo? {
            val encoded = if ("/document/" in uri) {
                uri.substringAfter("/document/")
            } else {
                uri.substringAfter("://").substringAfter('/') // Skip the authority.
            }
            val decoded = percentDecode(encoded)
            // Storage document ids look like "3233-6631:ROMS/N3DS/…"; drop the volume, but not
            // a colon inside a file name.
            val colon = decoded.indexOf(':')
            val slash = decoded.indexOf('/')
            val path = if (colon in 0 until slash) decoded.substring(colon + 1) else decoded
            val segments = path.split('/').filter { it.isNotEmpty() }
            if (segments.size < 2) return null
            // ES-DE style layout: <ROMS>/<system>/[subfolders/]<file>. Without a ROMS folder,
            // the parent folder is the best guess for the system.
            val root = segments.indexOfLast { it.equals(ROMS_ROOT, ignoreCase = true) }
            val systemIndex = if (root in 0 until segments.size - 2) root + 1 else segments.size - 2
            val below = segments.drop(systemIndex + 1)
            val file = below.last()
            val name = file.substringBeforeLast('.', file)
            if (name.isBlank()) return null
            return RomInfo(
                platform = segments[systemIndex],
                relativePath = (below.dropLast(1) + name).joinToString("/"),
            )
        }

        /** URI percent-decoding; unlike URLDecoder, keeps '+' as is. */
        private fun percentDecode(value: String): String {
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%' && i + 2 < value.length) {
                    val byte = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (byte != null) {
                        out.write(byte)
                        i += 3
                        continue
                    }
                }
                out.write(c.toString().toByteArray())
                i++
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
