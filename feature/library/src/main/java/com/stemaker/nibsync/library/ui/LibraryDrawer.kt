package com.stemaker.nibsync.library.ui

import com.stemaker.nibsync.theme.NibSyncTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.round
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.stemaker.nibsync.library.LibraryNode
import com.stemaker.nibsync.library.NodeId
import com.stemaker.nibsync.background.ui.BackgroundPicker
import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.Orientation
import com.stemaker.nibsync.model.PageFormat
import com.stemaker.nibsync.model.PaperSize
import kotlinx.coroutines.launch

/** Test tags used by UI tests. */
object LibraryTags {
    const val DRAWER = "library-drawer"
    const val SETTINGS = "library-settings"
    const val TODOS = "library-todos"
    const val DRAGGED = "library-dragged"
    const val TREE = "library-tree"
    const val NEW_BOOK = "library-new-book"
    const val NEW_FOLDER = "library-new-folder"
    const val NEW_DOCUMENT = "library-new-document"
    const val NAME_FIELD = "library-name"
    const val CONFIRM = "library-confirm"
    const val RENAME = "library-rename"
    const val DELETE = "library-delete"
    fun row(title: String) = "library-row-$title"
    /** Account choice of a new book; null: only on this device. */
    fun account(label: String?) = "library-account-${label ?: "local"}"
    fun assign(label: String) = "library-assign-$label"
    fun paper(size: PaperSize) = "library-paper-${size.name}"
    fun orientation(o: Orientation) = "library-orientation-${o.name}"
}

/** An entry being dragged in the library ([moved] once the finger moved), pointer in root coordinates. */
private data class TreeDrag(val id: NodeId, val title: String, val pointer: Offset, val moved: Boolean = false)

/** How long the dragged entry is held over a closed folder before it opens. */
private const val HOVER_OPEN_MS = 700L

/** Distance from the list's top or bottom edge where dragging scrolls, and the step. */
private const val EDGE_PX = 80f
private const val SCROLL_STEP_PX = 16f

/** A sync account a book can belong to, as the app names it. */
data class AccountChoice(val key: String, val label: String)

/**
 * Sync accounts of books (this module knows nothing about syncing): every book belongs to one
 * account or stays on the device. [accountOf] gives a book's account key.
 */
