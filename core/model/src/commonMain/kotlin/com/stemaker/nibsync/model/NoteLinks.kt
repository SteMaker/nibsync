package com.stemaker.nibsync.model

import com.stemaker.nibsync.model.geometry.Rect

/** Anchors and links to notes: page elements that connect documents. Both lie on [Layer.TEXT]. */
sealed interface LinkElement : PageElement {
    val x: Float
    val y: Float
    val widthMm: Float
    val heightMm: Float

    override val layer: Layer get() = Layer.TEXT
    override val bounds: Rect get() = Rect(x, y, x + widthMm, y + heightMm)

    companion object {
        const val HEIGHT_MM = 8f

        /** Label size: text height in mm, and an average character width used to size the element. */
        const val FONT_MM = HEIGHT_MM * 0.45f
        private const val CHAR_MM = FONT_MM * 0.56f
        const val MAX_WIDTH_MM = 120f

        /** Width for an icon and [label]: the label is drawn shortened if the real font is wider. */
        fun widthFor(label: String): Float = minOf(MAX_WIDTH_MM, HEIGHT_MM + 2f + label.length * CHAR_MM)
    }
}

/**
 * A named place in a document that [NoteLink]s can lead to; shown as a small flag with its
 * name. [anchorId] stays the same when the anchor is moved (remove + add with a fresh [id]),
 * so links keep working; a copy is a new anchor.
 */
data class Anchor(
    override val id: StrokeId,
    val anchorId: StrokeId,
    override val x: Float,
    override val y: Float,
    val name: String,
    override val createdAtEpochMs: Long,
    override val author: DeviceId,
) : LinkElement {
    override val widthMm: Float get() = LinkElement.widthFor(name)
    override val heightMm: Float get() = LinkElement.HEIGHT_MM
}

/**
 * A link to the document [target], or to the anchor [anchorId] in it. [title] and [anchorName]
 * are the names when the link was made; apps show the document's current title when they know it.
 */
data class NoteLink(
    override val id: StrokeId,
    override val x: Float,
    override val y: Float,
    val target: DocumentId,
    val anchorId: StrokeId?,
    val title: String,
    val anchorName: String?,
    override val createdAtEpochMs: Long,
    override val author: DeviceId,
) : LinkElement {
    override val widthMm: Float get() = LinkElement.widthFor(label(title))
    override val heightMm: Float get() = LinkElement.HEIGHT_MM

    /** "Title › Anchor", with the document's [currentTitle]. */
    fun label(currentTitle: String): String = if (anchorName == null) currentTitle else "$currentTitle › $anchorName"
}

/** A new anchor ([anchorId] null) or a moved one. */
data class AnchorDraft(val x: Float, val y: Float, val name: String, val anchorId: StrokeId? = null) : ElementDraft {
    override fun translated(dx: Float, dy: Float): AnchorDraft = copy(x = x + dx, y = y + dy)
}

data class NoteLinkDraft(
    val x: Float,
    val y: Float,
    val target: DocumentId,
    val anchorId: StrokeId?,
    val title: String,
    val anchorName: String?,
) : ElementDraft {
    override fun translated(dx: Float, dy: Float): NoteLinkDraft = copy(x = x + dx, y = y + dy)
}

/** What a link leads to: a document, and in it an anchor (null: the top of the document). */
data class LinkTarget(val document: DocumentId, val anchorId: StrokeId?)

val NoteLink.linkTarget: LinkTarget get() = LinkTarget(target, anchorId)
