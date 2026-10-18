package de.nibsync.library.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.nibsync.library.LibraryNode
import de.nibsync.library.NodeId
import de.nibsync.model.Orientation
import de.nibsync.model.PageFormat
import de.nibsync.model.PaperSize
import kotlinx.coroutines.launch

/** Test tags used by UI tests. */
object LibraryTags {
    const val DRAWER = "library-drawer"
    const val TREE = "library-tree"
    const val NEW_BOOK = "library-new-book"
    const val NEW_FOLDER = "library-new-folder"
    const val NEW_DOCUMENT = "library-new-document"
    const val NAME_FIELD = "library-name"
    const val CONFIRM = "library-confirm"
    const val RENAME = "library-rename"
    const val DELETE = "library-delete"
    fun row(title: String) = "library-row-$title"
    fun paper(size: PaperSize) = "library-paper-${size.name}"
    fun orientation(o: Orientation) = "library-orientation-${o.name}"
}

private sealed interface Dialog {
    data object NewBook : Dialog
    data object NewFolder : Dialog
    data object NewDocument : Dialog
    data class Rename(val node: LibraryNode) : Dialog
    data class Delete(val node: LibraryNode) : Dialog
}

/**
 * Content of the library drawer: the tree of books, chapters and documents, and the actions
 * to create entries in the selected folder.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryDrawer(
    viewModel: LibraryViewModel,
    currentDocument: NodeId?,
    onOpenDocument: (NodeId) -> Unit,
    modifier: Modifier = Modifier,
    /** Extra content at the bottom, e.g. the sync settings. */
    footer: @Composable () -> Unit = {},
) {
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val expanded by viewModel.expanded.collectAsStateWithLifecycle()
    val selectedFolder by viewModel.selectedFolder.collectAsStateWithLifecycle()
    val highlightedId by viewModel.highlighted.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var menuFor by remember { mutableStateOf<NodeId?>(null) }

    Column(modifier.fillMaxHeight().testTag(LibraryTags.DRAWER)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.library), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { dialog = Dialog.NewBook }, modifier = Modifier.testTag(LibraryTags.NEW_BOOK)) {
                Icon(Icons.Filled.LibraryAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.new_book))
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).testTag(LibraryTags.TREE)) {
            items(viewModel.rows(tree, expanded), key = { it.node.id.value }) { row ->
                val node = row.node
                val highlighted = node.id == highlightedId
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                            .combinedClickable(
                                onClick = {
                                    if (node.isFolder) {
                                        viewModel.onFolderTapped(node.id)
                                    } else {
                                        viewModel.onDocumentOpened(node.id)
                                        onOpenDocument(node.id)
                                    }
                                },
                                onLongClick = { menuFor = node.id },
                            )
                            .padding(start = (8 + row.depth * 24).dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
                            .testTag(LibraryTags.row(node.title)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(24.dp)) {
                            if (node.isFolder && row.hasChildren) {
                                Icon(if (row.expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight, contentDescription = null)
                            }
                        }
                        Icon(
                            when {
                                !node.isFolder -> Icons.Filled.Description
                                node.parent == null -> Icons.AutoMirrored.Filled.MenuBook
                                row.expanded -> Icons.Filled.FolderOpen
                                else -> Icons.Filled.Folder
                            },
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            node.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (node.id == currentDocument) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    DropdownMenu(expanded = menuFor == node.id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = { menuFor = null; dialog = Dialog.Rename(node) },
                            modifier = Modifier.testTag(LibraryTags.RENAME),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = { menuFor = null; dialog = Dialog.Delete(node) },
                            modifier = Modifier.testTag(LibraryTags.DELETE),
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        val folder = selectedFolder?.let { tree[it] }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (folder != null) stringResource(R.string.create_in, folder.title) else stringResource(R.string.select_folder_hint),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { dialog = Dialog.NewFolder }, enabled = folder != null, modifier = Modifier.testTag(LibraryTags.NEW_FOLDER)) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.new_folder))
                }
                FilledTonalButton(onClick = { dialog = Dialog.NewDocument }, enabled = folder != null, modifier = Modifier.testTag(LibraryTags.NEW_DOCUMENT)) {
                    Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.new_document))
                }
            }
        }
        footer()
    }

    when (val d = dialog) {
        null -> Unit
        Dialog.NewBook -> NameDialog(stringResource(R.string.new_book), placeholder = stringResource(R.string.default_book_name), onDismiss = { dialog = null }) { name ->
            scope.launch { viewModel.createBook(name) }
        }
        Dialog.NewFolder -> NameDialog(stringResource(R.string.new_folder), placeholder = stringResource(R.string.default_folder_name), onDismiss = { dialog = null }) { name ->
            scope.launch { viewModel.createFolder(name) }
        }
        Dialog.NewDocument -> NewDocumentDialog(onDismiss = { dialog = null }) { name, format ->
            scope.launch {
                viewModel.createDocument(name, format)?.let {
                    viewModel.onDocumentOpened(it.id)
                    onOpenDocument(it.id)
                }
            }
        }
        is Dialog.Rename -> NameDialog(stringResource(R.string.rename), placeholder = d.node.title, initial = d.node.title, onDismiss = { dialog = null }) { name ->
            scope.launch { viewModel.rename(d.node.id, name) }
        }
        is Dialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.delete_title, d.node.title)) },
            text = { Text(stringResource(if (d.node.isFolder) R.string.delete_folder_text else R.string.delete_document_text)) },
            confirmButton = {
                TextButton(onClick = { dialog = null; scope.launch { viewModel.delete(d.node.id) } }, modifier = Modifier.testTag(LibraryTags.CONFIRM)) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Asks for a name. The field starts empty (except when renaming) with [placeholder] as hint,
 * which is also used when nothing is entered. The keyboard opens right away.
 */
@Composable
private fun NameDialog(title: String, placeholder: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    // Renaming starts with the whole old name selected, so typing replaces it.
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.name)) },
                placeholder = { Text(placeholder) },
                modifier = Modifier.focusRequester(focus).testTag(LibraryTags.NAME_FIELD),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onDismiss(); onConfirm(name.text.ifBlank { placeholder }) },
                modifier = Modifier.testTag(LibraryTags.CONFIRM),
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NewDocumentDialog(onDismiss: () -> Unit, onConfirm: (String, PageFormat) -> Unit) {
    var name by remember { mutableStateOf("") }
    val defaultName = stringResource(R.string.default_document_name)
    var paper by remember { mutableStateOf(PaperSize.A4) }
    var orientation by remember { mutableStateOf(Orientation.PORTRAIT) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_document)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.name)) },
                    placeholder = { Text(defaultName) },
                    modifier = Modifier.focusRequester(focus).testTag(LibraryTags.NAME_FIELD),
                )
                Text(stringResource(R.string.paper_width))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (size in PaperSize.entries) {
                        FilterChip(
                            selected = paper == size,
                            onClick = { paper = size },
                            label = { Text(size.name) },
                            modifier = Modifier.testTag(LibraryTags.paper(size)),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (o in Orientation.entries) {
                        FilterChip(
                            selected = orientation == o,
                            onClick = { orientation = o },
                            label = { Text(stringResource(if (o == Orientation.PORTRAIT) R.string.portrait else R.string.landscape)) },
                            modifier = Modifier.testTag(LibraryTags.orientation(o)),
                        )
                    }
                }
                val format = PageFormat(paper, orientation)
                Text(
                    stringResource(R.string.width_info, format.widthMm.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDismiss(); onConfirm(name.ifBlank { defaultName }, PageFormat(paper, orientation)) },
                modifier = Modifier.testTag(LibraryTags.CONFIRM),
            ) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