class BookSyncUi(
    val accounts: List<AccountChoice>,
    val accountOf: (NodeId) -> String?,
    /** Preselected account of a new book. */
    val initial: String?,
    val onCreated: (book: NodeId, account: String?) -> Unit,
    /** A book that was only on this device syncs with the account from now on. */
    val onAssign: (book: NodeId, account: String) -> Unit,
)

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
    /** Extra content at the bottom, e.g. the sync status. */
    footer: @Composable () -> Unit = {},
    /** Opens the app settings; null hides the gear button. */
    onSettings: (() -> Unit)? = null,
    bookSync: BookSyncUi? = null,
    /** Shows the generated page of open to-dos; null hides its entry. */
    onOpenTodos: (() -> Unit)? = null,
    /** Number of open to-dos, null while unknown. */
    todoCount: Int? = null,
    todosShown: Boolean = false,
) {
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val expanded by viewModel.expanded.collectAsStateWithLifecycle()
    val selectedFolder by viewModel.selectedFolder.collectAsStateWithLifecycle()
    val highlightedId by viewModel.highlighted.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var menuFor by remember { mutableStateOf<NodeId?>(null) }
    // Long press and drag moves a chapter or document; long press alone opens its menu.
    var drag by remember { mutableStateOf<TreeDrag?>(null) }
    val rowBounds = remember { mutableStateMapOf<NodeId, Rect>() }
    // The drag handlers live as long as their row: they read the current tree through this.
    val currentTree by rememberUpdatedState(tree)
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    var drawerOrigin by remember { mutableStateOf(Offset.Zero) }
    val listState = rememberLazyListState()
    val over = drag?.takeIf { it.moved }?.let { d -> rowBounds.entries.firstOrNull { it.value.contains(d.pointer) }?.key }
    val dropTarget = drag?.let { d -> over?.let { tree.dropTarget(d.id, it) } }
    // Held over a closed folder, it opens, so chapters inside become targets too.
    LaunchedEffect(over) {
        val folder = over?.takeIf { tree[it]?.isFolder == true && it !in expanded } ?: return@LaunchedEffect
        delay(HOVER_OPEN_MS)
        viewModel.expand(folder)
    }
    // Near the top or bottom edge, the list scrolls.
    LaunchedEffect(drag?.moved == true) {
        while (drag?.moved == true) {
            val y = drag?.pointer?.y ?: break
            when {
                y < listBounds.top + EDGE_PX -> listState.scrollBy(-SCROLL_STEP_PX)
                y > listBounds.bottom - EDGE_PX -> listState.scrollBy(SCROLL_STEP_PX)
            }
            delay(16)
        }
    }

    Box(modifier.onGloballyPositioned { drawerOrigin = it.positionInRoot() }) {
    Column(Modifier.fillMaxHeight().testTag(LibraryTags.DRAWER)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.library), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onSettings != null) {
                IconButton(onClick = onSettings, modifier = Modifier.testTag(LibraryTags.SETTINGS)) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                }
                Spacer(Modifier.width(4.dp))
            }
            FilledTonalButton(onClick = { dialog = Dialog.NewBook }, modifier = Modifier.testTag(LibraryTags.NEW_BOOK)) {
                Icon(Icons.Filled.LibraryAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.new_book))
            }
        }
        HorizontalDivider()
        if (onOpenTodos != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (todosShown) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(onClick = onOpenTodos)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag(LibraryTags.TODOS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.TaskAlt, contentDescription = null, tint = NibSyncTheme.colors.todo)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.open_todos), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (todoCount != null) Text(todoCount.toString(), style = MaterialTheme.typography.labelLarge)
            }
            HorizontalDivider()
        }
        LazyColumn(Modifier.weight(1f).onGloballyPositioned { listBounds = it.boundsInRoot() }.testTag(LibraryTags.TREE), state = listState) {
            items(viewModel.rows(tree, expanded), key = { it.node.id.value }) { row ->
                val node = row.node
                val highlighted = node.id == highlightedId
                DisposableEffect(node.id) { onDispose { rowBounds.remove(node.id) } }
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { rowBounds[node.id] = it.boundsInRoot() }
                            .background(
                                when {
                                    node.id == dropTarget -> MaterialTheme.colorScheme.primaryContainer
                                    highlighted -> MaterialTheme.colorScheme.secondaryContainer
                                    else -> MaterialTheme.colorScheme.surface
                                },
                            )
                            .pointerInput(node.id) {
                                // Long press, then: lift without moving opens the menu, moving drags.
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                                    val origin = rowBounds[node.id]?.topLeft ?: Offset.Zero
                                    drag = TreeDrag(node.id, node.title, origin + press.position)
                                    var travelled = 0f
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == press.id } ?: break
                                        if (!change.pressed) break
                                        val delta = change.positionChange()
                                        travelled += delta.getDistance()
                                        change.consume()
                                        // Books stay where they are; their long press only opens the menu.
                                        if (node.parent != null && travelled > viewConfiguration.touchSlop) {
                                            drag = drag?.let { it.copy(pointer = it.pointer + delta, moved = true) }
                                        } else {
                                            drag = drag?.let { it.copy(pointer = it.pointer + delta) }
                                        }
                                    }
                                    val d = drag
                                    drag = null
                                    if (d == null || !d.moved) {
                                        menuFor = node.id
                                    } else {
                                        val target = rowBounds.entries.firstOrNull { it.value.contains(d.pointer) }?.key?.let { currentTree.dropTarget(d.id, it) }
                                        if (target != null) scope.launch { viewModel.move(d.id, target) }
                                    }
                                }
                            }
                            .clickable {
                                if (node.isFolder) {
                                    viewModel.onFolderTapped(node.id)
                                } else {
                                    viewModel.onDocumentOpened(node.id)
                                    onOpenDocument(node.id)
                                }
                            }
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
                        Column(Modifier.weight(1f)) {
                            Text(
                                node.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (node.id == currentDocument) FontWeight.Bold else FontWeight.Normal,
                            )
                            if (node.parent == null && bookSync != null) {
                                val key = bookSync.accountOf(node.id)
                                Text(
                                    bookSync.accounts.firstOrNull { it.key == key }?.label ?: stringResource(R.string.local_only),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
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
                        if (node.parent == null && bookSync != null && bookSync.accountOf(node.id) == null) {
                            for (account in bookSync.accounts) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sync_book_with, account.label)) },
                                    onClick = { menuFor = null; bookSync.onAssign(node.id, account.key) },
                                    modifier = Modifier.testTag(LibraryTags.assign(account.label)),
                                )
                            }
                        }
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
    // The dragged entry follows the finger.
    drag?.takeIf { it.moved }?.let { d ->
        Surface(
            shape = MaterialTheme.shapes.small,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.offset { (d.pointer - drawerOrigin + Offset(24f, -48f)).round() }.testTag(LibraryTags.DRAGGED),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (tree[d.id]?.isFolder == true) Icons.Filled.Folder else Icons.Filled.Description, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(d.title, maxLines = 1)
            }
        }
    }
    }

    when (val d = dialog) {
        null -> Unit
        Dialog.NewBook -> NameDialog(
            stringResource(R.string.new_book),
            placeholder = stringResource(R.string.default_book_name),
            onDismiss = { dialog = null },
            accounts = bookSync?.let { it.accounts to it.initial },
        ) { name, account ->
            scope.launch {
                val book = viewModel.createBook(name)
                bookSync?.onCreated(book.id, account)
            }
        }
        Dialog.NewFolder -> NameDialog(stringResource(R.string.new_folder), placeholder = stringResource(R.string.default_folder_name), onDismiss = { dialog = null }) { name, _ ->
            scope.launch { viewModel.createFolder(name) }
        }
        Dialog.NewDocument -> NewDocumentDialog(onDismiss = { dialog = null }) { name, format, background ->
            scope.launch {
                viewModel.createDocument(name, format, background)?.let {
                    viewModel.onDocumentOpened(it.id)
                    onOpenDocument(it.id)
                }
            }
        }
        is Dialog.Rename -> NameDialog(stringResource(R.string.rename), placeholder = d.node.title, initial = d.node.title, onDismiss = { dialog = null }) { name, _ ->
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
 * which is also used when nothing is entered. The keyboard opens right away. With [accounts]
 * (choices and the preselected key) it also asks which account the new book syncs with.
 */
@Composable
private fun NameDialog(
    title: String,
    placeholder: String,
    initial: String = "",
    onDismiss: () -> Unit,
    accounts: Pair<List<AccountChoice>, String?>? = null,
    onConfirm: (String, String?) -> Unit,
) {
    // Renaming starts with the whole old name selected, so typing replaces it.
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var account by remember { mutableStateOf(accounts?.let { (list, key) -> key?.takeIf { k -> list.any { it.key == k } } }) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.name)) },
                    placeholder = { Text(placeholder) },
                    modifier = Modifier.focusRequester(focus).testTag(LibraryTags.NAME_FIELD),
                )
                if (accounts != null) {
                    Text(stringResource(R.string.sync_with), style = MaterialTheme.typography.labelLarge)
                    @Composable
                    fun choice(key: String?, label: String, tag: String) = Row(
                        Modifier.fillMaxWidth().selectable(selected = account == key, onClick = { account = key }).testTag(tag),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = account == key, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                    for (a in accounts.first) choice(a.key, a.label, LibraryTags.account(a.label))
                    choice(null, stringResource(R.string.local_only), LibraryTags.account(null))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDismiss(); onConfirm(name.text.ifBlank { placeholder }, account) },
                modifier = Modifier.testTag(LibraryTags.CONFIRM),
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NewDocumentDialog(onDismiss: () -> Unit, onConfirm: (String, PageFormat, BackgroundSpec) -> Unit) {
    var name by remember { mutableStateOf("") }
    val defaultName = stringResource(R.string.default_document_name)
    var paper by remember { mutableStateOf(PaperSize.A4) }
    var orientation by remember { mutableStateOf(Orientation.PORTRAIT) }
    var background by remember { mutableStateOf(BackgroundSpec()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_document)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                BackgroundPicker(background, { background = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDismiss(); onConfirm(name.ifBlank { defaultName }, PageFormat(paper, orientation), background) },
                modifier = Modifier.testTag(LibraryTags.CONFIRM),
            ) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
