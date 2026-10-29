package com.stemaker.nibsync.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import com.stemaker.nibsync.model.geometry.Viewport
import com.stemaker.nibsync.text.TextBoxEditor
import com.stemaker.nibsync.text.TextEditing
import com.stemaker.nibsync.text.TextFormatActions
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.InsertPageBreak
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stemaker.nibsync.document.erase.EraserMode
import com.stemaker.nibsync.background.ui.BackgroundPicker
import com.stemaker.nibsync.model.Asset
import com.stemaker.nibsync.model.BackgroundSpec
import java.io.File
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.background.ui.R as BackgroundR

/** Test tags used by UI tests. */
object NoteScreenTags {
    const val TOOL_SETTINGS = "tool-settings"
    const val CANVAS = "note-canvas"
    const val TOOLBAR = "note-toolbar"
    fun pen(family: BrushFamily) = "pen-${family.name}"
    fun color(index: Int) = "color-$index"
    fun size(index: Int) = "size-$index"
    const val UNDO = "undo"
    const val MENU = "menu"
    const val BACKGROUND = "background"
    const val BACKGROUND_CONFIRM = "background-confirm"
    const val LASSO = "lasso"
    const val TEXT = "text-tool"
    const val INSERT = "insert"
    const val INSERT_FILE = "insert-file"
    const val INSERT_CLIPBOARD = "insert-clipboard"
    const val INSERT_ANCHOR = "insert-anchor"
    const val INSERT_NOTE_LINK = "insert-note-link"
    const val MOVE_TEXT = "move-text"
    const val RESIZE_TEXT = "resize-text"
    const val DELETE_SELECTION = "delete-selection"
    const val COPY_SELECTION = "copy-selection"
    const val PASTE = "paste"
    const val REDO = "redo"
    const val TODO = "todo-tool"
    const val TODO_KEYBOARD = "todo-keyboard"
    const val TODO_INK = "todo-ink"
    const val MOVE_TODO = "move-todo"
    const val RESIZE_TODO = "resize-todo"
    const val DELETE_TODO = "delete-todo"
    fun eraserMode(mode: EraserMode) = "eraser-${mode.name}"
    fun eraserSize(index: Int) = "eraser-size-$index"
}

