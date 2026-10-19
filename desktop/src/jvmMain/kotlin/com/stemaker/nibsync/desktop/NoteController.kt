package com.stemaker.nibsync.desktop

import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.erase.EraseGesture
import com.stemaker.nibsync.document.erase.EraserMode
import com.stemaker.nibsync.document.select.ClipContent
import com.stemaker.nibsync.document.select.Lasso
import com.stemaker.nibsync.document.select.boundsOf
import com.stemaker.nibsync.document.select.translated
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.ElementDraft
import com.stemaker.nibsync.model.FileElement
import com.stemaker.nibsync.model.FileLink
import com.stemaker.nibsync.model.FileLinkDraft
import com.stemaker.nibsync.model.ImageDraft
import com.stemaker.nibsync.model.ImageElement
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.PageElement
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.ShapeRecognizer
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.StrokePoint
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.assets
import com.stemaker.nibsync.model.geometry.Rect
import com.stemaker.nibsync.shapes.HoldDetector
import com.stemaker.nibsync.model.TodoItem
import com.stemaker.nibsync.text.TextBoxEditing
import com.stemaker.nibsync.text.TodoEditing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.hypot

/** What a gesture in progress looks like, for drawing it on the page. */
sealed interface GesturePreview {
    data class Drawing(val stroke: Stroke) : GesturePreview
    data class Erasing(val visible: List<Stroke>, val x: Float, val y: Float, val radiusMm: Float) : GesturePreview
    class Lassoing(val path: FloatArray) : GesturePreview
    data class Moving(val dx: Float, val dy: Float) : GesturePreview
    data class Resizing(val factor: Float) : GesturePreview
}

/**
 * What mouse, pen and keys do with the open document on the PC, where typing comes first: with
 * the text tool a click on free paper starts a text box, a click on a box opens it. Pens,
 * erasers and the lasso work like on the tablet; a click on a picture or file link selects it
 * (double-click opens the link), the selection can be dragged, scaled at its corner handle,
 * copied, cut, pasted and deleted.
 */
