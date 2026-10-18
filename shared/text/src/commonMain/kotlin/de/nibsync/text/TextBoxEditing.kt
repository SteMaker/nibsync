package de.nibsync.text

import de.nibsync.document.DocumentSession
import de.nibsync.model.Quantization
import de.nibsync.model.RichText
import de.nibsync.model.StrokeId
import de.nibsync.model.TextBox
import de.nibsync.model.TextBoxDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The text box being edited: where it is, its content in [controller], and [boxId] once it
 * has been stored (null for a new box that is still empty). [key] identifies the editing
 * session, i.e. one controller.
 */
data class TextEditing(
    val key: Long,
    val boxId: StrokeId?,
    val x: Float,
    val y: Float,
    val widthMm: Float,
    val controller: TextEditController,
)

/**
 * Editing one text box of a document at a time, the same way on every platform: every save of
 * a change replaces the box (one undo step), unchanged saves are skipped, a box left empty is
 * removed when editing ends.
 */
class TextBoxEditing(private val session: DocumentSession) {
    private val _editing = MutableStateFlow<TextEditing?>(null)

    /** The text box being edited, or null. */
    val editing: StateFlow<TextEditing?> = _editing.asStateFlow()

    /**
     * Height in mm of a text in a box of the given width. The platform sets its real text
     * layout; the default is a rough estimate for tests without one.
     */
    var measureText: (RichText, Float) -> Float = { text, _ -> text.paragraphs.size * RichText.DEFAULT_SIZE_PT * RichText.MM_PER_PT * 1.4f }

    /** What was stored last for the edited box, to skip saves without changes. */
    private var saved: TextBoxDraft? = null
    private var key = 0L

    /** Starts editing [box] (ends editing another box first). */
    fun open(box: TextBox) {
        finish()
        saved = TextBoxDraft(box.x, box.y, box.widthMm, box.heightMm, box.text)
        start(box.id, box.x, box.y, box.widthMm, box.text)
    }

    /** Starts a new, empty box; it is stored once it has text. */
    fun startNew(x: Float, y: Float, widthMm: Float) {
        finish()
        saved = null
        start(null, x, y, maxOf(TextBox.MIN_WIDTH_MM, widthMm), RichText.EMPTY)
    }

    private fun start(boxId: StrokeId?, x: Float, y: Float, widthMm: Float, text: RichText) {
        _editing.value = TextEditing(++key, boxId, x, y, widthMm, TextEditController.create(text))
    }

    /** Stores the edited box if it changed. Called while typing and when done. */
    fun save() {
        val e = _editing.value ?: return
        val text = e.controller.text
        if (text.isBlank && e.boxId == null) return
        // On the storage grid, so an unchanged box compares equal to what was loaded.
        fun q(mm: Float) = Quantization.quantizePosition(mm)
        val draft = TextBoxDraft(q(e.x), q(e.y), q(e.widthMm), q(measureText(text, e.widthMm)), text)
        if (draft == saved && e.boxId != null && e.boxId in session.state.value) return
        val box = session.replaceElements(listOfNotNull(e.boxId), listOf(draft)).single()
        saved = draft
        _editing.value = e.copy(boxId = box.id)
    }

    /** Ends editing; a box left empty is removed. Returns false if nothing was being edited. */
    fun finish(): Boolean {
        val e = _editing.value ?: return false
        if (e.controller.text.isBlank) {
            e.boxId?.let { session.removeStrokes(listOf(it)) }
        } else {
            save()
        }
        _editing.value = null
        saved = null
        return true
    }

    /** Deletes the edited box. Returns false if nothing was being edited. */
    fun delete(): Boolean {
        val e = _editing.value ?: return false
        e.boxId?.let { session.removeStrokes(listOf(it)) }
        _editing.value = null
        saved = null
        return true
    }

    /** Moves the edited box while its handle is dragged; [save] stores the result. */
    fun move(dx: Float, dy: Float) =
        _editing.update { it?.copy(x = maxOf(0f, it.x + dx), y = maxOf(0f, it.y + dy)) }

    /** Changes the width of the edited box while its edge is dragged. */
    fun resize(dw: Float) =
        _editing.update { it?.copy(widthMm = maxOf(TextBox.MIN_WIDTH_MM, it.widthMm + dw)) }
}