@Composable
fun NoteScreen(
    viewModel: CanvasViewModel,
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    showPageBreaks: Boolean = false,
    /** Fingers write instead of scrolling (a setting, for devices without a pen). */
    drawWithFinger: Boolean = false,
    /** Extra items at the end of the tool bar, e.g. a sync indicator. */
    toolbarEnd: @Composable () -> Unit = {},
    /** Where a file (picture, PDF) of this document is stored. */
    assetFile: (Asset) -> File? = { null },
    /** Opens the file picker to insert pictures or PDFs; null hides the insert menu. */
    onInsertFile: (() -> Unit)? = null,
    /** Inserts a picture or PDF from the system clipboard, if there is one; null disables it. */
    onInsertFromClipboard: (() -> Unit)? = null,
    /** Asks for the name of a new anchor; null: no anchors. */
    onInsertAnchor: (() -> Unit)? = null,
    /** Asks for the target of a new link to a note; null: no such links. */
    onInsertNoteLink: (() -> Unit)? = null,
    onCanvasCreated: (NoteCanvasView) -> Unit = {},
    /** Extra content over the page, placed with its viewport (e.g. the document title). */
    pageOverlay: @Composable androidx.compose.foundation.layout.BoxScope.(Viewport) -> Unit = {},
    /** Added to the box holding the page and [pageOverlay], e.g. to watch presses on the page. */
    pageModifier: Modifier = Modifier,
) {
    val document by viewModel.document.collectAsStateWithLifecycle()
    val tools by viewModel.tools.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val clip by viewModel.clipboard.content.collectAsStateWithLifecycle()
    var canvasView by remember { mutableStateOf<NoteCanvasView?>(null) }
    var choosingBackground by remember { mutableStateOf(false) }
    val textEditing by viewModel.textEditing.collectAsStateWithLifecycle()
    val todoEditing by viewModel.todoEditing.collectAsStateWithLifecycle()
    var viewport by remember { mutableStateOf<Viewport?>(null) }
    val doneTasks = remember(document) { document.todos.filter { document.isDone(it.taskId) }.mapTo(HashSet()) { it.taskId } }

    // Store a typed to-do while typing, too.
    todoEditing?.text?.let { controller ->
        LaunchedEffect(todoEditing?.key) {
            snapshotFlow { controller.contentVersion }.drop(1).debounce(TEXT_SAVE_DELAY_MS).collect { viewModel.saveTodo() }
        }
    }

    // Store the text while typing, so nothing is lost if the app goes away.
    textEditing?.let { editing ->
        LaunchedEffect(editing.key) {
            snapshotFlow { editing.controller.contentVersion }.drop(1).debounce(TEXT_SAVE_DELAY_MS).collect { viewModel.saveText() }
        }
    }

    if (choosingBackground) {
        BackgroundDialog(
            initial = document.background,
            onDismiss = { choosingBackground = false },
            onConfirm = { choosingBackground = false; viewModel.setBackground(it) },
        )
    }

    Column(modifier.fillMaxSize()) {
        Toolbar(
            toolbarEnd = toolbarEnd,
            onMenu = onMenu,
            onBackground = { choosingBackground = true },
            tools = tools,
            canUndo = canUndo,
            canRedo = canRedo,
            onUndo = { viewModel.undo() },
            onRedo = { viewModel.redo() },
            onPen = viewModel::selectPen,
            onEraser = viewModel::selectEraser,
            hasSelection = selection.isNotEmpty(),
            canPaste = clip != null,
            onLasso = viewModel::selectLasso,
            onDeleteSelection = viewModel::deleteSelection,
            onCopySelection = viewModel::copySelection,
            onPaste = {
                // Into the middle of what is visible, kept on the page.
                canvasView?.viewport?.let { v ->
                    val r = v.visibleDocumentRect
                    val half = (clip?.width ?: 0f) / 2
                    val cx = ((r.left + r.right) / 2).coerceIn(half, maxOf(half, v.pageWidthMm - half))
                    viewModel.paste(cx, (r.top + r.bottom) / 2)
                }
            },
            onText = viewModel::selectText,
            onTodo = viewModel::selectTodo,
            onInsertFile = onInsertFile,
            onInsertFromClipboard = onInsertFromClipboard,
            onInsertAnchor = onInsertAnchor,
            onInsertNoteLink = onInsertNoteLink,
            textEditing = textEditing?.controller ?: todoEditing?.text,
            onTextDelete = { if (todoEditing != null) viewModel.deleteTodo() else viewModel.deleteText() },
            inkTodoOpen = todoEditing?.isInk == true,
            onDeleteTodo = viewModel::deleteTodo,
        )
        // With the on-screen keyboard up, the cursor of the edited text is kept visible: the
        // window either gets smaller, or (edge-to-edge) the keyboard covers the lower part of
        // the page, which then can scroll that much further. A hardware keyboard has no inset,
        // so nothing changes.
        val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
        val windowHeight = LocalView.current.rootView.height
        var pageBottomInWindow by remember { mutableFloatStateOf(0f) }
        val keyboardOverlap = if (imeBottom > 0) maxOf(0f, pageBottomInWindow - (windowHeight - imeBottom)) else 0f
        var cursorMm by remember { mutableStateOf<Pair<Float, Float>?>(null) }
        if (textEditing == null && todoEditing?.text == null) cursorMm = null
        LaunchedEffect(keyboardOverlap) { canvasView?.setBottomInset(keyboardOverlap) }
        val pageHeightPx = viewport?.viewHeightPx
        LaunchedEffect(imeBottom > 0, keyboardOverlap, pageHeightPx, cursorMm) {
            val c = cursorMm ?: return@LaunchedEffect
            if (imeBottom > 0) canvasView?.reveal(c.first, c.second)
        }
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxHeight().clipToBounds()
                    .onGloballyPositioned { pageBottomInWindow = it.boundsInWindow().bottom }
                    .then(pageModifier),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().testTag(NoteScreenTags.CANVAS),
                    factory = { context ->
                        NoteCanvasView(context).also {
                            it.onTextFrame = { x, y, width -> viewModel.requestText(x, y, width, it.viewport.pageWidthMm) }
                            it.onPageTapped = viewModel::tapAt
                            it.onPageLongPressed = viewModel::longPressAt
                            it.onSelectionResized = viewModel::resizeSelection
                            it.mediaRenderer.assetFile = assetFile
                            it.onViewportChanged = { v -> viewport = v }
                            viewModel.measureText = it.textRenderer::heightMm
                            it.onStrokeDrafted = viewModel::commitStroke
                            it.onShapeRequested = viewModel::commitShape
                            it.onEraseFinished = viewModel::commitErase
                            it.onLassoFinished = viewModel::selectByLasso
                            it.onSelectionMoved = viewModel::moveSelection
                            it.onSelectionCleared = viewModel::clearSelection
                            it.onTodoChecked = viewModel::toggleTodo
                            it.onTodoInk = viewModel::commitTodoInk
                            canvasView = it
                            onCanvasCreated(it)
                        }
                    },
                    update = { view ->
                        view.pageFormat = document.meta.format
                        view.pageBackground = document.background
                        view.brush = tools.activeBrush
                        view.drawWithFinger = drawWithFinger
                        view.showPageBreaks = showPageBreaks
                        view.tool = tools.tool
                        view.selection = selection
                        view.eraserMode = tools.eraserMode
                        view.eraserRadiusMm = tools.eraserRadiusMm
                        view.setStrokes(document.strokes)
                        view.setTextBoxes(document.textBoxes)
                        view.setFiles(document.files)
                        view.setLinks(document.links)
                        view.editingTextBox = textEditing?.boxId
                        view.setTodos(document.todos, doneTasks)
                        view.openTodo = todoEditing?.preview(PREVIEW_AUTHOR)
                        view.openTodoTyped = todoEditing?.text != null
                    },
                )
                val editing = textEditing
                val v = viewport ?: canvasView?.viewport
                if (editing != null && v != null && v.hasSize) {
                    TextBoxOverlay(
                        editing = editing,
                        viewport = v,
                        onMove = viewModel::moveText,
                        onResize = viewModel::resizeText,
                        onGestureEnd = viewModel::saveText,
                        onCursor = { top, bottom -> cursorMm = editing.y + top to editing.y + bottom },
                    )
                }
                val todo = todoEditing
                if (todo != null && v != null && v.hasSize) {
                    TodoOverlay(
                        todo, v, onMove = viewModel::moveTodo, onResize = viewModel::resizeTodo, onGestureEnd = viewModel::saveTodo,
                        onCursor = { top, bottom ->
                            val y = todo.y + com.stemaker.nibsync.model.TodoItem.PADDING_MM
                            cursorMm = y + top to y + bottom
                        },
                    )
                }
                if (v != null && v.hasSize) pageOverlay(v)
            }
            ToolSettingsBar(tools, onColor = viewModel::selectColor, onSize = viewModel::selectSize, onEraserSize = viewModel::selectEraserSize, onTodoInk = viewModel::selectTodoInk)
        }
    }
}

