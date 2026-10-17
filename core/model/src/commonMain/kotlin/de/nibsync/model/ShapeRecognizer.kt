package de.nibsync.model

/**
 * Extension point for replacing hand-drawn geometry (lines, rectangles, ...) with clean shapes.
 * Implementations will be added in a later step; the canvas already routes finished drafts
 * through this interface.
 */
fun interface ShapeRecognizer {
    /** Returns a cleaned-up replacement for [draft], or [draft] itself if nothing was recognised. */
    fun recognize(draft: StrokeDraft): StrokeDraft

    companion object {
        val None: ShapeRecognizer = ShapeRecognizer { it }
    }
}
