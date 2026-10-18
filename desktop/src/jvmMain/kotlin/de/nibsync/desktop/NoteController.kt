package de.nibsync.desktop

import de.nibsync.document.DocumentSession
import de.nibsync.model.TextBox
import de.nibsync.text.TextBoxEditing

/**
 * What clicks and keys do with the open document on the PC, where typing comes first: a
 * click on free paper starts a text box there, a click on a text box opens it.
 */
class NoteController(val session: DocumentSession, measureText: (de.nibsync.model.RichText, Float) -> Float) {
    val text = TextBoxEditing(session).also { it.measureText = measureText }

    /** The topmost text box at ([x], [y]) mm, or null. */
    fun textBoxAt(x: Float, y: Float): TextBox? =
        session.state.value.textBoxes.lastOrNull { it.bounds.contains(x, y) }

    /**
     * A click at ([x], [y]) mm on a page [pageWidthMm] wide. Opens the text box there, or
     * starts a new one whose first line is at the click. Clicks beside the page only end
     * editing.
     */
    fun clickAt(x: Float, y: Float, pageWidthMm: Float) {
        val hit = textBoxAt(x, y)
        if (hit != null) {
            if (text.editing.value?.boxId != hit.id) text.open(hit)
            return
        }
        if (x < 0f || x > pageWidthMm || y < 0f) {
            text.finish()
            return
        }
        val width = minOf(DEFAULT_WIDTH_MM, pageWidthMm - MARGIN_MM - x)
        if (width >= MIN_NEW_WIDTH_MM) {
            text.startNew(x, maxOf(0f, y - FIRST_LINE_OFFSET_MM), width)
        } else {
            // Too close to the right edge: the box ends at the margin instead.
            val w = minOf(MIN_NEW_WIDTH_MM, pageWidthMm - MARGIN_MM)
            text.startNew(maxOf(0f, pageWidthMm - MARGIN_MM - w), maxOf(0f, y - FIRST_LINE_OFFSET_MM), w)
        }
    }

    /** Escape: stop editing, keep the text. */
    fun endEditing() {
        text.finish()
    }

    fun undo(): Boolean {
        text.finish()
        return session.undo()
    }

    fun redo(): Boolean {
        text.finish()
        return session.redo()
    }

    companion object {
        /** Width of a new box, unless the page ends earlier. */
        const val DEFAULT_WIDTH_MM = 120f

        /** Free space kept to the right page edge. */
        const val MARGIN_MM = 5f

        /** New boxes are at least this wide, moved left if needed. */
        const val MIN_NEW_WIDTH_MM = 30f

        /** A new box starts this far above the click, so the first line is where one clicked. */
        const val FIRST_LINE_OFFSET_MM = 2.5f
    }
}
