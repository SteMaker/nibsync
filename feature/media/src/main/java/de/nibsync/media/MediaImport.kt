package de.nibsync.media

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.caverock.androidsvg.SVG
import de.nibsync.model.Asset
import de.nibsync.model.ElementDraft
import de.nibsync.model.FileLink
import de.nibsync.model.FileLinkDraft
import de.nibsync.model.ImageDraft

/** A file chosen for inserting into a page. */
class PickedFile(val bytes: ByteArray, val mimeType: String, val fileName: String)

data class SizeMm(val width: Float, val height: Float)

/** Recognising inserted files and deciding where and how big they appear on the page. */
object MediaImport {
    /** Larger files are refused. */
    const val MAX_BYTES = 50L * 1024 * 1024

    /** Pixel pictures get the size they would have printed at this resolution ... */
    const val PRINT_DPI = 150f

    /** ... but at most this share of the page width. */
    const val MAX_PAGE_SHARE = 0.8f

    private const val SVG_DPI = 96f
    private const val MM_PER_INCH = 25.4f

    /**
     * The supported type of a file, judged by its content (the declared type and the file name
     * only help with SVG, which is plain text), or null for anything else.
     */
    fun detectType(bytes: ByteArray, declaredType: String?, fileName: String?): String? {
        fun startsWith(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
        return when {
            startsWith(0x89, 0x50, 0x4E, 0x47) -> Asset.PNG
            startsWith(0xFF, 0xD8, 0xFF) -> Asset.JPEG
            startsWith(0x25, 0x50, 0x44, 0x46) -> Asset.PDF // %PDF
            looksLikeSvg(bytes, declaredType, fileName) -> Asset.SVG
            else -> null
        }
    }

    private fun looksLikeSvg(bytes: ByteArray, declaredType: String?, fileName: String?): Boolean {
        val head = bytes.copyOf(minOf(bytes.size, 2048)).decodeToString()
        return "<svg" in head || ((declaredType == Asset.SVG || fileName?.endsWith(".svg", ignoreCase = true) == true) && "<" in head)
    }

    /** Size a picture would naturally have on paper, or null if it cannot be read. */
    fun naturalSizeMm(bytes: ByteArray, mimeType: String): SizeMm? = when (mimeType) {
        Asset.PNG, Asset.JPEG -> {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                null
            } else {
                val turned = mimeType == Asset.JPEG && exifRotation(bytes) % 180 != 0
                val w = if (turned) options.outHeight else options.outWidth
                val h = if (turned) options.outWidth else options.outHeight
                SizeMm(w * MM_PER_INCH / PRINT_DPI, h * MM_PER_INCH / PRINT_DPI)
            }
        }
        Asset.SVG -> runCatching {
            val svg = SVG.getFromString(bytes.decodeToString())
            val box = svg.documentViewBox
            var w = svg.documentWidth
            var h = svg.documentHeight
            if ((w <= 0f || h <= 0f) && box != null && box.width() > 0f && box.height() > 0f) {
                w = box.width()
                h = box.height()
            }
            if (w <= 0f || h <= 0f) SizeMm(100f, 100f) else SizeMm(w * MM_PER_INCH / SVG_DPI, h * MM_PER_INCH / SVG_DPI)
        }.getOrNull()
        else -> null
    }

    /** Clockwise rotation in degrees stored in a JPEG's EXIF data (camera photos). */
    fun exifRotation(bytes: ByteArray): Int =
        runCatching { ExifInterface(bytes.inputStream()).rotationDegrees }.getOrDefault(0)

    /**
     * What to add for [file] stored as asset [sha256]: a picture centred at ([centerX],
     * [centerY]) mm, scaled down to fit the page, or a link for other files. Null if the
     * picture cannot be read.
     */
    fun draft(file: PickedFile, sha256: String, pageWidthMm: Float, centerX: Float, centerY: Float): ElementDraft? {
        val asset = Asset(sha256, file.mimeType)
        if (file.mimeType !in Asset.IMAGE_TYPES) {
            val w = minOf(FileLink.DEFAULT_WIDTH_MM, pageWidthMm)
            return FileLinkDraft(left(centerX, w, pageWidthMm), maxOf(0f, centerY - FileLink.DEFAULT_HEIGHT_MM / 2), w, FileLink.DEFAULT_HEIGHT_MM, asset, file.fileName)
        }
        val natural = naturalSizeMm(file.bytes, file.mimeType) ?: return null
        val scale = minOf(1f, MAX_PAGE_SHARE * pageWidthMm / natural.width)
        val w = natural.width * scale
        val h = natural.height * scale
        return ImageDraft(left(centerX, w, pageWidthMm), maxOf(0f, centerY - h / 2), w, h, asset)
    }

    private fun left(centerX: Float, width: Float, pageWidthMm: Float) =
        (centerX - width / 2).coerceIn(0f, maxOf(0f, pageWidthMm - width))
}
