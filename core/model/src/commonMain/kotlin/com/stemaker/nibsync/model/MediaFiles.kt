package com.stemaker.nibsync.model

/** A file chosen for inserting into a page. */
class PickedFile(val bytes: ByteArray, val mimeType: String, val fileName: String)

data class SizeMm(val width: Float, val height: Float)

/**
 * Recognising inserted files and deciding where and how big they appear on the page; the
 * same on every platform. Reading a picture's pixel size is up to the platform.
 */
object MediaFiles {
    /** Larger files are refused. */
    const val MAX_BYTES = 50L * 1024 * 1024

    /** Pixel pictures get the size they would have printed at this resolution ... */
    const val PRINT_DPI = 150f

    /** ... but at most this share of the page width. */
    const val MAX_PAGE_SHARE = 0.8f

    /** SVG lengths are CSS pixels. */
    const val SVG_DPI = 96f
    const val MM_PER_INCH = 25.4f

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

    /**
     * The type any file is stored with: a picture or PDF recognised by [detectType], else the
     * declared type, else one guessed from the file name, else [Asset.BINARY]. Files that are not
     * pictures become [FileLink]s, which the system opens with a fitting program.
     */
    fun fileType(bytes: ByteArray, declaredType: String?, fileName: String?): String =
        detectType(bytes, declaredType, fileName)
            ?: declaredType?.lowercase()?.takeIf { it.matches(MIME_TYPE) && it !in Asset.IMAGE_TYPES && it != Asset.BINARY }
            ?: fileName?.let(::typeForName)
            ?: Asset.BINARY

    /** The usual type of a file named [fileName], by its extension; null if unknown. */
    fun typeForName(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return EXTENSION_TYPES[ext]?.takeIf { it !in Asset.IMAGE_TYPES }
    }

    private val MIME_TYPE = Regex("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")

    private val EXTENSION_TYPES = mapOf(
        "pdf" to Asset.PDF,
        "txt" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "html" to "text/html",
        "htm" to "text/html",
        "xml" to "text/xml",
        "json" to "application/json",
        "rtf" to "application/rtf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "epub" to "application/epub+zip",
        "zip" to "application/zip",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "ogg" to "audio/ogg",
        "wav" to "audio/wav",
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "ics" to "text/calendar",
        "vcf" to "text/vcard",
        "eml" to "message/rfc822",
    )

    private fun looksLikeSvg(bytes: ByteArray, declaredType: String?, fileName: String?): Boolean {
        val head = bytes.copyOf(minOf(bytes.size, 2048)).decodeToString()
        return "<svg" in head || ((declaredType == Asset.SVG || fileName?.endsWith(".svg", ignoreCase = true) == true) && "<" in head)
    }

    /** Size of a pixel picture of [widthPx] x [heightPx] printed at [PRINT_DPI]. */
    fun printSize(widthPx: Int, heightPx: Int) = SizeMm(widthPx * MM_PER_INCH / PRINT_DPI, heightPx * MM_PER_INCH / PRINT_DPI)

    /** Size of an SVG of the given CSS pixel size. */
    fun svgSize(widthPx: Float, heightPx: Float) = SizeMm(widthPx * MM_PER_INCH / SVG_DPI, heightPx * MM_PER_INCH / SVG_DPI)

    /**
     * What to add for [file] stored as asset [sha256]: a picture of [natural] size centred at
     * ([centerX], [centerY]) mm, scaled down to fit the page, or a link for other files. Null
     * for a picture whose size could not be read.
     */
    fun draft(file: PickedFile, sha256: String, natural: SizeMm?, pageWidthMm: Float, centerX: Float, centerY: Float): ElementDraft? {
        val asset = Asset(sha256, file.mimeType)
        if (file.mimeType !in Asset.IMAGE_TYPES) {
            val w = minOf(FileLink.DEFAULT_WIDTH_MM, pageWidthMm)
            return FileLinkDraft(left(centerX, w, pageWidthMm), maxOf(0f, centerY - FileLink.DEFAULT_HEIGHT_MM / 2), w, FileLink.DEFAULT_HEIGHT_MM, asset, file.fileName)
        }
        natural ?: return null
        val scale = minOf(1f, MAX_PAGE_SHARE * pageWidthMm / natural.width)
        val w = natural.width * scale
        val h = natural.height * scale
        return ImageDraft(left(centerX, w, pageWidthMm), maxOf(0f, centerY - h / 2), w, h, asset)
    }

    private fun left(centerX: Float, width: Float, pageWidthMm: Float) =
        (centerX - width / 2).coerceIn(0f, maxOf(0f, pageWidthMm - width))
}
