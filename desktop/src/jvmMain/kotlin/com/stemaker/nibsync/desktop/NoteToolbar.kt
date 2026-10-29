package com.stemaker.nibsync.desktop

import androidx.compose.material.icons.filled.TableChart
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.stemaker.nibsync.document.erase.EraserMode
import com.stemaker.nibsync.model.BrushFamily
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TextFont
import com.stemaker.nibsync.text.TextEditController
import com.stemaker.nibsync.text.TextFonts
import com.stemaker.nibsync.text.TextPreset
import com.stemaker.nibsync.text.TextTags

/**
 * Tool bar controls never take the keyboard focus: clicking one must leave the cursor and the
 * selection in the open text box, so "bold" applies to the selected text.
 */
private val NotFocusable = Modifier.focusProperties { canFocus = false }

object ToolbarTags {
    const val UNDO = "undo"
    const val REDO = "redo"
    const val TEXT = "tool-text"
    fun pen(family: BrushFamily) = "pen-${family.name}"
    fun eraser(mode: EraserMode) = "eraser-${mode.name}"
    const val ERASER = "tool-eraser"
    const val TEXT_DONE = "text-done"
    const val INSERT_TABLE = "insert-table"
    const val TABLE_MENU = "table-menu"
    fun tableAction(name: String) = "table-$name"
    const val SELECTION_BAR = "selection-bar"
    const val LASSO = "tool-lasso"
    const val TODO = "tool-todo"
    const val TODO_KEYBOARD = "todo-keyboard"
    const val TODO_INK = "todo-ink"
    const val INSERT = "insert"
    const val INSERT_FILE = "insert-file"
    const val INSERT_ANCHOR = "insert-anchor"
    const val INSERT_NOTE_LINK = "insert-note-link"
    fun color(index: Int) = "color-$index"
    fun size(index: Int) = "size-$index"
    fun eraserSize(index: Int) = "eraser-size-$index"
    const val DELETE_SELECTION = "delete-selection"
    const val COPY_SELECTION = "copy-selection"
    const val CUT_SELECTION = "cut-selection"
    const val PASTE = "paste"
    const val TOOL_SETTINGS = "tool-settings"
}

/** Tools and their options; part of the tool bar while no text box is open. */
class ToolbarTools(
    val state: DesktopToolState,
    val hasSelection: Boolean,
    val canPaste: Boolean,
    val onTool: (DesktopTool) -> Unit,
    val onPen: (BrushFamily) -> Unit,
    val onEraser: (EraserMode) -> Unit,
    val onColor: (Int) -> Unit,
    val onSize: (Float) -> Unit,
    val onEraserSize: (Float) -> Unit,
    val onInsert: () -> Unit,
    val onDeleteSelection: () -> Unit,
    val onCopySelection: () -> Unit,
    val onCutSelection: () -> Unit,
    val onPaste: () -> Unit,
    /** The to-do tool was chosen; [ink] if with a pen (graphics tablet), else typed. */
    val onTodo: (ink: Boolean) -> Unit = {},
    val onTodoInk: (Boolean) -> Unit = {},
    val onInsertAnchor: () -> Unit = {},
    val onInsertNoteLink: () -> Unit = {},
    val onInsertTable: () -> Unit = {},
)

