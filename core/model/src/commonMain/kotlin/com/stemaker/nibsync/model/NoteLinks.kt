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

        /** Label text height of an element [heightMm] high. */
        fun fontFor(heightMm: Float): Float = heightMm * 0.45f
        const val MAX_WIDTH_MM = 120f

        /**
         * Width for an icon and [label] in a row [heightMm] high, from an average character
         * width; the label is drawn shortened if the real font is wider.
         */
        fun widthFor(label: String, heightMm: Float = HEIGHT_MM): Float =
            minOf(MAX_WIDTH_MM, heightMm * 1.25f + label.length * fontFor(heightMm) * 0.62f)
    }
}

/**
 * A named place in a document that [NoteLink]s can lead to; shown as a small flag, with its
 * name beside it while selected ([labelBounds]). [anchorId] stays the same when the anchor is
 * moved (remove + add with a fresh [id]), so links keep working; a copy is a new anchor.
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
    override val widthMm: Float get() = HEIGHT_MM
    override val heightMm: Float get() = HEIGHT_MM

    /** The flag and the name, as shown while the anchor is selected. */
    val labelBounds: Rect get() = Rect(x, y, x + maxOf(HEIGHT_MM, LinkElement.widthFor(name, HEIGHT_MM)), y + HEIGHT_MM)

    companion object {
        /** Smaller than a link: an anchor is only a marker. */
        const val HEIGHT_MM = LinkElement.HEIGHT_MM * 0.7f
    }
}

/** Where [this] is drawn when selected: an anchor shows its name then, so it is wider. */
val PageElement.selectedBounds: Rect get() = if (this is Anchor) labelBounds else bounds

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
