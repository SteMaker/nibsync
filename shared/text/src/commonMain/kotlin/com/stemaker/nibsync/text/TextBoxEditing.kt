package com.stemaker.nibsync.text

import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.model.Quantization
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TextBoxDraft
import com.stemaker.nibsync.document.select.Reflow
import com.stemaker.nibsync.model.geometry.Rect
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

/** While typing: elements drawn shifted by [dy] mm, as the text flow will move them on saving. */
data class FlowPreview(val ids: Set<StrokeId>, val dy: Float)

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
        val old = e.boxId?.let { session.state.value.element(it) }
        // What is below moves with the box's height (text flow).
        val box = session.edit {
            val added = replaceElements(listOfNotNull(e.boxId), listOf(draft)).single()
            reflow(setOfNotNull(e.boxId, added.id), old?.bounds, added.bounds)
            added
        }
        saved = draft
        _editing.value = e.copy(boxId = box.id)
    }

    /** Ends editing; a box left empty is removed. Returns false if nothing was being edited. */
    fun finish(): Boolean {
        val e = _editing.value ?: return false
        if (e.controller.text.isBlank) {
            e.boxId?.let(::removeFlowing)
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
        e.boxId?.let(::removeFlowing)
        _editing.value = null
        saved = null
        return true
    }

    /** Ends editing without storing anything (the caller stored the content its own way). */
    fun discard() {
        _editing.value = null
        saved = null
    }

    /** Removes the box [id]; what was below it moves up (text flow). */
    private fun removeFlowing(id: StrokeId) {
        val old = session.state.value.element(id) ?: return
        session.edit {
            replaceElements(listOf(id), emptyList())
            reflow(setOf(id), old.bounds, null)
        }
    }

    /** What the text flow would move, and how far, if the box were stored as typed now. */
    fun flowPreview(): FlowPreview? {
        val e = _editing.value ?: return null
        val state = session.state.value
        val old = e.boxId?.let { state.element(it) }
        val after = Rect(e.x, e.y, e.x + e.widthMm, e.y + measureText(e.controller.text, e.widthMm))
        val (moving, dy) = Reflow.plan(state.elements, setOfNotNull(e.boxId), old?.bounds, after)
        return if (moving.isEmpty() || dy == 0f) null else FlowPreview(moving.mapTo(HashSet()) { it.id }, dy)
    }

    /** Moves the edited box while its handle is dragged; [save] stores the result. */
    fun move(dx: Float, dy: Float) =
        _editing.update { it?.copy(x = maxOf(0f, it.x + dx), y = maxOf(0f, it.y + dy)) }

    /** Changes the width of the edited box while its edge is dragged. */
    fun resize(dw: Float) =
        _editing.update { it?.copy(widthMm = maxOf(TextBox.MIN_WIDTH_MM, it.widthMm + dw)) }
}