/** Tool bar above the page: undo/redo, and text formatting while a box is open. */
@Composable
fun NoteToolbar(
    strings: Strings,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    editing: TextEditController?,
    onDeleteText: () -> Unit,
    tools: ToolbarTools? = null,
    /** Ends text editing ("Done"). */
    onDoneText: () -> Unit = {},
    /** The table whose cell is being edited: its rows, columns and joined cells. */
    table: com.stemaker.nibsync.text.TableEditing? = null,
    /** Items at the right end, e.g. the sync status. */
    end: @Composable () -> Unit = {},
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Action(strings.undo, Icons.AutoMirrored.Filled.Undo, canUndo, ToolbarTags.UNDO, onUndo)
            Action(strings.redo, Icons.AutoMirrored.Filled.Redo, canRedo, ToolbarTags.REDO, onRedo)
            // While a text box is open only its formatting is shown (the tool bar would get too
            // wide otherwise); "Done", Esc or a click beside the box brings the tools back.
            if (editing == null && tools != null) {
                ToolButtons(strings, tools)
                ToolOptions(strings, tools)
            }
            if (editing != null) {
                Divider()
                FilledTonalButton(onClick = onDoneText, modifier = NotFocusable.testTag(ToolbarTags.TEXT_DONE)) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.textDone)
                }
                Divider()
                PresetMenu(strings, editing)
                Divider()
                Toggle(strings.bold, Icons.Filled.FormatBold, editing.isBold, TextTags.BOLD, editing::toggleBold)
                Toggle(strings.italic, Icons.Filled.FormatItalic, editing.isItalic, TextTags.ITALIC, editing::toggleItalic)
                Divider()
                FontMenu(strings, editing)
                Action(strings.textShrink, Icons.Filled.TextDecrease, editing.canShrink, TextTags.SHRINK, editing::shrink)
                SizeMenu(strings, editing)
                Action(strings.textGrow, Icons.Filled.TextIncrease, editing.canGrow, TextTags.GROW, editing::grow)
                Divider()
                Toggle(strings.bullets, Icons.AutoMirrored.Filled.FormatListBulleted, editing.isBulletList, TextTags.BULLETS, editing::toggleBulletList)
                Toggle(strings.numbers, Icons.Filled.FormatListNumbered, editing.isNumberedList, TextTags.NUMBERS, editing::toggleNumberedList)
                Action(strings.outdent, Icons.AutoMirrored.Filled.FormatIndentDecrease, editing.canOutdent, TextTags.OUTDENT, editing::outdent)
                Action(strings.indent, Icons.AutoMirrored.Filled.FormatIndentIncrease, editing.canIndent, TextTags.INDENT, editing::indent)
                Divider()
                if (table != null) {
                    TableMenu(strings, table)
                    Divider()
                }
                Action(if (table != null) strings.deleteTable else strings.deleteTextBox, Icons.Filled.Delete, true, TextTags.DELETE, onDeleteText)
            }
            end()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ToolButtons(strings: Strings, t: ToolbarTools) {
    val s = t.state
    // Writing: the pens, each with its colour, and the eraser.
    ToolGroup {
        for (family in BrushFamily.entries) {
            val (label, icon) = when (family) {
                BrushFamily.BALLPOINT -> strings.penBallpoint to Icons.Filled.Draw
                BrushFamily.FOUNTAIN_PEN -> strings.penFountain to Icons.Filled.HistoryEdu
                BrushFamily.HIGHLIGHTER -> strings.penHighlighter to Icons.Filled.BorderColor
            }
            WithTooltip(label) {
                FilledIconToggleButton(
                    checked = s.tool == DesktopTool.PEN && s.pen == family,
                    onCheckedChange = { t.onPen(family) },
                    modifier = NotFocusable.testTag(ToolbarTags.pen(family)),
                ) {
                    Box {
                        Icon(icon, contentDescription = label)
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 3.dp, y = 3.dp)
                                .size(9.dp)
                                .background(Color(s.pens.getValue(family).colorArgb).copy(alpha = 1f), CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
                        )
                    }
                }
            }
        }
        Toggle(strings.eraser, eraserIcon(s.eraserMode), s.tool == DesktopTool.ERASER, ToolbarTags.ERASER) { t.onEraser(s.eraserMode) }
    }
    // Placing things: text, selection, to-dos and inserted content.
    ToolGroup {
        Toggle(strings.toolText, Icons.Filled.TextFields, s.tool == DesktopTool.TEXT, ToolbarTags.TEXT) { t.onTool(DesktopTool.TEXT) }
        Toggle(strings.lasso, Icons.Filled.HighlightAlt, s.tool == DesktopTool.LASSO, ToolbarTags.LASSO) { t.onTool(DesktopTool.LASSO) }
        // A pen of a graphics tablet writes the to-do by hand, the mouse types it.
        var pressedWithPen by remember { mutableStateOf(false) }
        Box(
            Modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { e ->
                pressedWithPen = e.changes.any { it.type == PointerType.Stylus }
            },
        ) {
            Toggle(strings.toolTodo, Icons.Filled.TaskAlt, s.tool == DesktopTool.TODO, ToolbarTags.TODO) { t.onTodo(pressedWithPen) }
        }
        InsertMenu(strings, t)
    }
}

/** Buttons that belong together, on a common rounded background. */
@Composable
private fun ToolGroup(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/** Insert: a picture or file, an anchor, a link to a note. */
@Composable
private fun InsertMenu(strings: Strings, t: ToolbarTools) {
    var open by remember { mutableStateOf(false) }
    Box {
        Action(strings.insert, Icons.Filled.AddPhotoAlternate, true, ToolbarTags.INSERT) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(strings.insertFile) },
                leadingIcon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
                onClick = { open = false; t.onInsert() },
                modifier = Modifier.testTag(ToolbarTags.INSERT_FILE),
            )
            DropdownMenuItem(
                text = { Text(strings.insertAnchor) },
                leadingIcon = { Icon(Icons.Filled.Flag, contentDescription = null) },
                onClick = { open = false; t.onInsertAnchor() },
                modifier = Modifier.testTag(ToolbarTags.INSERT_ANCHOR),
            )
            DropdownMenuItem(
                text = { Text(strings.insertTable) },
                leadingIcon = { Icon(Icons.Filled.TableChart, contentDescription = null) },
                onClick = { open = false; t.onInsertTable() },
                modifier = Modifier.testTag(ToolbarTags.INSERT_TABLE),
            )
            DropdownMenuItem(
                text = { Text(strings.insertNoteLink) },
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                onClick = { open = false; t.onInsertNoteLink() },
                modifier = Modifier.testTag(ToolbarTags.INSERT_NOTE_LINK),
            )
        }
    }
}

