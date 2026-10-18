package de.nibsync.canvas

import androidx.lifecycle.ViewModel
import de.nibsync.document.DocumentSession
import de.nibsync.document.DocumentState
import de.nibsync.document.erase.EraseResult
import de.nibsync.document.erase.EraserMode
import de.nibsync.document.select.ClipContent
import de.nibsync.document.select.Lasso
import de.nibsync.document.select.translated
import de.nibsync.model.Asset
import de.nibsync.model.BackgroundSpec
import de.nibsync.model.DocumentId
import de.nibsync.model.ElementDraft
import de.nibsync.model.FileElement
import de.nibsync.model.FileLink
import de.nibsync.model.FileLinkDraft
import de.nibsync.model.ImageDraft
import de.nibsync.model.ImageElement
import de.nibsync.model.assets
import de.nibsync.model.PageElement
import de.nibsync.model.Quantization
import de.nibsync.model.RichText
import de.nibsync.model.TextBox
import de.nibsync.model.TextBoxDraft
import de.nibsync.model.toDraft
import de.nibsync.text.TextBoxEditing
import de.nibsync.text.TextEditing
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
        lassoByTap = false
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
        ClipContent.of(doc.elements.filter { it.id in _selection.value })?.let { clipboard.set(it, doc.meta.id) }
    }

    /**
     * Copies files (pictures, PDFs) of pasted elements from the document they were copied in;
     * set by the app, which knows where documents are stored.
     */
    var copyAssets: (from: DocumentId, to: DocumentId, assets: Set<Asset>) -> Unit = { _, _, _ -> }

    /** Pastes the clipboard centred at ([cx], [cy]) mm; the pasted strokes become the selection. */
    fun paste(cx: Float, cy: Float): CommittedEdit? {
        val clip = clipboard.content.value ?: return null
        val drafts = clip.placedAt(cx, cy)
        val here = session.state.value.meta.id
        val source = clipboard.source
        if (source != null && source != here && drafts.assets().isNotEmpty()) copyAssets(source, here, drafts.assets())
        val added = session.replaceElements(emptySet(), drafts)
        if (_tools.value.tool != Tool.LASSO) lassoByTap = false
        _tools.update { it.withLasso() }
        _selection.value = added.mapTo(LinkedHashSet()) { it.id }
        return CommittedEdit(added, emptySet())
    }

    /**
     * Adds inserted pictures or file links (their files must already be stored with the
     * document) and selects them, so they can be moved and scaled right away.
     */
    fun insert(drafts: List<ElementDraft>): List<PageElement> {
        if (drafts.isEmpty()) return emptyList()
        finishText()
        val added = session.replaceElements(emptySet(), drafts)
        selectTemporarily(added.mapTo(LinkedHashSet()) { it.id })
        return added
    }

    /**
     * Scales the selected picture or file link by [factor] from its top left corner, in one
     * undo step. Only for a selection of exactly one such element.
     */
    fun resizeSelection(factor: Float): CommittedEdit? {
        val element = session.state.value.elements.singleOrNull { it.id in _selection.value } as? FileElement ?: return null
        if (_selection.value.size != 1 || factor <= 0f || factor == 1f) return null
        val w = maxOf(MIN_FILE_SIZE_MM, element.widthMm * factor)
        val h = element.heightMm * (w / element.widthMm)
        val draft = when (element) {
            is ImageElement -> ImageDraft(element.x, element.y, w, h, element.asset)
            is FileLink -> FileLinkDraft(element.x, element.y, w, h, element.asset, element.fileName)
        }
        val added = session.replaceElements(listOf(element.id), listOf(draft))
        _selection.value = added.mapTo(LinkedHashSet()) { it.id }
        return CommittedEdit(added, setOf(element.id))
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
        lassoByTap = false
        _tools.update { it.withPen(pen) }
        clearSelection()
    }

    fun selectColor(argb: Int) = _tools.update { it.withColor(argb) }

    fun selectSize(mm: Float) = _tools.update { it.withSize(mm) }


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
        lassoByTap = false
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

    private val text = TextBoxEditing(session)

    /** The text box being edited, or null. While set, the tool bar shows text formatting. */
    val textEditing: StateFlow<TextEditing?> = text.editing

    /** Height in mm of a text in a box of the given width; the screen sets the real layout. */
    var measureText: (RichText, Float) -> Float
        get() = text.measureText
        set(value) {
            text.measureText = value
        }

    // Text mode, and the lasso when it was entered by tapping a picture, are temporary: a tap
    // on an empty spot of the page goes back to the tool used before.

    /** The tool to go back to when a temporary mode ends. */
    private var toolBeforeTemporary = Tool.PEN

    /** True if the lasso tool was switched on by tapping a picture, not by the user. */
    private var lassoByTap = false

    /** Opens a file link in another app; set by the app. */
    var openFile: (FileLink) -> Unit = {}

    fun selectText() {
        enterTextMode()
        clearSelection()
    }

    private fun rememberToolBeforeTemporary() {
        val current = _tools.value.tool
        val temporary = current == Tool.TEXT || (current == Tool.LASSO && lassoByTap)
        if (!temporary) toolBeforeTemporary = current
    }

    private fun enterTextMode() {
        rememberToolBeforeTemporary()
        lassoByTap = false
        _tools.update { it.withText() }
    }

    private fun selectTemporarily(ids: Set<StrokeId>) {
        val manualLasso = _tools.value.tool == Tool.LASSO && !lassoByTap
        if (!manualLasso) {
            rememberToolBeforeTemporary()
            lassoByTap = true
            _tools.update { it.withLasso() }
        }
        _selection.value = ids
    }

    private fun leaveTemporaryMode() {
        val tool = _tools.value.tool
        if (tool == Tool.TEXT || (tool == Tool.LASSO && lassoByTap)) {
            lassoByTap = false
            _tools.update { it.copy(tool = toolBeforeTemporary) }
        }
    }

    /** The topmost text box at ([x], [y]) mm, or null. */
    fun textBoxAt(x: Float, y: Float): TextBox? =
        session.state.value.textBoxes.lastOrNull { it.bounds.contains(x, y) }

    /** The topmost text box, picture or file link at ([x], [y]) mm, or null. */
    fun tappableAt(x: Float, y: Float): PageElement? =
        session.state.value.elements.lastOrNull { it !is Stroke && it.bounds.contains(x, y) }

    /**
     * A tap on the page at ([x], [y]) mm (pen tap on an element, text tool, lasso or finger).
     * A text box opens for editing, a file link opens in another app, a picture gets selected;
     * an empty spot ends text mode or a picture selection and goes back to the previous tool.
     */
    fun tapAt(x: Float, y: Float) {
        finishText()
        when (val hit = tappableAt(x, y)) {
            is TextBox -> {
                enterTextMode()
                clearSelection()
                text.open(hit)
            }
            is FileLink -> openFile(hit)
            is ImageElement -> selectTemporarily(setOf(hit.id))
            else -> {
                clearSelection()
                leaveTemporaryMode()
            }
        }
    }

    /**
     * A long press at ([x], [y]) mm selects the text box, picture or link there (temporary
     * lasso mode), so it can be moved, copied or deleted. Elsewhere it does nothing.
     */
    fun longPressAt(x: Float, y: Float) {
        val hit = tappableAt(x, y) ?: return
        finishText()
        selectTemporarily(setOf(hit.id))
    }

    /**
     * The text tool touched the page: a frame ([widthMm] set) dragged from ([x], [y]) mm starts
     * a new text box; a tap ([widthMm] null) is handled like [tapAt].
     */
    fun requestText(x: Float, y: Float, widthMm: Float?, pageWidthMm: Float) {
        if (widthMm == null) {
            tapAt(x, y)
            return
        }
        finishText()
        enterTextMode()
        val maxWidth = maxOf(TextBox.MIN_WIDTH_MM, pageWidthMm - TEXT_MARGIN_MM)
        val width = widthMm.coerceIn(TextBox.MIN_WIDTH_MM, maxWidth)
        val left = x.coerceIn(0f, maxOf(0f, pageWidthMm - width))
        text.startNew(left, y, width)
    }

    /** Stores the edited box if it changed (one undo step). Called while typing and when done. */
    fun saveText() = text.save()

    /** Ends editing; a box left empty is removed. */
    fun finishText() {
        text.finish()
    }

    /** Deletes the edited box and leaves text mode. */
    fun deleteText() {
        if (text.delete()) leaveTemporaryMode()
    }

    /** Moves the edited box while its handle is dragged; [saveText] stores the result. */
    fun moveText(dx: Float, dy: Float) = text.move(dx, dy)

    /** Changes the width of the edited box while its edge is dragged. */
    fun resizeText(dw: Float) = text.resize(dw)

    private companion object {
        /** How many of the latest strokes a multi-stroke shape (arrow head + shaft) may join. */
        const val RECENT_STROKES = 10

        /** Space kept to the right page edge by new text boxes. */
        const val TEXT_MARGIN_MM = 5f

        /** Pictures and links are not scaled below this width. */
        const val MIN_FILE_SIZE_MM = 5f
    }
}
