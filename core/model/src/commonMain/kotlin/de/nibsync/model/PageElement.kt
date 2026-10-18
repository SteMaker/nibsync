package de.nibsync.model

import de.nibsync.model.geometry.Rect

/**
 * Something placed on a page: a handwritten [Stroke] or a [TextBox]. Elements are immutable;
 * changing one means removing it and adding a new element with a fresh id.
 */
sealed interface PageElement {
    val id: StrokeId
    val layer: Layer

    /** Area covered on the page, in mm. */
    val bounds: Rect
    val createdAtEpochMs: Long
    val author: DeviceId
}

/** The same element under another id (used when undo restores a removed element). */
fun PageElement.withId(id: StrokeId): PageElement = when (this) {
    is Stroke -> copy(id = id)
    is TextBox -> copy(id = id)
}

/** An element as the user made it, before it gets an id and a place in a document. */
sealed interface ElementDraft {
    /** The draft moved by ([dx], [dy]) mm. */
    fun translated(dx: Float, dy: Float): ElementDraft
}

/** The element as a draft for a new element of the same content. */
fun PageElement.toDraft(): ElementDraft = when (this) {
    is Stroke -> StrokeDraft(brush, tool, points, layer)
    is TextBox -> TextBoxDraft(x, y, widthMm, heightMm, text)
}