/** Options of the active tool, right next to the tools: colours, widths, eraser modes, paste. */
@Composable
private fun ToolOptions(strings: Strings, t: ToolbarTools) {
    val s = t.state
    Divider()
    Row(NotFocusable.testTag(ToolbarTags.TOOL_SETTINGS), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        when (s.tool) {
            DesktopTool.PEN -> PenOptions(strings, t)
            DesktopTool.ERASER -> {
                for (mode in EraserMode.entries) {
                    val label = if (mode == EraserMode.OBJECT) strings.eraserObject else strings.eraserPixel
                    Toggle(label, eraserIcon(mode), s.eraserMode == mode, ToolbarTags.eraser(mode)) { t.onEraser(mode) }
                }
                // The object eraser has a fixed tip; only the pixel eraser offers sizes.
                if (s.eraserMode == EraserMode.PIXEL) {
                    Divider()
                    DesktopPalette.eraserSizes.forEachIndexed { i, mm ->
                        Dot((8 + i * 5).dp, s.eraserSizeMm == mm, strings.width(mm), ToolbarTags.eraserSize(i)) { t.onEraserSize(mm) }
                    }
                }
            }
            DesktopTool.TODO -> {
                Toggle(strings.todoKeyboard, Icons.Filled.Keyboard, !s.todoInk, ToolbarTags.TODO_KEYBOARD) { t.onTodoInk(false) }
                Toggle(strings.todoInk, Icons.Filled.Draw, s.todoInk, ToolbarTags.TODO_INK) { t.onTodoInk(true) }
                if (s.todoInk) {
                    Divider()
                    PenOptions(strings, t)
                }
            }
            DesktopTool.LASSO -> Action(strings.paste, Icons.Filled.ContentPaste, t.canPaste, ToolbarTags.PASTE, t.onPaste)
            DesktopTool.TEXT -> Unit
        }
    }
}

@Composable
private fun PenOptions(strings: Strings, t: ToolbarTools) {
    val s = t.state
    DesktopPalette.colors(s.pen).forEachIndexed { i, argb ->
        Swatch(argb, s.choice.colorArgb == argb, strings.color(i + 1), ToolbarTags.color(i)) { t.onColor(argb) }
    }
    Divider()
    s.sizes(s.pen).forEachIndexed { i, mm ->
        Dot((8 + i * 5).dp, s.choice.sizeMm == mm, strings.width(mm), ToolbarTags.size(i)) { t.onSize(mm) }
    }
}

/** Actions for the selection, floating just above it on the page (below it without room above). */
@Composable
fun SelectionBar(strings: Strings, frame: com.stemaker.nibsync.model.geometry.Rect, viewport: com.stemaker.nibsync.model.geometry.Viewport, t: ToolbarTools) {
    val tr = viewport.documentToView
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val gap = 10f
    val centerX = (tr.mapX(frame.left) + tr.mapX(frame.right)) / 2
    val above = tr.mapY(frame.top) - size.height - gap
    val y = if (above >= gap) above else tr.mapY(frame.bottom) + gap
    val x = (centerX - size.width / 2).coerceIn(gap, maxOf(gap, viewport.viewWidthPx - size.width - gap))
    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .offset { androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()) }
            .onSizeChanged { size = it }
            .testTag(ToolbarTags.SELECTION_BAR),
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Action(strings.deleteSelection, Icons.Filled.Delete, true, ToolbarTags.DELETE_SELECTION, t.onDeleteSelection)
            Action(strings.copySelection, Icons.Filled.ContentCopy, true, ToolbarTags.COPY_SELECTION, t.onCopySelection)
            Action(strings.cutSelection, Icons.Filled.ContentCut, true, ToolbarTags.CUT_SELECTION, t.onCutSelection)
        }
    }
}

@Composable
private fun Swatch(argb: Int, selected: Boolean, label: String, tag: String, onClick: () -> Unit) {
    WithTooltip(label) {
        Box(
            NotFocusable
                .size(30.dp)
                .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                .padding(4.dp)
                .background(Color(argb), CircleShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = label; this.selected = selected }
                .testTag(tag),
        )
    }
}

