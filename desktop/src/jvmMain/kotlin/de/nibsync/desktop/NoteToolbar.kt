package de.nibsync.desktop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import de.nibsync.model.RichText
import de.nibsync.model.TextFont
import de.nibsync.text.TextEditController
import de.nibsync.text.TextFonts
import de.nibsync.text.TextTags

/**
 * Tool bar controls never take the keyboard focus: clicking one must leave the cursor and the
 * selection in the open text box, so "bold" applies to the selected text.
 */
private val NotFocusable = Modifier.focusProperties { canFocus = false }

object ToolbarTags {
    const val UNDO = "undo"
    const val REDO = "redo"
}

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
            if (editing != null) {
                Divider()
                Toggle(strings.bold, Icons.Filled.FormatBold, editing.isBold, TextTags.BOLD, editing::toggleBold)
                Toggle(strings.italic, Icons.Filled.FormatItalic, editing.isItalic, TextTags.ITALIC, editing::toggleItalic)
                Divider()
                FontMenu(strings, editing)
                SizeMenu(strings, editing)
                Divider()
                Toggle(strings.bullets, Icons.AutoMirrored.Filled.FormatListBulleted, editing.isBulletList, TextTags.BULLETS, editing::toggleBulletList)
                Toggle(strings.numbers, Icons.Filled.FormatListNumbered, editing.isNumberedList, TextTags.NUMBERS, editing::toggleNumberedList)
                Action(strings.outdent, Icons.AutoMirrored.Filled.FormatIndentDecrease, editing.canOutdent, TextTags.OUTDENT, editing::outdent)
                Action(strings.indent, Icons.AutoMirrored.Filled.FormatIndentIncrease, editing.canIndent, TextTags.INDENT, editing::indent)
                Divider()
                Action(strings.deleteTextBox, Icons.Filled.Delete, true, TextTags.DELETE, onDeleteText)
            }
            end()
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