/**
 * Settings of the active tool on the right: colours and widths of the pen or highlighter, the
 * pixel eraser's sizes. Always the same width, so the page keeps its zoom when the tool changes.
 */
@Composable
private fun ToolSettingsBar(tools: ToolState, onColor: (Int) -> Unit, onSize: (Float) -> Unit, onEraserSize: (Float) -> Unit, onTodoInk: (Boolean) -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxHeight().width(TOOL_SETTINGS_WIDTH).testTag(NoteScreenTags.TOOL_SETTINGS)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (tools.tool) {
                Tool.PEN -> PenOptions(tools, onColor, onSize)
                Tool.ERASER -> EraserOptions(tools, onEraserSize)
                Tool.TODO -> {
                    // Typed or handwritten new to-dos; handwriting with the pen's colour and width.
                    SettingsToggle(R.string.todo_keyboard, Icons.Filled.Keyboard, !tools.todoInk, NoteScreenTags.TODO_KEYBOARD) { onTodoInk(false) }
                    SettingsToggle(R.string.todo_ink, Icons.Filled.Draw, tools.todoInk, NoteScreenTags.TODO_INK) { onTodoInk(true) }
                    if (tools.todoInk) {
                        HorizontalDivider(Modifier.width(36.dp).padding(vertical = 4.dp))
                        PenOptions(tools, onColor, onSize)
                    }
                }
                Tool.LASSO, Tool.TEXT -> Unit
            }
        }
    }
}

private val TOOL_SETTINGS_WIDTH = 60.dp

/** Author of the preview of the to-do being edited (never stored). */
private val PREVIEW_AUTHOR = com.stemaker.nibsync.model.DeviceId("preview")

