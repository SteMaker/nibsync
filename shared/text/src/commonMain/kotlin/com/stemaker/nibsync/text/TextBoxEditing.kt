package com.stemaker.nibsync.text

import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.model.BlockDraft
import com.stemaker.nibsync.model.FlowBlock
import com.stemaker.nibsync.model.FlowSlot
import com.stemaker.nibsync.model.FractionalIndex
import com.stemaker.nibsync.model.IdGenerator
import com.stemaker.nibsync.model.Quantization
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TextBoxDraft
import com.stemaker.nibsync.model.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The text box being edited: where it is, its content in [controller], and [boxId] once it
 * has been stored (null for a new box that is still empty). [key] identifies the editing
 * session, i.e. one controller. [slot] is its place in a flow (null: a new flow once stored),
 * [movedX] and [movedY] how far its handle was dragged since the last save.
 */
data class TextEditing(
    val key: Long,
    val boxId: StrokeId?,
    val x: Float,
    val y: Float,
    val widthMm: Float,
    val controller: TextEditController,
    val slot: FlowSlot? = null,
    val movedX: Float = 0f,
    val movedY: Float = 0f,
)

/** While typing: elements drawn shifted by [dy] mm, as the text flow will move them on saving. */
data class FlowPreview(val ids: Set<StrokeId>, val dy: Float)

/**
 * Editing one text box of a document at a time, the same way on every platform: every save of
 * a change replaces the box (one undo step), unchanged saves are skipped, a box left empty is
 * removed when editing ends.
 */
class TextBoxEditing(private val session: DocumentSession, private val ids: IdGenerator = IdGenerator()) {
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
        saved = TextBoxDraft(q(box.x), q(box.y), box.widthMm, box.heightMm, box.text, box.slot)
        start(box.id, box.x, box.y, box.widthMm, box.text, box.slot)
    }

    /** Starts a new, empty box, in the place [slot] of a flow if given; it is stored once it has text. */
    fun startNew(x: Float, y: Float, widthMm: Float, slot: FlowSlot? = null) {
        finish()
        saved = null
        start(null, x, y, maxOf(TextBox.MIN_WIDTH_MM, widthMm), RichText.EMPTY, slot)
    }

    private fun start(boxId: StrokeId?, x: Float, y: Float, widthMm: Float, text: RichText, slot: FlowSlot?) {
        _editing.value = TextEditing(++key, boxId, x, y, widthMm, TextEditController.create(text), slot)
    }

    // On the storage grid, so an unchanged box compares equal to what was loaded.
    private fun q(mm: Float) = Quantization.quantizePosition(mm)

    /** Stores the edited box if it changed. Called while typing and when done. */
    fun save() {
        val e = _editing.value ?: return
        val text = e.controller.text
        if (text.isBlank && e.boxId == null) return
        val slot = e.slot ?: FlowSlot(ids.newStrokeId(), FlowSlot.FIRST)
        val draft = TextBoxDraft(q(e.x), q(e.y), q(e.widthMm), q(measureText(text, e.widthMm)), text, slot)
        if (draft == saved && e.boxId != null && e.boxId in session.state.value) return
        // What is below the flow moves with its height.
        val box = session.edit {
            val added = flowing(slot.flowId) { replaceElements(listOfNotNull(e.boxId), listOf(draft)).single() }
            if (e.boxId != null && (e.movedX != 0f || e.movedY != 0f)) dragFlow(slot.flowId, e.movedX, e.movedY)
            state.element(added.id) as TextBox
        }
        saved = draft.copy(x = q(box.x), y = q(box.y))
        _editing.value = e.copy(boxId = box.id, x = box.x, y = box.y, slot = slot, movedX = 0f, movedY = 0f)
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

    /** Removes the box [id]; what was below it moves up, text around it is joined. */
    private fun removeFlowing(id: StrokeId) = session.edit { removeBlocks(listOf(id), measureText, pull = true) }

    /**
     * Puts [blocks] at the cursor of the open box: the text before the cursor stays in the box, the
     * blocks come right below it, the rest of the text in a new box under them, all in one flow, and
     * what is below moves down; one undo step. Ends editing and returns the blocks, none if no box is open.
     */
    fun insertBlocksAtCursor(blocks: List<BlockDraft>): List<FlowBlock> {
        val e = _editing.value ?: return emptyList()
        val (before, after) = e.controller.splitAtCursor()
        discard()
        val old = e.boxId?.let { session.state.value.element(it) } as? TextBox
        val flowId = (old?.slot ?: e.slot)?.flowId ?: ids.newStrokeId()
        var order = (old?.slot ?: e.slot)?.order
        val high = session.state.value.flow(flowId)?.blocks?.firstOrNull { b -> order.let { it == null || b.slot.order > it } }?.slot?.order
        fun slot() = FlowSlot(flowId, FractionalIndex.between(order, high).also { order = it })
        fun box(text: RichText, slot: FlowSlot) = TextBoxDraft(e.x, e.y, e.widthMm, measureText(text, e.widthMm), text, slot)
        val first = if (before.isBlank) null else box(before, old?.slot ?: slot())
        val placed = blocks.map { b -> b.fittedTo(e.widthMm).let { it.translated(e.x - it.x, e.y - it.y) }.inSlot(slot()) }
        val last = if (after.isBlank) null else box(after, slot())
        return session.edit {
            val added = flowing(flowId) { replaceElements(listOfNotNull(e.boxId), listOfNotNull(first) + placed + listOfNotNull(last)) }
            added.subList(if (first == null) 0 else 1, (if (first == null) 0 else 1) + placed.size).map { state.element(it.id) as FlowBlock }
        }
    }

    /** What moves, and how far, if the box were stored as typed now. */
    fun flowPreview(): FlowPreview? {
        val e = _editing.value ?: return null
        val state = session.state.value
        val after = Rect(e.x, e.y, e.x + e.widthMm, e.y + measureText(e.controller.text, e.widthMm))
        return state.flowPreview(e.boxId?.let { state.element(it) } as? FlowBlock, e.slot?.flowId, after)
    }

    /** Moves the edited box while its handle is dragged; [save] stores the result. */
    fun move(dx: Float, dy: Float) = _editing.update {
        it?.let { e ->
            val x = maxOf(0f, e.x + dx)
            val y = maxOf(0f, e.y + dy)
            e.copy(x = x, y = y, movedX = e.movedX + x - e.x, movedY = e.movedY + y - e.y)
        }
    }

    /** Changes the width of the edited box while its edge is dragged. */
    fun resize(dw: Float) =
        _editing.update { it?.copy(widthMm = maxOf(TextBox.MIN_WIDTH_MM, it.widthMm + dw)) }
}
