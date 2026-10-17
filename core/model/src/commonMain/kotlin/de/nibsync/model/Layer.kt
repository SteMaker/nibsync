package de.nibsync.model

/**
 * Fixed drawing layers of a note page, listed back to front.
 *
 * Marker (highlighter) strokes live on [MARKER] so they are always drawn behind regular ink,
 * no matter in which order they were written.
 */
enum class Layer(val zIndex: Int) {
    /** Page background pattern (lines, grid). Holds no strokes. */
    BACKGROUND(0),

    /** Highlighter strokes, drawn behind the ink. */
    MARKER(1),

    /** Regular handwriting. */
    INK(2);

    val holdsStrokes: Boolean get() = this != BACKGROUND
}