@Composable
private fun SettingsToggle(labelRes: Int, icon: ImageVector, checked: Boolean, tag: String, onClick: () -> Unit) {
    val label = stringResource(labelRes)
    WithTooltip(label) {
        FilledIconToggleButton(checked = checked, onCheckedChange = { onClick() }, modifier = Modifier.testTag(tag)) {
            Icon(icon, contentDescription = label)
        }
    }
}

/**
 * Over the open to-do (the page draws its frame and circle): a handle to move it (top left), one
 * to resize it (bottom right corner) and, for a typed to-do, the text editor. Handwriting goes
 * through to the page, so the rest of the frame lets the pen through.
 */
@Composable
private fun TodoOverlay(
    edit: com.stemaker.nibsync.text.TodoEdit,
    viewport: Viewport,
    onMove: (Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit,
    onGestureEnd: () -> Unit,
    onCursor: (Float, Float) -> Unit,
) {
    val t = viewport.documentToView
    val scale by rememberUpdatedState(viewport.scale)
    val accent = MaterialTheme.colorScheme.primary
    val density = androidx.compose.ui.platform.LocalDensity.current
    fun dp(mm: Float) = with(density) { (mm * viewport.scale).toDp() }
    edit.text?.let { controller ->
        val focus = remember(edit.key) { FocusRequester() }
        LaunchedEffect(edit.key) { focus.requestFocus() }
        Box(Modifier.offset { IntOffset(t.mapX(edit.x + com.stemaker.nibsync.model.TodoItem.CONTENT_OFFSET_MM).roundToInt(), t.mapY(edit.y + com.stemaker.nibsync.model.TodoItem.PADDING_MM).roundToInt()) }) {
            TextBoxEditor(
                controller = controller,
                widthMm = edit.widthMm - com.stemaker.nibsync.model.TodoItem.CONTENT_OFFSET_MM - com.stemaker.nibsync.model.TodoItem.PADDING_MM,
                pxPerMm = viewport.scale,
                modifier = Modifier.focusRequester(focus),
                onCursor = onCursor,
            )
        }
    }
    val resizeLabel = stringResource(R.string.resize_todo)
    Box(Modifier.offset { IntOffset(t.mapX(edit.x).roundToInt(), t.mapY(edit.y).roundToInt()) }) {
        Icon(
            Icons.Filled.OpenWith,
            contentDescription = stringResource(R.string.move_todo),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .offset(x = (-14).dp, y = (-34).dp)
                .size(28.dp)
                .background(accent, CircleShape)
                .padding(4.dp)
                .testTag(NoteScreenTags.MOVE_TODO)
                .pointerInput(edit.key) {
                    detectDragGestures(onDragEnd = onGestureEnd) { change, drag ->
                        change.consume()
                        onMove(drag.x / scale, drag.y / scale)
                    }
                },
        )
        Box(
            Modifier
                .offset(x = dp(edit.widthMm) - 12.dp, y = dp(edit.heightMm) - 12.dp)
                .size(24.dp)
                .background(accent, CircleShape)
                .semantics { contentDescription = resizeLabel }
                .testTag(NoteScreenTags.RESIZE_TODO)
                .pointerInput(edit.key) {
                    detectDragGestures(onDragEnd = onGestureEnd) { change, drag ->
                        change.consume()
                        onResize(drag.x / scale, drag.y / scale)
                    }
                },
        )
    }
}

/**
 * The editor over the edited box, at the page zoom, with handles to move it (top left) and
 * to change its width (right edge).
 */
@Composable
private fun TextBoxOverlay(
    editing: TextEditing,
    viewport: Viewport,
    onMove: (Float, Float) -> Unit,
    onResize: (Float) -> Unit,
    onGestureEnd: () -> Unit,
    onCursor: (Float, Float) -> Unit,
) {
    val t = viewport.documentToView
    val scale by rememberUpdatedState(viewport.scale)
    val focus = remember(editing.key) { FocusRequester() }
    LaunchedEffect(editing.key) { focus.requestFocus() }
    val accent = MaterialTheme.colorScheme.primary
    val resizeLabel = stringResource(R.string.resize_text_box)
    Box(Modifier.offset { IntOffset(t.mapX(editing.x).roundToInt(), t.mapY(editing.y).roundToInt()) }) {
        TextBoxEditor(
            controller = editing.controller,
            widthMm = editing.widthMm,
            pxPerMm = viewport.scale,
            modifier = Modifier.focusRequester(focus).border(1.dp, accent.copy(alpha = 0.6f)),
            onCursor = onCursor,
        )
        Icon(
            Icons.Filled.OpenWith,
            contentDescription = stringResource(R.string.move_text_box),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .offset(x = (-14).dp, y = (-34).dp)
                .size(28.dp)
                .background(accent, CircleShape)
                .padding(4.dp)
                .testTag(NoteScreenTags.MOVE_TEXT)
                .pointerInput(editing.key) {
                    detectDragGestures(onDragEnd = onGestureEnd) { change, drag ->
                        change.consume()
                        onMove(drag.x / scale, drag.y / scale)
                    }
                },
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 10.dp)
                .size(width = 10.dp, height = 32.dp)
                .background(accent, RoundedCornerShape(5.dp))
                .semantics { contentDescription = resizeLabel }
                .testTag(NoteScreenTags.RESIZE_TEXT)
                .pointerInput(editing.key) {
                    detectHorizontalDragGestures(onDragEnd = onGestureEnd) { change, dx ->
                        change.consume()
                        onResize(dx / scale)
                    }
                },
        )
    }
}