class NoteController(
    val session: DocumentSession,
    measureText: (RichText, Float) -> Float,
    private val recognizer: ShapeRecognizer = ShapeRecognizer.None,
    private val clipboard: DesktopClipboard = DesktopClipboard(),
) {
    val text = TextBoxEditing(session).also { it.measureText = measureText }

    /** The to-do being written or typed, see [selectTodo]. */
    val todo = TodoEditing(session).also { it.measureText = measureText }

    private val _tools = MutableStateFlow(DesktopToolState())
    val tools: StateFlow<DesktopToolState> = _tools.asStateFlow()

    private val _selection = MutableStateFlow<Set<StrokeId>>(emptySet())
    val selection: StateFlow<Set<StrokeId>> = _selection.asStateFlow()

    private val _gesture = MutableStateFlow<GesturePreview?>(null)
    val gesture: StateFlow<GesturePreview?> = _gesture.asStateFlow()

    val clipboardContent get() = clipboard.content

    /** Opens a file link in another program; set by the app. */
    var openFile: (FileLink) -> Unit = {}

    /** Copies files of pasted elements from the document they were copied in; set by the app. */
    var copyAssets: (from: DocumentId, to: DocumentId, assets: Set<com.stemaker.nibsync.model.Asset>) -> Unit = { _, _, _ -> }

    // ---- tools ---------------------------------------------------------------------------

    /** The tool to go back to when a selection made by clicking a picture ends. */
    private var toolBeforeSelection = DesktopTool.TEXT
    private var selectionByClick = false

    fun selectTool(tool: DesktopTool) {
        text.finish()
        todo.finish()
        selectionByClick = false
        if (tool != DesktopTool.LASSO) clearSelection()
        _tools.update { it.copy(tool = tool) }
    }

    fun selectPen(family: BrushFamily) {
        selectTool(DesktopTool.PEN)
        _tools.update { it.copy(pen = family) }
    }

    fun selectColor(argb: Int) = _tools.update { it.copy(pens = it.pens + (it.pen to it.choice.copy(colorArgb = argb))) }

    fun selectSize(mm: Float) = _tools.update { it.copy(pens = it.pens + (it.pen to it.choice.copy(sizeMm = mm))) }

    fun selectEraser(mode: EraserMode) {
        selectTool(DesktopTool.ERASER)
        _tools.update { it.copy(eraserMode = mode) }
    }

    fun selectEraserSize(mm: Float) = _tools.update { it.copy(eraserSizeMm = mm) }

    /** The to-do tool; new to-dos are handwritten with [ink], else typed. */
    fun selectTodo(ink: Boolean) {
        selectTool(DesktopTool.TODO)
        // To-dos are written with a pen, not the highlighter.
        _tools.update { it.copy(todoInk = ink, pen = if (it.pen == BrushFamily.HIGHLIGHTER) BrushFamily.BALLPOINT else it.pen) }
    }

    /** Switches between handwritten and typed new to-dos (the open one keeps its kind). */
    fun selectTodoInk(ink: Boolean) = _tools.update { it.copy(todoInk = ink) }

    /** Ticks the task off or reopens it. */
    fun toggleTodo(taskId: StrokeId) {
        session.setTodoDone(taskId, !session.state.value.isDone(taskId))
    }

    private fun openTodo(item: TodoItem) {
        text.finish()
        clearSelection()
        selectionByClick = false
        todo.open(item)
        _tools.update { it.copy(tool = DesktopTool.TODO) }
    }

    // ---- elements under the pointer ----------------------------------------------------------

    /** The topmost text box at ([x], [y]) mm, or null. */
    fun textBoxAt(x: Float, y: Float): TextBox? =
        session.state.value.textBoxes.lastOrNull { it.bounds.contains(x, y) }

    /** The to-do whose check circle is at ([x], [y]) mm (a little generous), or null. */
    fun todoCheckAt(x: Float, y: Float): TodoItem? {
        val reach = 1.5f
        return session.state.value.todos.lastOrNull { t ->
            val c = t.checkBounds
            x >= c.left - reach && x <= c.right + reach && y >= c.top - reach && y <= c.bottom + reach
        }
    }

    /** The topmost text box, picture, file link or to-do at ([x], [y]) mm, or null. */
    fun elementAt(x: Float, y: Float): PageElement? =
        session.state.value.elements.lastOrNull { it !is Stroke && it.bounds.contains(x, y) }

    // ---- pointer gestures ------------------------------------------------------------------

    private sealed interface Active {
        class Click(val x: Float, val y: Float) : Active
        class Draw(val draft: MutableList<StrokePoint>, val tool: InputTool, val hold: HoldDetector, val startTime: Long, val onElement: Boolean, val intoTodo: Boolean = false) : Active
        class Erase(val gesture: EraseGesture) : Active
        class Lasso(val path: ArrayList<Float>) : Active
        class Move(val startX: Float, val startY: Float, var dx: Float = 0f, var dy: Float = 0f) : Active
        class Resize(val element: FileElement, val anchorDist: Float, var factor: Float = 1f) : Active
        class Check(val taskId: StrokeId) : Active
    }

    private var active: Active? = null
    private var pressX = 0f
    private var pressY = 0f
    private var pressTime = 0L

    /** Farthest the pointer got from where it went down; a click stays close all the time. */
    private var travel = 0f
    private var lastClickTime = Long.MIN_VALUE / 2
    private var lastClickX = 0f
    private var lastClickY = 0f
    private var pieceCounter = 0

    /**
     * A button or pen went down at ([x], [y]) mm. [toleranceMm] is how far a click may wander,
     * [pressure] is null for a mouse, [pageWidthMm] the paper width.
     */
    fun pointerDown(x: Float, y: Float, timeMs: Long, pressure: Float?, toleranceMm: Float, handleReachMm: Float) {
        pressX = x
        pressY = y
        pressTime = timeMs
        travel = 0f
        val state = _tools.value
        // The check circle of a to-do works with every tool but the eraser.
        if (state.tool != DesktopTool.ERASER) {
            todoCheckAt(x, y)?.let {
                active = Active.Check(it.taskId)
                return
            }
        }
        if (_selection.value.isNotEmpty() && state.tool != DesktopTool.ERASER) {
            val resizable = resizable()
            val frame = selectionFrame()
            if (resizable != null && frame != null && abs(x - frame.right) <= handleReachMm && abs(y - frame.bottom) <= handleReachMm) {
                active = Active.Resize(resizable, hypot(x - resizable.x, y - resizable.y))
                _gesture.value = GesturePreview.Resizing(1f)
                return
            }
            if (frame != null && frame.contains(x, y)) {
                active = Active.Move(x, y)
                _gesture.value = GesturePreview.Moving(0f, 0f)
                return
            }
        }
        if (state.tool != DesktopTool.TEXT) text.finish()
        if (state.tool != DesktopTool.TODO) todo.finish()
        active = when (state.tool) {
            DesktopTool.TEXT -> Active.Click(x, y)
            DesktopTool.TODO -> {
                val open = todo.editing.value
                if (open != null && open.isInk && todo.contains(x, y)) {
                    // Writing into the open handwritten to-do.
                    val point = StrokePoint(x, y, 0, pressure ?: StrokePoint.NO_VALUE)
                    Active.Draw(mutableListOf(point), if (pressure != null) InputTool.STYLUS else InputTool.MOUSE, HoldDetector(toleranceMm).also { it.onSample(x, y, timeMs) }, timeMs, onElement = false, intoTodo = true)
                        .also { _gesture.value = GesturePreview.Drawing(previewStroke(it)) }
                } else {
                    Active.Click(x, y)
                }
            }
            DesktopTool.PEN -> {
                val point = StrokePoint(x, y, 0, pressure ?: StrokePoint.NO_VALUE)
                Active.Draw(mutableListOf(point), if (pressure != null) InputTool.STYLUS else InputTool.MOUSE, HoldDetector(toleranceMm).also { it.onSample(x, y, timeMs) }, timeMs, elementAt(x, y) != null)
                    .also { _gesture.value = GesturePreview.Drawing(previewStroke(it)) }
            }
            DesktopTool.ERASER -> {
                val gesture = EraseGesture(state.eraserMode, state.eraserRadiusMm, session.state.value.strokes) { StrokeId("erase-preview-${pieceCounter++}") }
                gesture.moveTo(x, y)
                _gesture.value = GesturePreview.Erasing(gesture.visible, x, y, state.eraserRadiusMm)
                Active.Erase(gesture)
            }
            DesktopTool.LASSO -> Active.Lasso(arrayListOf(x, y)).also { _gesture.value = GesturePreview.Lassoing(it.path.toFloatArray()) }
        }
    }

    fun pointerMove(x: Float, y: Float, timeMs: Long, pressure: Float?) {
        travel = maxOf(travel, abs(x - pressX), abs(y - pressY))
        when (val a = active) {
            is Active.Draw -> {
                a.draft += StrokePoint(x, y, timeMs - a.startTime, pressure ?: StrokePoint.NO_VALUE)
                a.hold.onSample(x, y, timeMs)
                _gesture.value = GesturePreview.Drawing(previewStroke(a))
            }
            is Active.Erase -> {
                a.gesture.moveTo(x, y)
                _gesture.value = GesturePreview.Erasing(a.gesture.visible, x, y, _tools.value.eraserRadiusMm)
            }
            is Active.Lasso -> {
                a.path += x
                a.path += y
                _gesture.value = GesturePreview.Lassoing(a.path.toFloatArray())
            }
            is Active.Move -> {
                a.dx = x - a.startX
                a.dy = y - a.startY
                _gesture.value = GesturePreview.Moving(a.dx, a.dy)
            }
            is Active.Resize -> {
                if (a.anchorDist > 0f) a.factor = maxOf(MIN_SIZE_MM / a.element.widthMm, hypot(x - a.element.x, y - a.element.y) / a.anchorDist)
                _gesture.value = GesturePreview.Resizing(a.factor)
            }
            is Active.Click, is Active.Check, null -> Unit
        }
    }

    /** The button or pen went up; [pageWidthMm] places new text boxes. */
    fun pointerUp(x: Float, y: Float, timeMs: Long, toleranceMm: Float, pageWidthMm: Float) {
        val a = active
        active = null
        _gesture.value = null
        travel = maxOf(travel, abs(x - pressX), abs(y - pressY))
        val isClick = travel <= toleranceMm && timeMs - pressTime <= CLICK_MAX_MS
        when (a) {
            is Active.Click -> if (isClick) click(x, y, timeMs, pageWidthMm)
            is Active.Check -> if (isClick) toggleTodo(a.taskId)
            is Active.Draw -> {
                // A short click of the pen on a text box, picture or link acts on it, not a dot.
                if (a.intoTodo) {
                    if (a.draft.isNotEmpty()) todo.addInk(StrokeDraft(_tools.value.brush, a.tool, a.draft.toList()))
                } else if (isClick && a.onElement) {
                    click(pressX, pressY, timeMs, pageWidthMm)
                } else if (a.draft.isNotEmpty()) {
                    session.addStroke(StrokeDraft(_tools.value.brush, a.tool, a.draft.toList()))
                }
            }
            is Active.Erase -> if (a.gesture.hasChanges) {
                val result = a.gesture.result()
                session.replaceStrokes(result.removed, result.added)
            }
            is Active.Lasso -> {
                if (isClick) {
                    click(pressX, pressY, timeMs, pageWidthMm)
                } else {
                    selectionByClick = false
                    _selection.value = Lasso.select(session.state.value.elements, a.path.toFloatArray())
                }
            }
            is Active.Move -> {
                if (abs(a.dx) > MIN_MOVE_MM || abs(a.dy) > MIN_MOVE_MM) {
                    moveSelection(a.dx, a.dy)
                } else if (isClick) {
                    click(pressX, pressY, timeMs, pageWidthMm)
                }
            }
            is Active.Resize -> if (abs(a.factor - 1f) > 0.01f) resizeSelection(a.factor)
            null -> Unit
        }
    }

    fun pointerCancel() {
        active = null
        _gesture.value = null
    }

    /** Called regularly while drawing: a pen held still turns the stroke into a clean shape. */
    fun checkHold(nowMs: Long) {
        val a = active as? Active.Draw ?: return
        if (a.intoTodo || !a.hold.consumeHold(nowMs)) return
        val points = a.draft.subList(0, a.hold.anchorSampleCount.coerceAtMost(a.draft.size)).toList()
        val draft = StrokeDraft(_tools.value.brush, a.tool, points)
        val recent = session.state.value.strokes.filter { it.layer == draft.layer }.takeLast(RECENT_STROKES)
        val result = recognizer.recognize(draft, recent) ?: return
        session.replaceStrokes(result.replaces, result.replacement)
        active = null
        _gesture.value = null
    }

    private fun previewStroke(a: Active.Draw) = Stroke(PREVIEW_ID, _tools.value.brush.family.defaultLayer, _tools.value.brush, a.tool, a.draft.toList(), 0, session.device)

    // ---- clicks ----------------------------------------------------------------------------

    private fun click(x: Float, y: Float, timeMs: Long, pageWidthMm: Float) {
        val double = timeMs - lastClickTime <= DOUBLE_CLICK_MS && abs(x - lastClickX) < 2f && abs(y - lastClickY) < 2f
        lastClickTime = timeMs
        lastClickX = x
        lastClickY = y
        when (val hit = elementAt(x, y)) {
            is TextBox -> {
                endTemporarySelection()
                clickAt(x, y, pageWidthMm)
            }
            is FileLink -> if (double) openFile(hit) else selectByClick(hit.id)
            is ImageElement -> selectByClick(hit.id)
            is TodoItem -> when {
                _tools.value.tool == DesktopTool.LASSO -> selectByClick(hit.id)
                todo.editing.value?.itemId != hit.id -> openTodo(hit)
            }
            else -> {
                if (todo.finish()) {
                    // The first click beside an open to-do only ends it.
                } else if (_selection.value.isNotEmpty()) {
                    // The first click beside a selection only ends it.
                    clearSelection()
                    endTemporarySelection()
                } else if (_tools.value.tool == DesktopTool.TEXT) {
                    clickAt(x, y, pageWidthMm)
                } else if (_tools.value.tool == DesktopTool.TODO && x in 0f..pageWidthMm && y >= 0f) {
                    todo.startNew(x - TodoItem.CONTENT_OFFSET_MM / 2, y - TodoItem.MIN_HEIGHT_MM / 2, _tools.value.todoInk, pageWidthMm)
                }
            }
        }
    }

    private fun selectByClick(id: StrokeId) {
        text.finish()
        todo.finish()
        val tool = _tools.value.tool
        if (tool != DesktopTool.LASSO) {
            toolBeforeSelection = tool
            selectionByClick = true
            _tools.update { it.copy(tool = DesktopTool.LASSO) }
        }
        _selection.value = setOf(id)
    }

    private fun endTemporarySelection() {
        if (selectionByClick && _tools.value.tool == DesktopTool.LASSO) _tools.update { it.copy(tool = toolBeforeSelection) }
        selectionByClick = false
    }

    /**
     * A click with the text tool at ([x], [y]) mm on a page [pageWidthMm] wide. Opens the text
     * box there, or starts a new one whose first line is at the click. Clicks beside the page
     * only end editing.
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

    // ---- selection ---------------------------------------------------------------------------

    fun clearSelection() {
        _selection.value = emptySet()
    }

    private fun selected(): List<PageElement> = session.state.value.elements.filter { it.id in _selection.value }

    /** The selected picture or link if it is the whole selection (then it can be scaled). */
    fun resizable(): FileElement? = selected().singleOrNull() as? FileElement

    /** The frame around the selection, in mm. */
    fun selectionFrame(): Rect? {
        val b = boundsOf(selected()) ?: return null
        return Rect(b.left - SELECTION_PADDING_MM, b.top - SELECTION_PADDING_MM, b.right + SELECTION_PADDING_MM, b.bottom + SELECTION_PADDING_MM)
    }

    fun deleteSelection(): Boolean {
        val ids = _selection.value
        if (ids.isEmpty()) return false
        session.removeStrokes(ids)
        clearSelection()
        endTemporarySelection()
        return true
    }

    fun copySelection(): Boolean {
        val content = ClipContent.of(selected()) ?: return false
        clipboard.set(content, session.state.value.meta.id)
        return true
    }

    fun cutSelection(): Boolean = copySelection() && deleteSelection()

    /** Pastes the copied elements centred at ([cx], [cy]) mm; they become the selection. */
    fun paste(cx: Float, cy: Float): Boolean {
        val clip = clipboard.content.value ?: return false
        text.finish()
        todo.finish()
        val drafts = clip.placedAt(cx, cy)
        val here = session.state.value.meta.id
        val source = clipboard.source
        if (source != null && source != here && drafts.assets().isNotEmpty()) copyAssets(source, here, drafts.assets())
        val added = session.replaceElements(emptySet(), drafts)
        selectionByClick = false
        _tools.update { it.copy(tool = DesktopTool.LASSO) }
        _selection.value = added.mapTo(LinkedHashSet()) { it.id }
        return true
    }

    /** Adds inserted pictures or links (files already stored) and selects them for moving and scaling. */
    fun insert(drafts: List<ElementDraft>) {
        if (drafts.isEmpty()) return
        text.finish()
        val added = session.replaceElements(emptySet(), drafts)
        val tool = _tools.value.tool
        if (tool != DesktopTool.LASSO) {
            toolBeforeSelection = tool
            selectionByClick = true
            _tools.update { it.copy(tool = DesktopTool.LASSO) }
        }
        _selection.value = added.mapTo(LinkedHashSet()) { it.id }
    }

    private fun moveSelection(dx: Float, dy: Float) {
        val items = selected()
        if (items.isEmpty()) return
        val added = session.replaceElements(items.map { it.id }, items.map { it.translated(dx, dy) })
        _selection.value = added.mapTo(LinkedHashSet()) { it.id }
    }

    private fun resizeSelection(factor: Float) {
        val element = resizable() ?: return
        val w = maxOf(MIN_SIZE_MM, element.widthMm * factor)
        val h = element.heightMm * (w / element.widthMm)
        val draft = when (element) {
            is ImageElement -> ImageDraft(element.x, element.y, w, h, element.asset)
            is FileLink -> FileLinkDraft(element.x, element.y, w, h, element.asset, element.fileName)
        }
        val added = session.replaceElements(listOf(element.id), listOf(draft))
        _selection.value = added.mapTo(LinkedHashSet()) { it.id }
    }

    /** Escape: stop editing or selecting. */
    fun escape() {
        if (!text.finish() && !todo.finish()) {
            clearSelection()
            endTemporarySelection()
        }
    }

    fun endEditing() {
        text.finish()
        todo.finish()
    }

    fun undo(): Boolean {
        endEditing()
        return session.undo().also { pruneSelection() }
    }

    fun redo(): Boolean {
        endEditing()
        return session.redo().also { pruneSelection() }
    }

    private fun pruneSelection() {
        val doc = session.state.value
        _selection.value = _selection.value.filterTo(LinkedHashSet()) { it in doc }
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

        const val SELECTION_PADDING_MM = 2f
        const val MIN_MOVE_MM = 0.2f
        const val MIN_SIZE_MM = 5f
        const val CLICK_MAX_MS = 400L
        const val DOUBLE_CLICK_MS = 400L
        const val RECENT_STROKES = 10
        val PREVIEW_ID = StrokeId("in-progress")
    }
}

/** Copied elements, shared by all documents of the window. */
class DesktopClipboard {
    private val _content = MutableStateFlow<ClipContent?>(null)
    val content: StateFlow<ClipContent?> = _content.asStateFlow()

    var source: DocumentId? = null
        private set

    fun set(content: ClipContent?, source: DocumentId? = null) {
        this.source = source
        _content.value = content
    }
}
