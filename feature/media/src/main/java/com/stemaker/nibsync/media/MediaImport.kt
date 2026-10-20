package com.stemaker.nibsync.media

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.caverock.androidsvg.SVG
import com.stemaker.nibsync.model.Asset
import com.stemaker.nibsync.model.ElementDraft
import com.stemaker.nibsync.model.MediaFiles
import com.stemaker.nibsync.model.PickedFile
import com.stemaker.nibsync.model.SizeMm

/**
 * Reading inserted pictures on Android (size, EXIF rotation); type detection and placement
 * are the platform-neutral [MediaFiles].
 */
object MediaImport {
    const val MAX_BYTES = MediaFiles.MAX_BYTES

    fun detectType(bytes: ByteArray, declaredType: String?, fileName: String?): String? =
        MediaFiles.detectType(bytes, declaredType, fileName)

    /** See [MediaFiles.fileType]: the type any file is stored with. */
    fun fileType(bytes: ByteArray, declaredType: String?, fileName: String?): String =
        MediaFiles.fileType(bytes, declaredType, fileName)

    /** Size a picture would naturally have on paper, or null if it cannot be read. */
    fun naturalSizeMm(bytes: ByteArray, mimeType: String): SizeMm? = when (mimeType) {
        Asset.PNG, Asset.JPEG -> {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                null
            } else {
                val turned = mimeType == Asset.JPEG && exifRotation(bytes) % 180 != 0
                if (turned) MediaFiles.printSize(options.outHeight, options.outWidth) else MediaFiles.printSize(options.outWidth, options.outHeight)
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
            if (w <= 0f || h <= 0f) SizeMm(100f, 100f) else MediaFiles.svgSize(w, h)
        }.getOrNull()
        else -> null
    }

    /** Clockwise rotation in degrees stored in a JPEG's EXIF data (camera photos). */
    fun exifRotation(bytes: ByteArray): Int =
        runCatching { ExifInterface(bytes.inputStream()).rotationDegrees }.getOrDefault(0)

    /** See [MediaFiles.draft]; the size is read here. */
    fun draft(file: PickedFile, sha256: String, pageWidthMm: Float, centerX: Float, centerY: Float): ElementDraft? =
        MediaFiles.draft(file, sha256, naturalSizeMm(file.bytes, file.mimeType), pageWidthMm, centerX, centerY)
}
