package com.stemaker.nibsync.canvas

import com.stemaker.nibsync.model.TableElement
import com.stemaker.nibsync.text.TableEdit
import com.stemaker.nibsync.text.TableEditing
import androidx.lifecycle.ViewModel
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.document.erase.EraseResult
import com.stemaker.nibsync.document.erase.EraserMode
import com.stemaker.nibsync.document.select.ClipContent
import com.stemaker.nibsync.model.BlockDraft
import com.stemaker.nibsync.text.FlowDrop
import com.stemaker.nibsync.text.FlowEditing
import com.stemaker.nibsync.document.select.Lasso
import com.stemaker.nibsync.model.Anchor
import com.stemaker.nibsync.model.AnchorDraft
import com.stemaker.nibsync.model.Asset
import com.stemaker.nibsync.model.LinkTarget
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.NoteLink
import com.stemaker.nibsync.model.linkTarget
import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.ElementDraft
import com.stemaker.nibsync.model.FileElement
import com.stemaker.nibsync.model.FileLink
import com.stemaker.nibsync.model.FileLinkDraft
import com.stemaker.nibsync.model.ImageDraft
import com.stemaker.nibsync.model.ImageElement
import com.stemaker.nibsync.model.assets
import com.stemaker.nibsync.model.PageElement
import com.stemaker.nibsync.model.selectedBounds
import com.stemaker.nibsync.model.Quantization
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TextBoxDraft
import com.stemaker.nibsync.model.toDraft
import com.stemaker.nibsync.text.TextBoxEditing
import com.stemaker.nibsync.text.TextEditing
import com.stemaker.nibsync.text.TodoEdit
import com.stemaker.nibsync.text.TodoEditing
import com.stemaker.nibsync.model.TodoItem
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.ShapeRecognizer
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.SnapGrid
import com.stemaker.nibsync.model.StrokeId
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
    initialTools: ToolState = ToolState(),
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
        todoByTap = false
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
        flows.remove(ids, pull = false)
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
        val added = flows.place(drafts)
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
        // Anchors and links go in at the cursor of an open text box, as letters.
        if (drafts.all { text.insertLinkAtCursor(it) }) return emptyList()
        val blocks = drafts.filterIsInstance<BlockDraft>()
        // Pictures and files go in at the cursor of an open text box.
        val added = if (text.editing.value != null && blocks.size == drafts.size) {
            tables.finish()
            text.insertBlocksAtCursor(blocks)
        } else {
            finishText()
            flows.place(drafts)
        }
        // Inserted at the cursor with text after it: typing goes on there.
        if (text.editing.value != null) return added
        selectTemporarily(added.mapTo(LinkedHashSet()) { it.id })
        return added
    }

    /** Follows a link to a note; set by the app. */
    var openNote: (LinkTarget) -> Unit = {}

    /** Asks for a new name of an anchor (then [renameAnchor]); set by the app. */
    var editAnchor: (Anchor) -> Unit = {}

    /** The anchor or link at a place (mm) in a text box; set by the screen, which has the text layout. */
    var linkAt: (TextBox, Float, Float) -> MarkStyle? = { _, _, _ -> null }

    /** The code of a code block whose copy symbol is at a place (mm) in a text box; set by the screen. */
    var codeToCopyAt: (TextBox, Float, Float) -> String? = { _, _, _ -> null }

    /** Puts text on the system clipboard; set by the screen. */
    var copyText: (String) -> Unit = {}

    /** Where the lists of [box] start: numbering goes on from the text above it in its flow. */
    fun listStart(box: TextBox): com.stemaker.nibsync.text.ListStart = com.stemaker.nibsync.text.RichTextLayout.listStart(session.state.value, box)

    /** Gives the anchor a new name; it stays the same anchor, so links to it keep working. */
    fun renameAnchor(anchor: Anchor, name: String) {
        val current = session.state.value.anchor(anchor.anchorId) ?: return
        if (name.isNotBlank() && name != current.name && flows.renameInText(anchor.anchorId, name.trim())) return
        if (name.isBlank() || name.trim() == current.name) return
        val added = session.replaceElements(listOf(current.id), listOf(AnchorDraft(current.x, current.y, name.trim(), current.anchorId)))
        if (current.id in _selection.value) _selection.value = added.mapTo(LinkedHashSet()) { it.id }
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
        val added = flows.resize(element, draft)
        _selection.value = setOf(added.id)
        return CommittedEdit(listOf(added), setOf(element.id))
    }

    /** Moves the selected strokes by ([dx], [dy]) mm in one undo step; the moved copies stay selected. */
    fun moveSelection(dx: Float, dy: Float): CommittedEdit? {
        val before = _selection.value
        if (before.isEmpty() || (dx == 0f && dy == 0f)) return null
        val after = flows.moveSelection(before, dx, dy)
        _selection.value = after
        return CommittedEdit(session.state.value.elements.filter { it.id in after }, before - after)
    }

    /** The grid moved and new things snap to; set by the app from the settings. */
    var snapGrid: () -> SnapGrid = { SnapGrid() }

    /** A move of the selection by ([dx], [dy]) mm, adjusted so that it lands on the grid. */
    fun snapMove(dx: Float, dy: Float): Pair<Float, Float> {
        // Not moved yet (a tap): nothing jumps to the grid.
        if (kotlin.math.abs(dx) <= MIN_SNAP_MOVE_MM && kotlin.math.abs(dy) <= MIN_SNAP_MOVE_MM) return dx to dy
        val state = session.state.value
        val (x, y) = SnapGrid.pointOf(state.elements.filter { it.id in _selection.value }) ?: return dx to dy
        return snapGrid().snapMove(x, y, dx, dy, state.background, state.meta.format.widthMm)
    }

    /** Whether tables, pictures, files or to-dos in the selection can be indented by [delta] steps. */
    fun canIndent(delta: Int): Boolean = flows.canIndent(_selection.value, delta)

    /** Indents the selected tables, pictures, files and to-dos by [delta] steps; they stay selected. */
    fun indentSelection(delta: Int) {
        _selection.value = flows.indent(_selection.value, delta)
    }

    /** Where blocks of the selection would land in text if moved by ([dx], [dy]) mm. */
    fun dropFor(dx: Float, dy: Float): FlowDrop? = flows.dropFor(_selection.value, dx, dy)

    private fun pruneSelection() {
        val doc = session.state.value
        _selection.value = _selection.value.filterTo(LinkedHashSet()) { it in doc }
    }

    private val _tools = MutableStateFlow(initialTools)
    val tools: StateFlow<ToolState> = _tools.asStateFlow()

    fun selectPen(pen: BrushFamily) {
        finishText()
        lassoByTap = false
        todoByTap = false
        _tools.update { it.withPen(pen) }
        clearSelection()
    }

    fun selectColor(argb: Int) = _tools.update { it.withColor(argb) }

    fun selectSize(mm: Float) = _tools.update { it.withSize(mm) }

    /** The pen widths set in the settings. */
    fun setPenWidths(widths: Map<BrushFamily, List<Float>>) = _tools.update { it.withWidths(widths) }


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
        todoByTap = false
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

    private val todo = TodoEditing(session)

    /** Tables: the cell being typed, rows, columns and joined cells. */
    val tables = TableEditing(session)

    /** Blocks in the text: dropping, placing, removing. */
    private val flows = FlowEditing(session)

    /** The table cell being edited, or null. */
    val tableEditing: StateFlow<TableEdit?> = tables.editing

    /** While typing in a box or cell: what the text flow will move, and how far. */
    fun flowPreview(): com.stemaker.nibsync.text.FlowPreview? =
        if (text.editing.value != null) text.flowPreview() else tables.flowPreview()

    /** The to-do being written or typed, or null. */
    val todoEditing: StateFlow<TodoEdit?> = todo.editing

    /** Height in mm of a text in a box of the given width; the screen sets the real layout. */
    var measureText: (RichText, Float) -> Float
        get() = text.measureText
        set(value) {
            text.measureText = value
            todo.measureText = value
            tables.measureText = value
            flows.measureText = value
        }

    /** The to-do tool; new to-dos are handwritten with [ink] (chosen with the pen), else typed. */
    fun selectTodo(ink: Boolean) {
        finishText()
        lassoByTap = false
        todoByTap = false
        _tools.update { it.withTodo(ink) }
        clearSelection()
    }

    /** Switches between handwritten and typed new to-dos (an open one keeps its kind). */
    fun selectTodoInk(ink: Boolean) = _tools.update { it.copy(todoInk = ink) }

    /** Ticks the task off or reopens it (one undo step). */
    fun toggleTodo(taskId: StrokeId) {
        session.setTodoDone(taskId, !session.state.value.isDone(taskId))
    }

    /** A stroke written into the open handwritten to-do. */
    fun commitTodoInk(draft: StrokeDraft) = todo.addInk(draft)

    /** Stores the open to-do if it changed (while typing, after moving or resizing). */
    fun saveTodo() = todo.save()

    fun moveTodo(dx: Float, dy: Float) = todo.move(dx, dy)

    fun resizeTodo(dw: Float, dh: Float) = todo.resize(dw, dh)

    /** Deletes the open to-do. */
    fun deleteTodo() {
        if (todo.delete()) leaveTemporaryMode()
    }

    /** True if the to-do tool was switched on by tapping a to-do, not by the user. */
    private var todoByTap = false

    private fun openTodo(item: TodoItem) {
        rememberToolBeforeTemporary()
        val tool = _tools.value.tool
        if (tool != Tool.TODO) todoByTap = true
        lassoByTap = false
        clearSelection()
        _tools.update { it.copy(tool = Tool.TODO) }
        todo.open(item)
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
        val temporary = current == Tool.TEXT || (current == Tool.LASSO && lassoByTap) || (current == Tool.TODO && todoByTap)
        if (!temporary) toolBeforeTemporary = current
    }

    private fun enterTextMode() {
        rememberToolBeforeTemporary()
        lassoByTap = false
        todoByTap = false
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
        if (tool == Tool.TEXT || (tool == Tool.LASSO && lassoByTap) || (tool == Tool.TODO && todoByTap)) {
            lassoByTap = false
            todoByTap = false
            _tools.update { it.copy(tool = toolBeforeTemporary) }
        }
    }

    /** The topmost text box at ([x], [y]) mm, or null. */
    fun textBoxAt(x: Float, y: Float): TextBox? =
        session.state.value.textBoxes.lastOrNull { it.bounds.contains(x, y) }

    /** The topmost text box, picture or file link at ([x], [y]) mm, or null. */
    fun tappableAt(x: Float, y: Float): PageElement? =
        session.state.value.elements.lastOrNull { it !is Stroke && it.shownBounds.contains(x, y) }

    /** Bounds as drawn: a selected anchor shows its name too. */
    private val PageElement.shownBounds get() = if (id in _selection.value) selectedBounds else bounds

    /**
     * A tap on the page at ([x], [y]) mm (pen tap on an element, text tool, lasso or finger).
     * A text box opens for editing, a file link opens in another app, a picture gets selected.
     * An empty spot starts a new text box with the text tool; if a box was open, it only ends
     * it, and it ends a picture selection, going back to the previous tool.
     */
    fun tapAt(x: Float, y: Float) {
        val hit = tappableAt(x, y)
        val open = todo.editing.value
        val textWasOpen = text.editing.value != null || tables.editing.value != null
        // A tap into the open to-do keeps it open.
        if (open != null && hit is TodoItem && hit.id == open.itemId) return
        finishText()
        when (hit) {
            is TodoItem -> if (_tools.value.tool == Tool.LASSO && !lassoByTap) selectTemporarily(setOf(hit.id)) else openTodo(hit)
            is TextBox -> {
                // A tap on a code block's copy symbol copies the code, with any tool.
                codeToCopyAt(hit, x, y)?.let {
                    copyText(it)
                    return
                }
                // With a tool other than text, a tap on an anchor or link in text works it.
                val mark = if (_tools.value.tool == Tool.TEXT) null else linkAt(hit, x, y)
                when (mark) {
                    is MarkStyle.NoteLink -> openNote(mark.linkTarget)
                    is MarkStyle.Anchor -> session.state.value.anchor(mark.anchorId)?.let(editAnchor)
                    else -> {
                        enterTextMode()
                        clearSelection()
                        text.open(hit)
                    }
                }
            }
            is FileLink -> openFile(hit)
            is NoteLink -> openNote(hit.linkTarget)
            // A tap selects an anchor (to move it); a tap on the selected anchor renames it.
            is Anchor -> if (_selection.value == setOf(hit.id)) editAnchor(hit) else selectTemporarily(setOf(hit.id))
            is ImageElement -> selectTemporarily(setOf(hit.id))
            is TableElement -> if (_tools.value.tool == Tool.LASSO && !lassoByTap) {
                selectTemporarily(setOf(hit.id))
            } else {
                hit.geometry.areaAtPoint(x - hit.x, y - hit.y)?.let { area ->
                    enterTextMode()
                    clearSelection()
                    tables.open(hit, hit.geometry.key(area))
                }
            }
            else -> {
                clearSelection()
                if (_tools.value.tool == Tool.TODO && open == null && !todoByTap) {
                    // A new to-do with its check circle at the tap.
                    val pageWidth = session.state.value.meta.format.widthMm
                    todo.startNew(x - TodoItem.PADDING_MM - TodoItem.CHECK_SIZE_MM / 2, y - TodoItem.MIN_HEIGHT_MM / 2, _tools.value.todoInk, pageWidth)
                } else if (_tools.value.tool == Tool.TEXT && !textWasOpen && open == null) {
                    // A new box of the default size, its first line at the tap.
                    val pageWidth = session.state.value.meta.format.widthMm
                    if (x in 0f..pageWidth && y >= 0f) {
                        val next = flows.continuation(x, y)
                        if (next != null) {
                            text.startNew(next.x, next.y, next.widthMm, next.slot)
                        } else {
                            val (sx, sy) = snapGrid().snap(x, y, session.state.value.background, pageWidth)
                            val (left, top, width) = TextBox.placeNew(sx, sy, pageWidth)
                            text.startNew(left, top, width)
                        }
                    }
                } else {
                    leaveTemporaryMode()
                }
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
        todo.finish()
        tables.finish()
    }

    /**
     * Adds a table of [rows] x [columns] and opens its first cell: at the cursor of an open text
     * box, else centred at [cx] with its top at [top] mm.
     */
    fun insertTable(cx: Float, top: Float, rows: Int, columns: Int, header: Boolean) {
        // At the cursor of an open text box, which is split there.
        if (text.editing.value != null) {
            tables.insertInto(text, rows, columns, header)
            return
        }
        finishText()
        clearSelection()
        enterTextMode()
        val pageWidth = session.state.value.meta.format.widthMm
        val width = minOf(TableElement.DEFAULT_WIDTH_MM, pageWidth - 10f)
        tables.insert(cx - width / 2, top, rows, columns, header, pageWidth)
    }

    /** Sets the width of a column of the open table (its border was dragged). */
    fun setColumnWidth(colId: StrokeId, widthMm: Float) {
        val t = tables.table() ?: return
        tables.setColumnWidth(t, colId, widthMm)
    }

    /** "Done" while editing text: stores the box or to-do and goes back to the previous tool. */
    fun doneText() {
        finishText()
        leaveTemporaryMode()
    }

    /** Deletes the edited box and leaves text mode. */
    fun deleteText() {
        if (text.delete()) leaveTemporaryMode()
    }

    /** Moves the edited box while its handle is dragged; [saveText] stores the result. */
    fun moveText(dx: Float, dy: Float) = text.move(dx, dy)

    /** Changes the width of the edited box while its edge is dragged. */
    fun resizeText(dw: Float) = text.resize(dw)

    /** The open box as one thing: the cursor goes through its text, tables and pictures. */
    val box by lazy { com.stemaker.nibsync.text.BoxEditing(session, text, tables, flows) }

    /** The box of the open text or table cell, or null. */
    fun openBox(): com.stemaker.nibsync.model.Flow? = box.openFlow()

    /**
     * A key at the edge of the open text ([inCell]: table cell) goes on in the paragraph before or
     * after it in the box; a picture, file or to-do there gets selected. False if not handled.
     */
    fun boundary(boundary: com.stemaker.nibsync.text.Boundary, inCell: Boolean): Boolean {
        val result = (if (inCell) box.fromCell(boundary) else box.fromText(boundary)) ?: return false
        if (result.isNotEmpty()) selectTemporarily(result)
        return true
    }

    /** Moves the open box by its handle: live with open text, else when the drag ends ([done]). */
    fun moveBox(dx: Float, dy: Float, done: Boolean) {
        if (text.editing.value != null) {
            if (!done) text.move(dx, dy) else text.save()
            return
        }
        if (done) box.openFlow()?.let { flows.moveBy(it.id, dx, dy) }
    }

    /** Sets the open box's width (its right edge was dragged): live for open text, all of it when [done]. */
    fun resizeBox(dw: Float, done: Boolean) {
        val flow = box.openFlow() ?: return
        val e = text.editing.value
        if (!done) {
            if (e != null) text.resize(dw)
            return
        }
        box.setWidth(flow.id, if (e != null) e.widthMm else box.widthOf(flow) + dw)
    }

    private companion object {
        /** How many of the latest strokes a multi-stroke shape (arrow head + shaft) may join. */
        const val RECENT_STROKES = 10

        /** Space kept to the right page edge by new text boxes. */
        const val TEXT_MARGIN_MM = 5f

        /** Pictures and links are not scaled below this width. */
        const val MIN_FILE_SIZE_MM = 5f
    }
}

/** Moves shorter than this (mm) do not snap to the grid. */
private const val MIN_SNAP_MOVE_MM = 0.5f
