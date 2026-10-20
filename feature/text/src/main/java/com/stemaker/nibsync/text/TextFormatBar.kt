package com.stemaker.nibsync.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TextFont

/**
 * Tool bar items shown instead of the drawing tools while a text box is being edited. Editing
 * ends by tapping the page outside the box.
 */
@Composable
fun TextFormatActions(controller: TextEditController, onDelete: () -> Unit) {
    Toggle(R.string.text_bold, Icons.Filled.FormatBold, controller.isBold, TextTags.BOLD, controller::toggleBold)
    Toggle(R.string.text_italic, Icons.Filled.FormatItalic, controller.isItalic, TextTags.ITALIC, controller::toggleItalic)
    ToolbarDivider()
    FontMenu(controller)
    Action(R.string.text_shrink, Icons.Filled.TextDecrease, controller.canShrink, TextTags.SHRINK, controller::shrink)
    SizeMenu(controller)
    Action(R.string.text_grow, Icons.Filled.TextIncrease, controller.canGrow, TextTags.GROW, controller::grow)
    ToolbarDivider()
    Toggle(R.string.text_bullets, Icons.AutoMirrored.Filled.FormatListBulleted, controller.isBulletList, TextTags.BULLETS, controller::toggleBulletList)
    Toggle(R.string.text_numbers, Icons.Filled.FormatListNumbered, controller.isNumberedList, TextTags.NUMBERS, controller::toggleNumberedList)
    Action(R.string.text_outdent, Icons.AutoMirrored.Filled.FormatIndentDecrease, controller.canOutdent, TextTags.OUTDENT, controller::outdent)
    Action(R.string.text_indent, Icons.AutoMirrored.Filled.FormatIndentIncrease, controller.canIndent, TextTags.INDENT, controller::indent)
    ToolbarDivider()
    Action(R.string.text_delete, Icons.Filled.Delete, true, TextTags.DELETE, onDelete)
}

@Composable
private fun FontMenu(controller: TextEditController) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag(TextTags.FONT)) {
            Text(stringResource(fontLabel(controller.font)), fontFamily = TextFonts.family(controller.font))
        }
        // Not focusable: the editor keeps the focus and with it the cursor and pending style.
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            for (font in TextFont.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(fontLabel(font)), fontFamily = TextFonts.family(font)) },
                    onClick = { open = false; controller.setFont(font) },
                    modifier = Modifier.testTag(TextTags.font(font)),
                )
            }
        }
    }
}

@Composable
private fun SizeMenu(controller: TextEditController) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag(TextTags.SIZE)) {
            Text(stringResource(R.string.text_size_pt, formatPt(controller.sizePt)))
        }
        // Not focusable: the editor keeps the focus and with it the cursor and pending style.
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            for (pt in RichText.SIZES_PT) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.text_size_pt, formatPt(pt))) },
                    onClick = { open = false; controller.setSize(pt) },
                    modifier = Modifier.testTag(TextTags.size(pt)),
                )
            }
        }
    }
}

private fun formatPt(pt: Float): String = if (pt % 1f == 0f) pt.toInt().toString() else pt.toString()

private fun fontLabel(font: TextFont) = when (font) {
    TextFont.SANS -> R.string.font_sans
    TextFont.SERIF -> R.string.font_serif
    TextFont.MONO -> R.string.font_mono
    TextFont.HANDWRITING -> R.string.font_handwriting
}

@Composable
private fun Toggle(labelRes: Int, icon: ImageVector, checked: Boolean, tag: String, onClick: () -> Unit) {
    val label = stringResource(labelRes)
    WithTooltip(label) {
        FilledIconToggleButton(checked = checked, onCheckedChange = { onClick() }, modifier = Modifier.testTag(tag)) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun Action(labelRes: Int, icon: ImageVector, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val label = stringResource(labelRes)
    WithTooltip(label) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
            Icon(icon, contentDescription = label)
        }
    }
}

// Same look as the drawing tool bar's helpers in feature/canvas.
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
private fun ToolbarDivider() {
    Spacer(Modifier.width(4.dp))
    VerticalDivider(Modifier.height(32.dp))
    Spacer(Modifier.width(4.dp))
}
