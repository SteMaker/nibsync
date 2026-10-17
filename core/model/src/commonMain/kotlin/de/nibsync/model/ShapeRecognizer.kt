package de.nibsync.model

/**
 * Replaces hand-drawn geometry (lines, rectangles, ...) with clean shapes. The canvas asks for
 * it only when the user holds the pen still at the end of a stroke.
 */
fun interface ShapeRecognizer {
    /** Returns a clean replacement for [draft], or null if no shape was recognised. */
    fun recognize(draft: StrokeDraft): StrokeDraft?

    companion object {
        val None: ShapeRecognizer = ShapeRecognizer { null }
    }
}
