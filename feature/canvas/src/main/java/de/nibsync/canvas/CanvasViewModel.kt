package de.nibsync.canvas

import androidx.lifecycle.ViewModel
import de.nibsync.document.DocumentSession
import de.nibsync.document.DocumentState
import de.nibsync.document.erase.EraseResult
import de.nibsync.document.erase.EraserMode
import de.nibsync.document.select.ClipContent
import de.nibsync.document.select.Lasso
import de.nibsync.document.select.translated
import de.nibsync.model.BackgroundSpec
import de.nibsync.model.PageElement
import de.nibsync.model.Quantization
import de.nibsync.model.RichText
import de.nibsync.model.TextBox
import de.nibsync.model.TextBoxDraft
import de.nibsync.model.toDraft
import de.nibsync.text.TextEditController
import de.nibsync.model.BrushFamily
import de.nibsync.model.ShapeRecognizer
import de.nibsync.model.Stroke
import de.nibsync.model.StrokeDraft
import de.nibsync.model.StrokeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Elements just stored by an edit (shape, erasing, moving) and the ids of the elements they replaced. */
class CommittedEdit(val added: List<PageElement>, val removed: Set<StrokeId>)

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
 * Connects the canvas UI to a [DocumentSession]. The session is owned by the caller (it
 * outlives configuration changes and keeps writing after the screen is gone).
 */
