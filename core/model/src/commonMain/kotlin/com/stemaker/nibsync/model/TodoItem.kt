package com.stemaker.nibsync.model

import com.stemaker.nibsync.model.geometry.Rect

/** What a to-do says: typed text or handwriting. */
sealed interface TodoContent {
    val isEmpty: Boolean

    data class Text(val text: RichText) : TodoContent {
        override val isEmpty: Boolean get() = text.paragraphs.all { it.text.isBlank() }
    }

    /**
     * Handwritten strokes, with points relative to the top left of the content area (right of the
     * check circle), so moving the to-do does not touch them.
     */
    data class Ink(val strokes: List<Stroke>) : TodoContent {
        override val isEmpty: Boolean get() = strokes.isEmpty()

        /** Lower right corner of the strokes (mm, content coordinates), 0 for none. */
        val extent: Pair<Float, Float>
            get() = (strokes.maxOfOrNull { it.bounds.right } ?: 0f) to (strokes.maxOfOrNull { it.bounds.bottom } ?: 0f)
    }
}

/**
 * A task on the page: a frame of [widthMm] x [heightMm] with its top left at ([x], [y]) mm, a
 * check circle on the left and the [content] to its right. To-dos lie above everything else.
 *
 * Whether it is done is not part of the element: it is a register keyed by [taskId] in the
 * document (see `DocumentOp.SetTodoDone`), so moving or resizing the to-do (remove + add with a
 * fresh [id], same [taskId]) and ticking it off on another device never conflict.
 */
data class TodoItem(
    override val id: StrokeId,
    val taskId: StrokeId,
    val x: Float,
    val y: Float,
    val widthMm: Float,
    val heightMm: Float,
    val content: TodoContent,
    override val createdAtEpochMs: Long,
    override val author: DeviceId,
) : PageElement {
    init {
        require(widthMm > 0f && heightMm > 0f) { "A to-do needs a positive size" }
    }

    override val layer: Layer get() = Layer.TODO

    override val bounds: Rect get() = Rect(x, y, x + widthMm, y + heightMm)

    /** Where the check circle is (mm). */
    val checkBounds: Rect
        get() = Rect(x + PADDING_MM, y + PADDING_MM, x + PADDING_MM + CHECK_SIZE_MM, y + PADDING_MM + CHECK_SIZE_MM)

    /** Top left of the content area (mm); [TodoContent.Ink] points are relative to it. */
    val contentX: Float get() = x + CONTENT_OFFSET_MM
    val contentY: Float get() = y + PADDING_MM

    /** Width available to the content (mm). */
    val contentWidthMm: Float get() = (widthMm - CONTENT_OFFSET_MM - PADDING_MM).coerceAtLeast(1f)

    companion object {
        const val CHECK_SIZE_MM = 6f
        const val PADDING_MM = 2f
        const val CONTENT_OFFSET_MM = PADDING_MM + CHECK_SIZE_MM + PADDING_MM
        const val MIN_WIDTH_MM = CONTENT_OFFSET_MM + 10f
        const val MIN_HEIGHT_MM = CHECK_SIZE_MM + 2 * PADDING_MM
        const val DEFAULT_WIDTH_MM = 90f
        const val DEFAULT_HEIGHT_MM = 14f

        /** The smallest frame around [content] (frame size for a content area of that extent). */
        fun minSizeFor(contentWidthMm: Float, contentHeightMm: Float): Pair<Float, Float> =
            maxOf(MIN_WIDTH_MM, contentWidthMm + CONTENT_OFFSET_MM + PADDING_MM) to
                maxOf(MIN_HEIGHT_MM, contentHeightMm + 2 * PADDING_MM)
    }
}

/**
 * A to-do before it gets an id. [taskId] is kept when an existing to-do is moved or resized and
 * null for a new one (also for pasted copies, which are new tasks).
 */
data class TodoDraft(
    val x: Float,
    val y: Float,
    val widthMm: Float,
    val heightMm: Float,
    val content: TodoContent,
    val taskId: StrokeId? = null,
) : ElementDraft {
    override fun translated(dx: Float, dy: Float): TodoDraft = copy(x = x + dx, y = y + dy)
}
