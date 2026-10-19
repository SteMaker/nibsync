package com.stemaker.nibsync.text

import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.model.Quantization
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TodoContent
import com.stemaker.nibsync.model.TodoDraft
import com.stemaker.nibsync.model.TodoItem
import com.stemaker.nibsync.model.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The to-do being edited: its frame, its stored ids (null while a new one is still empty) and
 * its content, typed ([text] set) or handwritten ([ink]). [key] identifies the editing session.
 */
data class TodoEdit(
    val key: Long,
    val itemId: StrokeId?,
    val taskId: StrokeId?,
    val x: Float,
    val y: Float,
    val widthMm: Float,
    val heightMm: Float,
    val text: TextEditController?,
    val ink: List<Stroke>,
) {
    val isInk: Boolean get() = text == null

    val frame: Rect get() = Rect(x, y, x + widthMm, y + heightMm)

    /** The to-do as it looks right now, for drawing it while it is edited. */
    fun preview(author: com.stemaker.nibsync.model.DeviceId): TodoItem = TodoItem(
        id = itemId ?: PREVIEW_ID,
        taskId = taskId ?: PREVIEW_ID,
        x = x,
        y = y,
        widthMm = widthMm,
        heightMm = heightMm,
        content = text?.let { TodoContent.Text(it.text) } ?: TodoContent.Ink(ink),
        createdAtEpochMs = 0,
        author = author,
    )

    companion object {
        val PREVIEW_ID = StrokeId("todo-preview")
    }
}

/**
 * Editing one to-do of a document at a time, the same way on every platform (like
 * [TextBoxEditing]): every save of a change replaces the to-do with the same task (one undo
 * step), a to-do left empty is removed when editing ends. The frame never gets smaller than
 * its content: typed text wraps to the width, handwriting keeps its size.
 */
class TodoEditing(private val session: DocumentSession) {
    private val _editing = MutableStateFlow<TodoEdit?>(null)
    val editing: StateFlow<TodoEdit?> = _editing.asStateFlow()

    /** Height in mm of a text at a given width; the platform sets its real text layout. */
    var measureText: (RichText, Float) -> Float = { text, _ -> text.paragraphs.size * RichText.DEFAULT_SIZE_PT * RichText.MM_PER_PT * 1.4f }

    private var saved: TodoDraft? = null
    private var key = 0L

    /** Starts editing [item] (ends editing another one first). */
    fun open(item: TodoItem) {
        finish()
        saved = item.toDraft()
        val content = item.content
        _editing.value = TodoEdit(
            ++key, item.id, item.taskId, item.x, item.y, item.widthMm, item.heightMm,
            text = (content as? TodoContent.Text)?.let { TextEditController.create(it.text) },
            ink = (content as? TodoContent.Ink)?.strokes.orEmpty(),
        )
    }

    /** Starts a new, empty to-do at ([x], [y]) mm, handwritten or typed; stored once it has content. */
    fun startNew(x: Float, y: Float, ink: Boolean, pageWidthMm: Float) {
        finish()
        saved = null
        val width = minOf(TodoItem.DEFAULT_WIDTH_MM, maxOf(TodoItem.MIN_WIDTH_MM, pageWidthMm - x - MARGIN_MM))
        val left = x.coerceIn(0f, maxOf(0f, pageWidthMm - width))
        _editing.value = TodoEdit(
            ++key, null, null, left, maxOf(0f, y), width, TodoItem.DEFAULT_HEIGHT_MM,
            text = if (ink) null else TextEditController.create(RichText.EMPTY),
            ink = emptyList(),
        )
    }

    /** Whether ([x], [y]) mm is inside the edited to-do. */
    fun contains(x: Float, y: Float): Boolean = _editing.value?.frame?.contains(x, y) == true

    /**
     * Adds a handwritten stroke (page coordinates) to the edited to-do and stores it. The frame
     * grows if the stroke reaches beyond it.
     */
    fun addInk(draft: StrokeDraft) {
        val e = _editing.value ?: return
        if (!e.isInk) return
        val cx = e.x + TodoItem.CONTENT_OFFSET_MM
        val cy = e.y + TodoItem.PADDING_MM
        val relative = draft.copy(points = draft.points.map { it.copy(x = it.x - cx, y = it.y - cy) })
        val ink = e.ink + session.todoInk(listOf(relative)).strokes
        _editing.value = fitted(e.copy(ink = ink))
        save()
    }

    /** Stores the edited to-do if it changed. Called while typing and when done. */
    fun save() {
        val e = _editing.value?.let(::fitted) ?: return
        _editing.value = e
        val content = content(e)
        if (content.isEmpty && e.itemId == null) return
        fun q(mm: Float) = Quantization.quantizePosition(mm)
        val draft = TodoDraft(q(e.x), q(e.y), q(e.widthMm), q(e.heightMm), content, e.taskId)
        if (draft == saved && e.itemId != null && e.itemId in session.state.value) return
        val item = session.replaceElements(listOfNotNull(e.itemId), listOf(draft)).single() as TodoItem
        saved = draft.copy(taskId = item.taskId)
        _editing.value = e.copy(itemId = item.id, taskId = item.taskId)
    }

    /** Ends editing; a to-do left empty is removed. Returns false if nothing was being edited. */
    fun finish(): Boolean {
        val e = _editing.value ?: return false
        if (content(e).isEmpty) {
            e.itemId?.let { session.removeStrokes(listOf(it)) }
        } else {
            save()
        }
        _editing.value = null
        saved = null
        return true
    }

    /** Deletes the edited to-do. Returns false if nothing was being edited. */
    fun delete(): Boolean {
        val e = _editing.value ?: return false
        e.itemId?.let { session.removeStrokes(listOf(it)) }
        _editing.value = null
        saved = null
        return true
    }

    /** Moves the edited to-do while its handle is dragged; [save] stores the result. */
    fun move(dx: Float, dy: Float) =
        _editing.update { it?.copy(x = maxOf(0f, it.x + dx), y = maxOf(0f, it.y + dy)) }

    /** Changes the frame while its corner is dragged; it does not get smaller than the content. */
    fun resize(dw: Float, dh: Float) =
        _editing.update { it?.let { e -> fitted(e.copy(widthMm = e.widthMm + dw, heightMm = e.heightMm + dh)) } }

    private fun content(e: TodoEdit): TodoContent = e.text?.let { TodoContent.Text(it.text) } ?: TodoContent.Ink(e.ink)

    /** [e] with its frame at least as large as its content needs. */
    private fun fitted(e: TodoEdit): TodoEdit {
        val (minW, minH) = when (val text = e.text) {
            null -> TodoContent.Ink(e.ink).extent.let { (w, h) -> TodoItem.minSizeFor(w, h) }
            else -> {
                val contentWidth = maxOf(1f, e.widthMm - TodoItem.CONTENT_OFFSET_MM - TodoItem.PADDING_MM)
                TodoItem.minSizeFor(0f, measureText(text.text, contentWidth))
            }
        }
        return e.copy(widthMm = maxOf(minW, e.widthMm), heightMm = maxOf(minH, e.heightMm))
    }

    private fun TodoItem.toDraft() = TodoDraft(x, y, widthMm, heightMm, content, taskId)

    private companion object {
        /** Space kept to the right page edge by new to-dos. */
        const val MARGIN_MM = 5f
    }
}