/** Idle time after typing before the text box is stored. */
private const val TEXT_SAVE_DELAY_MS = 1500L

@Composable
private fun Toolbar(
    toolbarEnd: @Composable () -> Unit,
    onMenu: (() -> Unit)?,
    onBackground: () -> Unit,
    tools: ToolState,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPen: (BrushFamily) -> Unit,
    onEraser: (EraserMode) -> Unit,
    hasSelection: Boolean,
    canPaste: Boolean,
    onLasso: () -> Unit,
    onDeleteSelection: () -> Unit,
    onCopySelection: () -> Unit,
    onPaste: () -> Unit,
    onText: () -> Unit,
    onTodo: (ink: Boolean) -> Unit,
    onInsertFile: (() -> Unit)?,
    onInsertFromClipboard: (() -> Unit)?,
    onInsertAnchor: (() -> Unit)?,
    onInsertNoteLink: (() -> Unit)?,
    textEditing: com.stemaker.nibsync.text.TextEditController?,
    onTextDelete: () -> Unit,
    inkTodoOpen: Boolean,
    onDeleteTodo: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag(NoteScreenTags.TOOLBAR)) {
        // Tools stay in place; only the middle part (pen options, text formatting) scrolls when
        // it does not fit, so switching tools never moves the buttons.
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (textEditing != null) {
                ScrollingRow(Modifier.weight(1f)) {
                    TextFormatActions(textEditing, onDelete = onTextDelete)
                }
            } else {
                if (onMenu != null) {
                    val menuLabel = stringResource(R.string.library_menu)
                    WithTooltip(menuLabel) {
                        IconButton(onClick = onMenu, modifier = Modifier.testTag(NoteScreenTags.MENU)) {
                            Icon(Icons.Filled.Menu, contentDescription = menuLabel)
                        }
                    }
                    Divider()
                }
                val undoLabel = stringResource(R.string.undo)
                WithTooltip(undoLabel) {
                    IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.testTag(NoteScreenTags.UNDO)) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = undoLabel)
                    }
                }
                val redoLabel = stringResource(R.string.redo)
                WithTooltip(redoLabel) {
                    IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.testTag(NoteScreenTags.REDO)) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = redoLabel)
                    }
                }
                Divider()
                for (family in BrushFamily.entries) {
                    val label = stringResource(penLabel(family))
                    WithTooltip(label) {
                        FilledIconToggleButton(
                            checked = !tools.eraserActive && !tools.todoActive && tools.activePen == family,
                            onCheckedChange = { onPen(family) },
                            modifier = Modifier.testTag(NoteScreenTags.pen(family)),
                        ) {
                            Icon(penIcon(family), contentDescription = label)
                        }
                    }
                }
                for (mode in EraserMode.entries) {
                    val label = stringResource(if (mode == EraserMode.OBJECT) R.string.eraser_object else R.string.eraser_pixel)
                    WithTooltip(label) {
                        FilledIconToggleButton(
                            checked = tools.eraserActive && tools.eraserMode == mode,
                            onCheckedChange = { onEraser(mode) },
                            modifier = Modifier.testTag(NoteScreenTags.eraserMode(mode)),
                        ) {
                            Icon(eraserIcon(mode), contentDescription = label)
                        }
                    }
                }
                val lassoLabel = stringResource(R.string.lasso)
                WithTooltip(lassoLabel) {
                    FilledIconToggleButton(
                        checked = tools.lassoActive,
                        onCheckedChange = { onLasso() },
                        modifier = Modifier.testTag(NoteScreenTags.LASSO),
                    ) {
                        Icon(Icons.Filled.HighlightAlt, contentDescription = lassoLabel)
                    }
                }
                val textLabel = stringResource(R.string.text_tool)
                WithTooltip(textLabel) {
                    FilledIconToggleButton(
                        checked = tools.textActive,
                        onCheckedChange = { onText() },
                        modifier = Modifier.testTag(NoteScreenTags.TEXT),
                    ) {
                        Icon(Icons.Filled.TextFields, contentDescription = textLabel)
                    }
                }
                // Tapped with the pen, the to-do is handwritten; with a finger, it is typed.
                val todoLabel = stringResource(R.string.todo_tool)
                var pressedWithPen by remember { mutableStateOf(false) }
                WithTooltip(todoLabel) {
                    FilledIconToggleButton(
                        checked = tools.todoActive,
                        onCheckedChange = { onTodo(pressedWithPen) },
                        modifier = Modifier
                            .testTag(NoteScreenTags.TODO)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                        if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                                            pressedWithPen = e.changes.any { it.type == androidx.compose.ui.input.pointer.PointerType.Stylus }
                                        }
                                    }
                                }
                            },
                    ) {
                        Icon(Icons.Filled.TaskAlt, contentDescription = todoLabel)
                    }
                }
                if (onInsertFile != null) InsertMenu(onInsertFile, onInsertFromClipboard, onInsertAnchor, onInsertNoteLink)
                Divider()
                // Colours and widths are in the tool settings bar on the right.
                ScrollingRow(Modifier.weight(1f)) {
                    if (tools.tool == Tool.LASSO) SelectionActions(hasSelection, canPaste, onDeleteSelection, onCopySelection, onPaste)
                    if (inkTodoOpen) {
                        val label = stringResource(R.string.delete_todo)
                        WithTooltip(label) {
                            IconButton(onClick = onDeleteTodo, modifier = Modifier.testTag(NoteScreenTags.DELETE_TODO)) {
                                Icon(Icons.Filled.Delete, contentDescription = label)
                            }
                        }
                    }
                }
                Divider()
                val backgroundLabel = stringResource(BackgroundR.string.background_paper)
                WithTooltip(backgroundLabel) {
                    IconButton(onClick = onBackground, modifier = Modifier.testTag(NoteScreenTags.BACKGROUND)) {
                        Icon(Icons.Filled.GridOn, contentDescription = backgroundLabel)
                    }
                }
            }
            toolbarEnd()
        }
    }
}