class CanvasViewModel(
    private val session: DocumentSession,
    private val recognizer: ShapeRecognizer = ShapeRecognizer.None,
    val clipboard: StrokeClipboard = StrokeClipboard(),
) : ViewModel() {

    val document: StateFlow<DocumentState> = session.state

    val canUndo: StateFlow<Boolean> = session.canUndo
    val canRedo: StateFlow<Boolean> = session.canRedo

    fun undo(): Boolean {
        finishText()
        return session.undo().also { pruneSelection() }
    }

    fun redo(): Boolean {
        finishText()
        return session.redo().also { pruneSelection() }
    }

    private val _selection = MutableStateFlow<Set<StrokeId>>(emptySet())

    /** Strokes selected with the lasso. Only non-empty while the lasso tool is active. */
    val selection: StateFlow<Set<StrokeId>> = _selection.asStateFlow()

    fun selectLasso() {
        finishText()
        _tools.update { it.withLasso() }
    }

    /** Selects the elements enclosed by [polygon] (x0, y0, x1, y1, ... in mm). */
    fun selectByLasso(polygon: FloatArray) {
        _selection.value = Lasso.select(session.state.value.elements, polygon)
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun deleteSelection() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        session.removeStrokes(ids)
        clearSelection()
    }

    fun copySelection() {
        val doc = session.state.value
        ClipContent.of(doc.elements.filter { it.id in _selection.value })?.let(clipboard::set)
    }

    /** Pastes the clipboard centred at ([cx], [cy]) mm; the pasted strokes become the selection. */
    fun paste(cx: Float, cy: Float): CommittedEdit? {
        val clip = clipboard.content.value ?: return null
        val added = session.replaceElements(emptySet(), clip.placedAt(cx, cy))
        _tools.update { it.withLasso() }
        _selection.value = added.mapTo(LinkedHashSet()) { it.id }
        return CommittedEdit(added, emptySet())
    }

    /** Moves the selected strokes by ([dx], [dy]) mm in one undo step; the moved copies stay selected. */
    fun moveSelection(dx: Float, dy: Float): CommittedEdit? {
        val doc = session.state.value
        val selected = doc.elements.filter { it.id in _selection.value }
        if (selected.isEmpty() || (dx == 0f && dy == 0f)) return null
        val added = session.replaceElements(selected.map { it.id }, selected.map { it.translated(dx, dy) })
        _selection.value = added.mapTo(LinkedHashSet()) { it.id }
        return CommittedEdit(added, selected.mapTo(HashSet()) { it.id })
    }

    private fun pruneSelection() {
        val doc = session.state.value
        _selection.value = _selection.value.filterTo(LinkedHashSet()) { it in doc }
    }

    private val _tools = MutableStateFlow(ToolState())
    val tools: StateFlow<ToolState> = _tools.asStateFlow()

    fun selectPen(pen: BrushFamily) {
        finishText()
        _tools.update { it.withPen(pen) }
        clearSelection()
    }

    fun selectColor(argb: Int) = _tools.update { it.withColor(argb) }

    fun selectSize(mm: Float) = _tools.update { it.withSize(mm) }

    fun setDrawWithFinger(enabled: Boolean) = _tools.update { it.copy(drawWithFinger = enabled) }

    /** Changes the paper pattern of the document (synced, not undoable). */
    fun setBackground(background: BackgroundSpec) = session.setBackground(background)

    /** Called on the UI thread when a stroke has been completed on the canvas. */
    fun commitStroke(draft: StrokeDraft): Stroke? {
        if (draft.points.isEmpty()) return null
        return session.addStroke(draft)
    }

    /**
     * Called when the pen is held still at the end of a stroke. Returns the clean shape that
     * was added instead of the stroke, or null if no shape was recognised (the stroke goes on).
     */
    fun commitShape(draft: StrokeDraft): CommittedEdit? {
        if (draft.points.isEmpty()) return null
        val recent = session.state.value.strokes.filter { it.layer == draft.layer }.takeLast(RECENT_STROKES)
        val result = recognizer.recognize(draft, recent) ?: return null
        val stroke = session.replaceStrokes(result.replaces, result.replacement)
        return CommittedEdit(listOf(stroke), result.replaces)
    }

    fun selectEraser(mode: EraserMode) {
        finishText()
        _tools.update { it.withEraser(mode) }
        clearSelection()
    }

    fun selectEraserSize(mm: Float) = _tools.update { it.withEraserSize(mm) }

    /** Stores the outcome of an erase gesture as one undo step. */
    fun commitErase(result: EraseResult): CommittedEdit? {
        if (result.isEmpty) return null
        val added = session.replaceStrokes(result.removed, result.added)
        return CommittedEdit(added, result.removed)
    }

    // ---- text boxes ----------------------------------------------------------------------

    private val _textEditing = MutableStateFlow<TextEditing?>(null)

    /** The text box being edited, or null. While set, the tool bar shows text formatting. */
    val textEditing: StateFlow<TextEditing?> = _textEditing.asStateFlow()

    /**
     * Height in mm of a text in a box of the given width. The screen sets the real text
     * layout; the default is a rough estimate for tests without one.
     */
    var measureText: (RichText, Float) -> Float = { text, _ -> text.paragraphs.size * RichText.DEFAULT_SIZE_PT * RichText.MM_PER_PT * 1.4f }

    /** What was stored last for the edited box, to skip saves without changes. */
    private var savedText: TextBoxDraft? = null
    private var textEditingKey = 0L

    /** The tool to go back to when text mode ends. */
    private var toolBeforeText = Tool.PEN

    fun selectText() {
        enterTextMode()
        clearSelection()
    }

    private fun enterTextMode() {
        val current = _tools.value.tool
        if (current != Tool.TEXT) toolBeforeText = current
        _tools.update { it.withText() }
    }

    private fun leaveTextMode() {
        if (_tools.value.tool == Tool.TEXT) _tools.update { it.copy(tool = toolBeforeText) }
    }

    /** The topmost text box at ([x], [y]) mm, or null. */
    fun textBoxAt(x: Float, y: Float): TextBox? =
        session.state.value.textBoxes.lastOrNull { it.bounds.contains(x, y) }

    /**
     * The page was touched for text at ([x], [y]) mm. A tap ([widthMm] null) opens the text box
     * there, switching to text mode, or, off any box, ends text mode and goes back to the
     * previous tool. A frame dragged with the text tool ([widthMm] set) starts a new box.
     */
    fun requestText(x: Float, y: Float, widthMm: Float?, pageWidthMm: Float) {
        finishText()
        if (widthMm == null) {
            val hit = textBoxAt(x, y)
            if (hit == null) {
                leaveTextMode()
                return
            }
            enterTextMode()
            clearSelection()
            savedText = hit.toDraft() as TextBoxDraft
            startText(hit.id, hit.x, hit.y, hit.widthMm, hit.text)
            return
        }
        enterTextMode()
        val maxWidth = maxOf(TextBox.MIN_WIDTH_MM, pageWidthMm - TEXT_MARGIN_MM)
        val width = widthMm.coerceIn(TextBox.MIN_WIDTH_MM, maxWidth)
        val left = x.coerceIn(0f, maxOf(0f, pageWidthMm - width))
        savedText = null
        startText(null, left, y, width, RichText.EMPTY)
    }

    private fun startText(boxId: StrokeId?, x: Float, y: Float, widthMm: Float, text: RichText) {
        _textEditing.value = TextEditing(++textEditingKey, boxId, x, y, widthMm, TextEditController.create(text))
    }

    /** Stores the edited box if it changed (one undo step). Called while typing and when done. */
    fun saveText() {
        val e = _textEditing.value ?: return
        val text = e.controller.text
        if (text.isBlank && e.boxId == null) return
        // On the storage grid, so an unchanged box compares equal to what was loaded.
        fun q(mm: Float) = Quantization.quantizePosition(mm)
        val draft = TextBoxDraft(q(e.x), q(e.y), q(e.widthMm), q(measureText(text, e.widthMm)), text)
        if (draft == savedText && e.boxId != null && e.boxId in session.state.value) return
        val box = session.replaceElements(listOfNotNull(e.boxId), listOf(draft)).single()
        savedText = draft
        _textEditing.value = e.copy(boxId = box.id)
    }

    /** Ends editing; a box left empty is removed. */
    fun finishText() {
        val e = _textEditing.value ?: return
        if (e.controller.text.isBlank) {
            e.boxId?.let { session.removeStrokes(listOf(it)) }
        } else {
            saveText()
        }
        _textEditing.value = null
        savedText = null
    }

    /** Deletes the edited box and leaves text mode. */
    fun deleteText() {
        val e = _textEditing.value ?: return
        e.boxId?.let { session.removeStrokes(listOf(it)) }
        _textEditing.value = null
        savedText = null
        leaveTextMode()
    }

    /** Moves the edited box while its handle is dragged; [saveText] stores the result. */
    fun moveText(dx: Float, dy: Float) =
        _textEditing.update { it?.copy(x = maxOf(0f, it.x + dx), y = maxOf(0f, it.y + dy)) }

    /** Changes the width of the edited box while its edge is dragged. */
    fun resizeText(dw: Float) =
        _textEditing.update { it?.copy(widthMm = maxOf(TextBox.MIN_WIDTH_MM, it.widthMm + dw)) }

    private companion object {
        /** How many of the latest strokes a multi-stroke shape (arrow head + shaft) may join. */
        const val RECENT_STROKES = 10

        /** Space kept to the right page edge by new text boxes. */
        const val TEXT_MARGIN_MM = 5f
    }
}
