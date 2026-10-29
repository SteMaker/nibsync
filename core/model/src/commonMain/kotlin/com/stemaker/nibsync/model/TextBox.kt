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

        /** Width of a new box made by a click, unless the page ends earlier. */
        const val DEFAULT_WIDTH_MM = 120f

        /** Free space kept to the right page edge. */
        const val MARGIN_MM = 5f

        /** New boxes are at least this wide, moved left if needed. */
        const val MIN_NEW_WIDTH_MM = 30f

        /** A new box starts this far above the click, so the first line is where one clicked. */
        const val FIRST_LINE_OFFSET_MM = 2.5f

        /**
         * Left, top and width of a new box for a click at ([x], [y]) mm on a page [pageWidthMm]
         * wide: [DEFAULT_WIDTH_MM] wide or up to the margin, moved left near the right edge.
         */
        fun placeNew(x: Float, y: Float, pageWidthMm: Float): Triple<Float, Float, Float> {
            val top = maxOf(0f, y - FIRST_LINE_OFFSET_MM)
            val width = minOf(DEFAULT_WIDTH_MM, pageWidthMm - MARGIN_MM - x)
            if (width >= MIN_NEW_WIDTH_MM) return Triple(maxOf(0f, x), top, width)
            // Too close to the right edge: the box ends at the margin instead.
            val w = maxOf(MIN_WIDTH_MM, minOf(MIN_NEW_WIDTH_MM, pageWidthMm - MARGIN_MM))
            return Triple(maxOf(0f, pageWidthMm - MARGIN_MM - w), top, w)
        }
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
