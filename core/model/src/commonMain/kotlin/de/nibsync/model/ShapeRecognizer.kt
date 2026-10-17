package de.nibsync.model

/**
 * Result of shape recognition: [replacement] is added instead of the drawn stroke, and the
 * strokes in [replaces] (e.g. the shaft an arrow head was drawn onto) are removed with it.
 */
data class Recognition(
    val replacement: StrokeDraft,
    val replaces: Set<StrokeId> = emptySet(),
)

/**
 * Replaces hand-drawn geometry (lines, rectangles, ...) with clean shapes. The canvas asks for
 * it only when the user holds the pen still at the end of a stroke.
 */
fun interface ShapeRecognizer {
    /**
     * Returns a clean replacement for [draft], or null if no shape was recognised. [recent] are
     * the latest strokes of the document on the draft's layer, oldest first, for shapes made of
     * several strokes.
     */
    fun recognize(draft: StrokeDraft, recent: List<Stroke>): Recognition?

    companion object {
        val None: ShapeRecognizer = ShapeRecognizer { _, _ -> null }
    }
}
