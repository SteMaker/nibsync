package com.stemaker.nibsync.model

import com.stemaker.nibsync.model.geometry.Rect

/**
 * A file stored with a document (picture, PDF), named by the SHA-256 of its content: the same
 * content always gets the same name, so copies on several devices never conflict.
 */
data class Asset(val sha256: String, val mimeType: String) {
    init {
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid SHA-256 '$sha256'" }
        require(mimeType.isNotBlank()) { "mimeType must not be blank" }
    }

    companion object {
        const val PNG = "image/png"
        const val JPEG = "image/jpeg"
        const val SVG = "image/svg+xml"
        const val PDF = "application/pdf"

        /** A file of unknown type. */
        const val BINARY = "application/octet-stream"

        /** File types shown as pictures; everything else is a [FileLink]. */
        val IMAGE_TYPES = setOf(PNG, JPEG, SVG)
    }
}

/** A page element showing a stored file: a picture or a link to open the file. */
sealed interface FileElement : PageElement {
    val x: Float
    val y: Float
    val widthMm: Float
    val heightMm: Float
    val asset: Asset

    override val bounds: Rect get() = Rect(x, y, x + widthMm, y + heightMm)
}

/** A picture (PNG, JPEG, SVG) scaled to [widthMm] x [heightMm], top left at ([x], [y]) mm. */
data class ImageElement(
    override val id: StrokeId,
    override val x: Float,
    override val y: Float,
    override val widthMm: Float,
    override val heightMm: Float,
    override val asset: Asset,
    override val createdAtEpochMs: Long,
    override val author: DeviceId,
) : FileElement {
    init {
        require(widthMm > 0f && heightMm > 0f) { "An image needs a positive size" }
    }

    override val layer: Layer get() = Layer.IMAGE
}

/** A file of any type (e.g. a PDF) shown as a labelled link; tapping it opens the file in another app. */
data class FileLink(
    override val id: StrokeId,
    override val x: Float,
    override val y: Float,
    override val widthMm: Float,
    override val heightMm: Float,
    override val asset: Asset,
    val fileName: String,
    override val createdAtEpochMs: Long,
    override val author: DeviceId,
) : FileElement {
    init {
        require(widthMm > 0f && heightMm > 0f) { "A link needs a positive size" }
    }

    override val layer: Layer get() = Layer.TEXT

    /** Short type label on the link: "PDF", the file's extension (up to 4 letters) or "FILE". */
    val badge: String get() = badgeFor(asset.mimeType, fileName)

    companion object {
        fun badgeFor(mimeType: String, fileName: String): String {
            if (mimeType == Asset.PDF) return "PDF"
            val ext = fileName.substringAfterLast('.', "")
            return if (ext.length in 1..4 && ext.all { it.isLetterOrDigit() }) ext.uppercase() else "FILE"
        }

        const val DEFAULT_WIDTH_MM = 60f
        const val DEFAULT_HEIGHT_MM = 12f
    }
}

data class ImageDraft(val x: Float, val y: Float, val widthMm: Float, val heightMm: Float, val asset: Asset) : ElementDraft {
    override fun translated(dx: Float, dy: Float): ImageDraft = copy(x = x + dx, y = y + dy)
}

data class FileLinkDraft(
    val x: Float,
    val y: Float,
    val widthMm: Float,
    val heightMm: Float,
    val asset: Asset,
    val fileName: String,
) : ElementDraft {
    override fun translated(dx: Float, dy: Float): FileLinkDraft = copy(x = x + dx, y = y + dy)
}

/** Assets the drafts refer to (they must exist in a document before the drafts are added there). */
fun List<ElementDraft>.assets(): Set<Asset> = mapNotNullTo(LinkedHashSet()) {
    when (it) {
        is ImageDraft -> it.asset
        is FileLinkDraft -> it.asset
        else -> null
    }
}
