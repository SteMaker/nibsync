package de.nibsync.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import de.nibsync.model.geometry.Viewport
import de.nibsync.text.TextBoxEditor
import de.nibsync.text.TextEditing
import de.nibsync.text.TextFormatActions
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import de.nibsync.document.erase.EraserMode
import de.nibsync.background.ui.BackgroundPicker
import de.nibsync.model.Asset
import de.nibsync.model.BackgroundSpec
import java.io.File
import de.nibsync.model.BrushFamily
import de.nibsync.background.ui.R as BackgroundR

/** Test tags used by UI tests. */
object NoteScreenTags {
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
    const val MOVE_TEXT = "move-text"
    const val RESIZE_TEXT = "resize-text"
    const val DELETE_SELECTION = "delete-selection"
    const val COPY_SELECTION = "copy-selection"
    const val PASTE = "paste"
    const val REDO = "redo"
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
    onCanvasCreated: (NoteCanvasView) -> Unit = {},
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
    var viewport by remember { mutableStateOf<Viewport?>(null) }

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
            onColor = viewModel::selectColor,
            onSize = viewModel::selectSize,
            onEraser = viewModel::selectEraser,
            onEraserSize = viewModel::selectEraserSize,
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
            onInsertFile = onInsertFile,
            onInsertFromClipboard = onInsertFromClipboard,
            textEditing = textEditing,
            onTextDelete = viewModel::deleteText,
        )
        Box(Modifier.fillMaxSize().clipToBounds()) {
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
                    view.editingTextBox = textEditing?.boxId
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
                )
            }
        }
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
    onColor: (Int) -> Unit,
    onSize: (Float) -> Unit,
    onEraser: (EraserMode) -> Unit,
    onEraserSize: (Float) -> Unit,
    hasSelection: Boolean,
    canPaste: Boolean,
    onLasso: () -> Unit,
    onDeleteSelection: () -> Unit,
    onCopySelection: () -> Unit,
    onPaste: () -> Unit,
    onText: () -> Unit,
    onInsertFile: (() -> Unit)?,
    onInsertFromClipboard: (() -> Unit)?,
    textEditing: TextEditing?,
    onTextDelete: () -> Unit,
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
                    TextFormatActions(textEditing.controller, onDelete = onTextDelete)
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
                            checked = !tools.eraserActive && tools.activePen == family,
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
                if (onInsertFile != null) InsertMenu(onInsertFile, onInsertFromClipboard)
                Divider()
                ScrollingRow(Modifier.weight(1f)) {
                    when (tools.tool) {
                        Tool.ERASER -> EraserOptions(tools, onEraserSize)
                        Tool.LASSO -> SelectionActions(hasSelection, canPaste, onDeleteSelection, onCopySelection, onPaste)
                        Tool.PEN -> PenOptions(tools, onColor, onSize)
                        Tool.TEXT -> Unit
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
    Divider()
    PenPalette.sizes(tools.activePen).forEachIndexed { index, mm ->
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

/** "Insert" button: pick a picture or PDF file, or take one from the clipboard. */
@Composable
private fun InsertMenu(onInsertFile: () -> Unit, onInsertFromClipboard: (() -> Unit)?) {
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
