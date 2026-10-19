package com.stemaker.nibsync.model

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

    /** Inserted pictures, so they can be highlighted and written on. Holds no strokes. */
    IMAGE(1),

    /** Highlighter strokes, drawn behind the ink. */
    MARKER(2),

    /** Text boxes and file links. Holds no strokes. */
    TEXT(3),

    /** Regular handwriting. */
    INK(4),

    /** To-dos, above everything so they can always be ticked off. Holds no strokes. */
    TODO(5);

    val holdsStrokes: Boolean get() = this == MARKER || this == INK
}