@Composable
private fun PenOptions(tools: ToolState, onColor: (Int) -> Unit, onSize: (Float) -> Unit) {
    PenPalette.colors(tools.activePen).forEachIndexed { index, argb ->
        ColorSwatch(
            argb = argb,
            selected = tools.activeSettings.colorArgb == argb,
            description = stringResource(R.string.color_n, index + 1),
            modifier = Modifier.testTag(NoteScreenTags.color(index)),
            onClick = { onColor(argb) },
        )
    }
    HorizontalDivider(Modifier.width(36.dp).padding(vertical = 4.dp))
    tools.sizes(tools.activePen).forEachIndexed { index, mm ->
        SizeDot(
            diameter = (8 + index * 6).dp,
            selected = tools.activeSettings.sizeMm == mm,
            description = stringResource(R.string.width_mm, mm),
            modifier = Modifier.testTag(NoteScreenTags.size(index)),
            onClick = { onSize(mm) },
        )
    }
}

@Composable
private fun SelectionActions(
    hasSelection: Boolean,
    canPaste: Boolean,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
) {
    val actions = listOf(
        Triple(R.string.delete_selection, Icons.Filled.Delete, NoteScreenTags.DELETE_SELECTION) to (hasSelection to onDelete),
        Triple(R.string.copy_selection, Icons.Filled.ContentCopy, NoteScreenTags.COPY_SELECTION) to (hasSelection to onCopy),
        Triple(R.string.paste, Icons.Filled.ContentPaste, NoteScreenTags.PASTE) to (canPaste to onPaste),
    )
    for ((look, behaviour) in actions) {
        val (labelRes, icon, tag) = look
        val (enabled, onClick) = behaviour
        val label = stringResource(labelRes)
        WithTooltip(label) {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
                Icon(icon, contentDescription = label)
            }
        }
    }
}

