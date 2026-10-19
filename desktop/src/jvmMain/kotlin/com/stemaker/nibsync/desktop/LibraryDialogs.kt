package com.stemaker.nibsync.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.stemaker.nibsync.library.LibraryNode
import com.stemaker.nibsync.model.BackgroundKind
import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.Orientation
import com.stemaker.nibsync.model.PageFormat
import com.stemaker.nibsync.model.PaperSize

object LibraryDialogTags {
    const val NAME = "library-dialog-name"
    const val CONFIRM = "library-dialog-confirm"
    fun paper(size: PaperSize) = "library-paper-${size.name}"
    fun orientation(o: Orientation) = "library-orientation-${o.name}"
    fun background(kind: BackgroundKind) = "library-background-${kind.name}"
    fun spacing(mm: Float) = "library-spacing-${mm.toInt()}"
}

/**
 * Asks for a name, with the keyboard focus in the field. Renaming starts with the old name
 * selected, so typing replaces it; an empty field uses [placeholder].
 */
@Composable
fun LibraryNameDialog(title: String, strings: Strings, placeholder: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(strings.name) },
                placeholder = { Text(placeholder) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(LibraryDialogTags.NAME),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.text.trim().ifEmpty { placeholder }) }, modifier = Modifier.testTag(LibraryDialogTags.CONFIRM)) {
                Text(if (initial.isEmpty()) strings.create else strings.save)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

/** Name, paper width (DIN size and orientation) and pattern of a new document, as on the tablet. */
@Composable
fun NewDocumentDialog(strings: Strings, onDismiss: () -> Unit, onConfirm: (String, PageFormat, BackgroundSpec) -> Unit) {
    var name by remember { mutableStateOf("") }
    var paper by remember { mutableStateOf(PaperSize.A4) }
    var orientation by remember { mutableStateOf(Orientation.PORTRAIT) }
    var background by remember { mutableStateOf(BackgroundSpec()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.newDocument) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(strings.name) },
                    placeholder = { Text(strings.newPage) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(LibraryDialogTags.NAME),
                )
                Text(strings.paperWidth, style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (size in PaperSize.entries) {
                        FilterChip(selected = paper == size, onClick = { paper = size }, label = { Text(size.name) }, modifier = Modifier.testTag(LibraryDialogTags.paper(size)))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (o in Orientation.entries) {
                        FilterChip(
                            selected = orientation == o, onClick = { orientation = o },
                            label = { Text(if (o == Orientation.PORTRAIT) strings.portrait else strings.landscape) },
                            modifier = Modifier.testTag(LibraryDialogTags.orientation(o)),
                        )
                    }
                }
                Text(strings.widthInfo(PageFormat(paper, orientation).widthMm.toInt()), style = MaterialTheme.typography.bodySmall)
                Text(strings.paper, style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (kind in BackgroundKind.entries) {
                        FilterChip(
                            selected = background.kind == kind,
                            onClick = { if (background.kind != kind) background = BackgroundSpec.of(kind) },
                            label = { Text(strings.background(kind)) },
                            modifier = Modifier.testTag(LibraryDialogTags.background(kind)),
                        )
                    }
                }
                if (background.kind != BackgroundKind.BLANK) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(strings.spacing, style = MaterialTheme.typography.bodySmall)
                        for (mm in BackgroundSpec.SPACINGS_MM) {
                            FilterChip(
                                selected = background.spacingMm == mm,
                                onClick = { background = background.copy(spacingMm = mm) },
                                label = { Text("${mm.toInt()} mm") },
                                modifier = Modifier.testTag(LibraryDialogTags.spacing(mm)),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim().ifEmpty { strings.newPage }, PageFormat(paper, orientation), background) },
                modifier = Modifier.testTag(LibraryDialogTags.CONFIRM),
            ) { Text(strings.create) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

/** Confirms deleting an entry (and, for a book or chapter, everything inside). */
@Composable
fun DeleteEntryDialog(node: LibraryNode, strings: Strings, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.deleteTitle(node.title)) },
        text = { Text(if (node.isFolder) strings.deleteFolderText else strings.deleteDocumentText) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(LibraryDialogTags.CONFIRM)) { Text(strings.delete) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}
