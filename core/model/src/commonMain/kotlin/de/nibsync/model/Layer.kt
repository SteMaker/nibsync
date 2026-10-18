package de.nibsync.model

/**
 * Fixed drawing layers of a note page, listed back to front.
 *
 * Marker (highlighter) strokes live on [MARKER] so they are always drawn behind regular ink,
 * no matter in which order they were written. Text boxes sit between the two: highlighting
 * marks them from behind, handwritten notes go on top. [zIndex] is not persisted.
 */
enum class Layer(val zIndex: Int) {
    /** Page background pattern (lines, grid). Holds no strokes. */
    BACKGROUND(0),

    /** Highlighter strokes, drawn behind the ink. */
    MARKER(1),

    /** Text boxes. Holds no strokes. */
    TEXT(2),

    /** Regular handwriting. */
    INK(3);

    val holdsStrokes: Boolean get() = this == MARKER || this == INK
}
