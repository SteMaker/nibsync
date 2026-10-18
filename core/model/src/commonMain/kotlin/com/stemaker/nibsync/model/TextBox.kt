package com.stemaker.nibsync.model

import com.stemaker.nibsync.model.geometry.Rect

/**
 * A box of typed, formatted text on the page. [x], [y] is the top left corner in mm; the
 * width is chosen by the user, the text wraps inside it. [heightMm] is the laid-out height
 * when the box was stored, so platform-neutral code (selection, page growth) knows its extent
 * without a text engine.
 */
data class TextBox(
    override val id: StrokeId,
    val x: Float,
    val y: Float,
    val widthMm: Float,
    val heightMm: Float,
    val text: RichText,
    override val createdAtEpochMs: Long,
    override val author: DeviceId,
) : PageElement {
    init {
        require(widthMm > 0f) { "widthMm must be positive" }
        require(heightMm >= 0f) { "heightMm must not be negative" }
    }

    override val layer: Layer get() = Layer.TEXT

    override val bounds: Rect get() = Rect(x, y, x + widthMm, y + heightMm)

    companion object {
        /** Boxes are never narrower than this. */
        const val MIN_WIDTH_MM = 10f
    }
}

data class TextBoxDraft(
    val x: Float,
    val y: Float,
    val widthMm: Float,
    val heightMm: Float,
    val text: RichText,
) : ElementDraft {
    override fun translated(dx: Float, dy: Float): TextBoxDraft = copy(x = x + dx, y = y + dy)
}