@Composable
private fun Dot(diameter: androidx.compose.ui.unit.Dp, selected: Boolean, label: String, tag: String, onClick: () -> Unit) {
    WithTooltip(label) {
        Box(
            NotFocusable
                .size(30.dp)
                .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = label; this.selected = selected }
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(diameter).background(MaterialTheme.colorScheme.onSurface, CircleShape))
        }
    }
}

@Composable
private fun FontMenu(strings: Strings, controller: TextEditController) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = NotFocusable.testTag(TextTags.FONT)) {
            Text(strings.font(controller.font), fontFamily = TextFonts.family(controller.font))
        }
        // Not focusable: the editor keeps the focus, the cursor and the pending style.
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            for (font in TextFont.entries) {
                DropdownMenuItem(
                    text = { Text(strings.font(font), fontFamily = TextFonts.family(font)) },
                    onClick = { open = false; controller.setFont(font) },
                    modifier = NotFocusable.testTag(TextTags.font(font)),
                )
            }
        }
    }
}

@Composable
private fun PresetMenu(strings: Strings, controller: TextEditController) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = NotFocusable.testTag(TextTags.PRESET)) {
            Text(controller.preset?.let(strings::preset) ?: strings.textStyle)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            for (preset in TextPreset.entries) {
                DropdownMenuItem(
                    text = {
                        // Each entry looks like its style (headings a little smaller than on the page).
                        Text(
                            strings.preset(preset),
                            fontFamily = TextFonts.family(preset.font),
                            fontWeight = if (preset.bold) FontWeight.Bold else FontWeight.Normal,
                            fontSize = minOf(preset.sizePt, 20f).sp,
                        )
                    },
                    onClick = { open = false; controller.applyPreset(preset) },
                    modifier = NotFocusable.testTag(TextTags.preset(preset)),
                )
            }
        }
    }
}

@Composable
private fun SizeMenu(strings: Strings, controller: TextEditController) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = NotFocusable.testTag(TextTags.SIZE)) {
            Text(strings.sizePt(formatPt(controller.sizePt)))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            for (pt in RichText.SIZES_PT) {
                DropdownMenuItem(
                    text = { Text(strings.sizePt(formatPt(pt))) },
                    onClick = { open = false; controller.setSize(pt) },
                    modifier = NotFocusable.testTag(TextTags.size(pt)),
                )
            }
        }
    }
}

private fun formatPt(pt: Float): String = if (pt % 1f == 0f) pt.toInt().toString() else pt.toString()

@Composable
private fun Toggle(label: String, icon: ImageVector, checked: Boolean, tag: String, onClick: () -> Unit) {
    WithTooltip(label) {
        FilledIconToggleButton(checked = checked, onCheckedChange = { onClick() }, modifier = NotFocusable.testTag(tag)) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun Action(label: String, icon: ImageVector, enabled: Boolean, tag: String, onClick: () -> Unit) {
    WithTooltip(label) {
        IconButton(onClick = onClick, enabled = enabled, modifier = NotFocusable.testTag(tag)) {
            Icon(icon, contentDescription = label)
        }
    }
}

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

@Composable
private fun Divider() {
    Spacer(Modifier.width(4.dp))
    VerticalDivider(Modifier.height(28.dp))
    Spacer(Modifier.width(4.dp))
}

/** "Table" menu while a cell is open: rows, columns, joined cells, header. */
@Composable
private fun TableMenu(strings: Strings, table: com.stemaker.nibsync.text.TableEditing) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = NotFocusable.testTag(ToolbarTags.TABLE_MENU)) {
            Icon(Icons.Filled.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(strings.table)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            @Composable
            fun item(label: String, name: String, action: () -> Unit) = DropdownMenuItem(
                text = { Text(label) },
                onClick = { open = false; action() },
                modifier = NotFocusable.testTag(ToolbarTags.tableAction(name)),
            )
            item(strings.rowAbove, "row-above") { table.insertRow(below = false) }
            item(strings.rowBelow, "row-below") { table.insertRow(below = true) }
            item(strings.columnLeft, "column-left") { table.insertColumn(right = false) }
            item(strings.columnRight, "column-right") { table.insertColumn(right = true) }
            HorizontalDivider()
            item(strings.joinRight, "join-right") { table.join(right = true) }
            item(strings.joinDown, "join-down") { table.join(right = false) }
            item(strings.splitCells, "split") { table.split() }
            HorizontalDivider()
            item(strings.deleteRow, "delete-row") { table.deleteRow() }
            item(strings.deleteColumn, "delete-column") { table.deleteColumn() }
            item(strings.headerRow, "header") { table.toggleHeader() }
        }
    }
}