@Composable
private fun EraserOptions(tools: ToolState, onEraserSize: (Float) -> Unit) {
    // The object eraser has a fixed tip; only the pixel eraser offers sizes.
    if (tools.eraserMode != EraserMode.PIXEL) return
    PenPalette.eraserSizes.forEachIndexed { index, mm ->
        SizeDot(
            diameter = (8 + index * 6).dp,
            selected = tools.eraserSizeMm == mm,
            description = stringResource(R.string.eraser_size_mm, mm),
            modifier = Modifier.testTag(NoteScreenTags.eraserSize(index)),
            onClick = { onEraserSize(mm) },
            hollow = true,
        )
    }
}

/** Shows [text] on long press, so the meaning of an icon can be looked up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WithTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
    ) {
        content()
    }
}

/** "Insert" button: pick a picture or file, take one from the clipboard, set an anchor or link a note. */
@Composable
private fun InsertMenu(onInsertFile: () -> Unit, onInsertFromClipboard: (() -> Unit)?, onInsertAnchor: (() -> Unit)?, onInsertNoteLink: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.insert)
    Box {
        WithTooltip(label) {
            IconButton(onClick = { open = true }, modifier = Modifier.testTag(NoteScreenTags.INSERT)) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = label)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.insert_file)) },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                onClick = { open = false; onInsertFile() },
                modifier = Modifier.testTag(NoteScreenTags.INSERT_FILE),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.insert_clipboard)) },
                leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null) },
                enabled = onInsertFromClipboard != null,
                onClick = { open = false; onInsertFromClipboard?.invoke() },
                modifier = Modifier.testTag(NoteScreenTags.INSERT_CLIPBOARD),
            )
            if (onInsertAnchor != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.insert_anchor)) },
                    leadingIcon = { Icon(Icons.Filled.Flag, contentDescription = null) },
                    onClick = { open = false; onInsertAnchor() },
                    modifier = Modifier.testTag(NoteScreenTags.INSERT_ANCHOR),
                )
            }
            if (onInsertNoteLink != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.insert_note_link)) },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    onClick = { open = false; onInsertNoteLink() },
                    modifier = Modifier.testTag(NoteScreenTags.INSERT_NOTE_LINK),
                )
            }
        }
    }
}

@Composable
private fun ScrollingRow(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun Divider() {
    Spacer(Modifier.width(4.dp))
    VerticalDivider(Modifier.height(32.dp))
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun ColorSwatch(argb: Int, selected: Boolean, description: String, modifier: Modifier, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier
            .size(36.dp)
            .border(3.dp, ring, CircleShape)
            .padding(6.dp)
            .background(Color(argb), CircleShape)
            .border(1.dp, Color(0x33000000), CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
    )
}

@Composable
private fun SizeDot(
    diameter: androidx.compose.ui.unit.Dp,
    selected: Boolean,
    description: String,
    modifier: Modifier,
    onClick: () -> Unit,
    hollow: Boolean = false,
) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier
            .size(40.dp)
            .border(3.dp, ring, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        val dot = Modifier.size(diameter)
        Box(if (hollow) dot.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else dot.background(MaterialTheme.colorScheme.onSurface, CircleShape))
    }
}

private fun penIcon(family: BrushFamily): ImageVector = when (family) {
    BrushFamily.BALLPOINT -> Icons.Filled.Draw
    BrushFamily.FOUNTAIN_PEN -> Icons.Filled.HistoryEdu
    BrushFamily.HIGHLIGHTER -> Icons.Filled.BorderColor
}

private fun penLabel(family: BrushFamily): Int = when (family) {
    BrushFamily.BALLPOINT -> R.string.pen_ballpoint
    BrushFamily.FOUNTAIN_PEN -> R.string.pen_fountain
    BrushFamily.HIGHLIGHTER -> R.string.pen_highlighter
}

@Composable
private fun BackgroundDialog(initial: BackgroundSpec, onDismiss: () -> Unit, onConfirm: (BackgroundSpec) -> Unit) {
    var choice by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(BackgroundR.string.background_paper)) },
        text = { BackgroundPicker(choice, { choice = it }) },
        confirmButton = {
            TextButton(onClick = { onConfirm(choice) }, modifier = Modifier.testTag(NoteScreenTags.BACKGROUND_CONFIRM)) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
