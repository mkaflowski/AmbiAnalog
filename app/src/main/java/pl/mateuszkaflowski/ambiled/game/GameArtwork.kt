package pl.mateuszkaflowski.ambiled.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import pl.mateuszkaflowski.ambiled.adb.AdbShell
import pl.mateuszkaflowski.ambiled.ambient.StickColors
import com.flyfishxu.kadb.Kadb
import java.io.File
import java.security.MessageDigest

/** Finds game artwork and turns it into LED colors. */
object GameArtwork {

    // Cocoon keeps ES-DE style scraped media, see ArtworkMatch.
    private const val COCOON_MEDIA = "/sdcard/Cocoon/downloaded_media"
    private const val DECODE_SIZE = 256
    // Scraped covers often carry a platform frame (e.g. the purple "GBA" border) that would
    // otherwise win the palette for every game of that platform.
    private const val COVER_INSET = 0.12f

    /**
     * Finds artwork matching [patterns] (see [ArtworkMatch]) in Cocoon's media folder and copies
     * it into our external files dir under [cacheKey]. Cocoon's folder has a .nomedia file, so
     * it's not reachable through MediaStore; the shell (group ext_data_rw) can read it and write
     * into our Android/data folder. Returns null if the frontend has no artwork for the game.
     */
    fun copyArtwork(
        context: Context,
        kadb: Kadb,
        cacheKey: String,
        patterns: List<String>,
        preferredSystem: String?,
    ): File? {
        val target = artworkFile(context, cacheKey) ?: return null
        val paths = patterns.joinToString(" -o ") { "-path ${AdbShell.quote(it)}" }
        val found = kadb.shell("find $COCOON_MEDIA -type f \\( $paths \\) 2>/dev/null").output.lines()
        val source = ArtworkMatch.pick(found, preferredSystem) ?: return null
        // World-readable, so our uid can open a file the shell created.
        val quotedTarget = AdbShell.quote(target.path)
        kadb.shell("cp ${AdbShell.quote(source)} $quotedTarget && chmod 644 $quotedTarget")
        return target.takeIf { it.isFile && it.length() > 0 }
    }

    /** Artwork copied earlier by [copyArtwork], if any. */
    fun cachedArtwork(context: Context, cacheKey: String): File? =
        artworkFile(context, cacheKey)?.takeIf { it.isFile && it.length() > 0 }

    private fun artworkFile(context: Context, cacheKey: String): File? =
        context.getExternalFilesDir("covers")?.let { File(it, hash(cacheKey)) }

    fun colorsFromFile(file: File): StickColors? =
        decode(file)?.let { bitmap -> colorsFrom(bitmap, COVER_INSET).also { bitmap.recycle() } }

    private fun decode(file: File): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        options.inSampleSize = sampleSize(options.outWidth, options.outHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.path, options)
    }

    fun colorsFromAppIcon(context: Context, packageName: String): StickColors? = runCatching {
        val icon = context.packageManager.getApplicationIcon(packageName)
        colorsFrom(icon.toBitmap(DECODE_SIZE / 2, DECODE_SIZE / 2), inset = 0f)
    }.getOrNull()

    private fun colorsFrom(bitmap: Bitmap, inset: Float): StickColors? {
        val dx = (bitmap.width * inset).toInt()
        val dy = (bitmap.height * inset).toInt()
        val palette = Palette.from(bitmap)
            .setRegion(dx, dy, bitmap.width - dx, bitmap.height - dy)
            .maximumColorCount(16)
            .generate()
        return CoverPalette.pick(palette.swatches.map { Swatch(it.rgb, it.population) })
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var size = 1
        while (width / (size * 2) >= DECODE_SIZE && height / (size * 2) >= DECODE_SIZE) size *= 2
        return size
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
