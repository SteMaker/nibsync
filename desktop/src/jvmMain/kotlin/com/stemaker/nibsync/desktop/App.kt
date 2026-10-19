package com.stemaker.nibsync.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.unit.dp
import com.stemaker.nibsync.model.MediaFiles
import com.stemaker.nibsync.model.PickedFile
import com.stemaker.nibsync.shapes.GeometricShapeRecognizer
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.library.NodeId
import com.stemaker.nibsync.library.OpenTodo
import com.stemaker.nibsync.text.DocumentHeader
import com.stemaker.nibsync.text.DocumentHeaderState
import com.stemaker.nibsync.text.endsTitleEditing
import com.stemaker.nibsync.text.DocumentPath
import com.stemaker.nibsync.library.TodoCollector
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.flow.debounce
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.sync.client.SyncController
import java.io.File

/** The main window content: library on the left, the open document on the right. */
@Composable
fun App(
    container: DesktopContainer,
    settings: Settings,
    strings: Strings = Strings(),
    keys: KeyRouter = KeyRouter(),
    sync: SyncController? = null,
    openBrowser: (String) -> Boolean = ::openInBrowser,
    /** Logs in with OneDrive or Google Drive (browser); tests replace it. */
    cloudLogin: suspend (com.stemaker.nibsync.sync.client.SyncService) -> com.stemaker.nibsync.sync.client.SyncConfig = { service ->
        DesktopCloudLogin.run(service, sync!!.clients, openBrowser, strings)
    },
) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xff1a3fa8))) {
        // Shortcuts also from here, so they work in tests without a window (the window passes
        // keys on first; a key is handled only once).
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent(keys::onKey)) {
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                container.library.load(strings.defaultBook, strings.defaultDocument)
                ready = true
                // Only now: the sync compares the loaded library with the server.
                sync?.start()
            }
            val tree by container.library.tree.collectAsState()
            var showSettings by remember { mutableStateOf(false) }
            var newBook by remember { mutableStateOf(false) }
            var libraryAction by remember { mutableStateOf<LibraryAction?>(null) }
            // Dialogs get the keys (pasting into a field must not paste into the document).
            SideEffect { keys.blocked = showSettings || newBook || libraryAction != null }
            var showTitle by remember { mutableStateOf(settings.showTitle) }
            var showCreated by remember { mutableStateOf(settings.showCreated) }
            val appScope = rememberCoroutineScope()
            val accounts = sync?.accounts?.collectAsState()?.value.orEmpty()
            val bookAccounts = sync?.books?.map?.collectAsState()?.value.orEmpty()
            var lastAccount by remember { mutableStateOf(settings.lastBookAccount?.let { runCatching { com.stemaker.nibsync.sync.client.AccountId(it) }.getOrNull() }) }
            if (newBook) {
                NewBookDialog(strings, accounts, initial = lastAccount, onDismiss = { newBook = false }) { title, account ->
                    newBook = false
                    lastAccount = account
                    settings.lastBookAccount = account?.value
                    appScope.launch {
                        val book = container.library.createBook(title)
                        if (account != null) sync?.assignBook(book.id, account)
                    }
                }
            }
            if (showSettings) {
                val display = DisplaySettings(
                    showTitle = showTitle,
                    onShowTitle = { showTitle = it; settings.showTitle = it },
                    showCreated = showCreated,
                    onShowCreated = { showCreated = it; settings.showCreated = it },
                )
                SettingsDialog(sync, display, strings, openBrowser, cloudLogin = cloudLogin, onClose = { showSettings = false })
            }
            // One clipboard for the window, so elements can be copied between documents.
            val clipboard = remember { DesktopClipboard() }
            var documentId by remember { mutableStateOf<DocumentId?>(null) }
            var expanded by remember { mutableStateOf(emptySet<NodeId>()) }
            // The shown document's book and chapters stay open in the tree.
            LaunchedEffect(documentId, ready) {
                val id = documentId ?: return@LaunchedEffect
                expanded = expanded + tree.pathTo(NodeId(id.value)).dropLast(1).map { it.id }
            }
            var showTodos by remember { mutableStateOf(false) }
            fun open(id: NodeId) {
                showTodos = false
                documentId = id.asDocumentId
                settings.lastDocument = id.asDocumentId
            }
            // The generated page of open to-dos: derived from the documents, never stored.
            val todoCollector = remember {
                TodoCollector({ container.openSessionOrNull(it)?.state?.value }, { container.repository.load(it) })
            }
            var todos by remember { mutableStateOf<List<OpenTodo>?>(null) }
            var todoRefresh by remember { mutableIntStateOf(0) }
            var ticked by remember { mutableStateOf<TickedTodo?>(null) }
            var reveal by remember { mutableStateOf<Pair<DocumentId, Float>?>(null) }
            val syncStatuses = sync?.statuses?.collectAsState()?.value
            LaunchedEffect(syncStatuses) {
                // The sync may have changed stored documents.
                todoCollector.invalidate()
                todoRefresh++
            }
            LaunchedEffect(Unit) {
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                container.localChanges.debounce(TODO_REFRESH_MS).collect { todoRefresh++ }
            }
            LaunchedEffect(ready, tree, todoRefresh) {
                if (ready) todos = todoCollector.collect(tree)
            }
            when (val action = libraryAction) {
                null -> Unit
                is LibraryAction.NewChapter -> LibraryNameDialog(strings.newChapter, strings, placeholder = strings.newChapter, onDismiss = { libraryAction = null }) { title ->
                    libraryAction = null
                    appScope.launch {
                        container.library.createFolder(action.parent.id, title)
                        expanded = expanded + action.parent.id
                    }
                }
                is LibraryAction.NewDocument -> NewDocumentDialog(strings, onDismiss = { libraryAction = null }) { title, format, background ->
                    libraryAction = null
                    appScope.launch {
                        val doc = container.library.createDocument(action.parent.id, title, format, background)
                        expanded = expanded + action.parent.id
                        open(doc.id)
                    }
                }
                is LibraryAction.Rename -> LibraryNameDialog(strings.rename, strings, placeholder = action.node.title, initial = action.node.title, onDismiss = { libraryAction = null }) { title ->
                    libraryAction = null
                    appScope.launch { container.library.rename(action.node.id, title) }
                }
                is LibraryAction.Delete -> DeleteEntryDialog(action.node, strings, onDismiss = { libraryAction = null }) {
                    libraryAction = null
                    val gone = tree.descendantsOf(action.node.id) + action.node
                    val docs = gone.filter { !it.isFolder }.map { it.id.asDocumentId }.toSet()
                    // Leave a document that is deleted before its session closes.
                    if (documentId in docs) documentId = tree.documents.map { it.id.asDocumentId }.firstOrNull { it !in docs }
                    appScope.launch {
                        container.beforeDocumentsDeleted(docs)
                        container.library.delete(action.node.id)
                        expanded = expanded - gone.map { it.id }.toSet()
                    }
                }
            }
            LaunchedEffect(ready, tree) {
                if (!ready) return@LaunchedEffect
                val docs = tree.documents.map { it.id.asDocumentId }
                if (documentId == null || documentId !in docs) {
                    documentId = settings.lastDocument?.takeIf { it in docs } ?: docs.firstOrNull()
                }
            }
            Row(Modifier.fillMaxSize()) {
                LibraryPane(
                    tree = tree,
                    current = if (showTodos) null else documentId?.let { NodeId(it.value) },
                    strings = strings,
                    onOpen = ::open,
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    onAction = { libraryAction = it },
                    footer = { if (sync != null) SyncStatusLine(sync, strings) },
                    onSettings = { showSettings = true },
                    onNewBook = { newBook = true },
                    bookAccounts = sync?.let { BookAccountsUi(accounts, { id -> bookAccounts[id] }, it::assignBook) },
                    onTodos = { showTodos = true },
                    todosShown = showTodos,
                    todoCount = todos?.size,
                )
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    val id = documentId
                    when {
                        showTodos -> TodoListPane(
                            todos = todos,
                            strings = strings,
                            onTick = { t ->
                                appScope.launch {
                                    container.openSession(t.document).setTodoDone(t.item.taskId, true)
                                    ticked = TickedTodo(t)
                                    todoRefresh++
                                }
                            },
                            onOpen = { t ->
                                reveal = t.document to t.item.y
                                open(NodeId(t.document.value))
                            },
                            ticked = ticked,
                            onUndo = { t ->
                                ticked = null
                                appScope.launch {
                                    container.openSession(t.todo.document).setTodoDone(t.todo.item.taskId, false)
                                    todoRefresh++
                                }
                            },
                            onUndoGone = { ticked = null },
                        )
                        id == null -> if (ready) Text(strings.noDocument) else CircularProgressIndicator()
                        else -> {
                            val node = tree[NodeId(id.value)]
                            val header = HeaderInfo(
                                path = tree.pathTo(NodeId(id.value)).map { it.title },
                                title = node?.title,
                                showTitle = showTitle,
                                showCreated = showCreated,
                                onRename = node?.let { n -> { title: String -> appScope.launch { container.library.rename(n.id, title) }; Unit } },
                            )
                            DocumentPane(container, id, strings, keys, sync, clipboard, header, reveal?.takeIf { it.first == id }?.second) { reveal = null }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DocumentPane(
    container: DesktopContainer,
    id: DocumentId,
    strings: Strings,
    keys: KeyRouter,
    sync: SyncController?,
    clipboard: DesktopClipboard,
    header: HeaderInfo,
    /** Scroll so this height (mm) is in view, e.g. a to-do opened from the list. */
    revealYMm: Float? = null,
    onRevealed: () -> Unit = {},
) {
    var session by remember(id) { mutableStateOf<DocumentSession?>(null) }
    LaunchedEffect(id) { session = container.openSession(id) }
    val s = session ?: return CircularProgressIndicator()
    val doc by s.state.collectAsState()
    val scope = rememberCoroutineScope()
    val textLayout = remember(id) { DesktopTextLayout() }
    val media = remember(id) { MediaCache(scope) { File(container.repository.assetPath(id, it.sha256).toString()) } }
    val painter = remember(id) { PagePainter(textLayout, media) }
    // Pictures that arrive with the sync get drawn once they are there.
    LaunchedEffect(id) { container.assetsArrivedFlow.collect { if (id in it) media.retryMissing() } }
    var message by remember(id) { mutableStateOf<String?>(null) }
    val controller = remember(s) {
        NoteController(s, textLayout::heightMm, GeometricShapeRecognizer(), clipboard).apply {
            openFile = { link -> if (!DesktopMedia.open(container.repository, id, link)) message = strings.mediaNotSynced }
            copyAssets = { from, to, assets -> DesktopMedia.copyAssets(container.repository, from, to, assets) }
        }
    }
    val editing by controller.text.editing.collectAsState()
    val titleEditing = remember(id) { mutableStateOf(false) }
    val headerState = remember(id) { DocumentHeaderState() }
    val todoEdit by controller.todo.editing.collectAsState()
    val tools by controller.tools.collectAsState()
    val selection by controller.selection.collectAsState()
    val gesture by controller.gesture.collectAsState()
    val clip by controller.clipboardContent.collectAsState()
    val canUndo by s.canUndo.collectAsState()
    val canRedo by s.canRedo.collectAsState()
    var viewport by remember(id) { mutableStateOf(initialViewport(doc.meta.format.widthMm, doc.meta.format.pageHeightMm)) }
    LaunchedEffect(revealYMm, viewport.hasSize) {
        if (revealYMm == null || !viewport.hasSize) return@LaunchedEffect
        viewport = viewport.withContentBottom(doc.contentBottomMm).copy(scrollYMm = maxOf(0f, revealYMm - REVEAL_MARGIN_MM)).clamped()
        onRevealed()
    }

    /** Stores [file] with the document and puts it in the middle of the visible page. */
    fun insertFile(file: PickedFile?) {
        if (file == null) {
            message = strings.mediaUnsupported
            return
        }
        scope.launch {
            val sha = container.repository.putAsset(id, file.bytes)
            val visible = viewport.visibleDocumentRect
            val cx = ((visible.left + visible.right) / 2).coerceIn(0f, viewport.pageWidthMm)
            val cy = maxOf(0f, (visible.top + visible.bottom) / 2)
            val draft = MediaFiles.draft(file, sha, DesktopMedia.naturalSize(file), viewport.pageWidthMm, cx, cy)
            if (draft == null) message = strings.mediaUnreadable else controller.insert(listOf(draft))
        }
    }

    fun pasteHere() {
        if (DesktopMedia.clipboardHasMedia()) {
            insertFile(DesktopMedia.fromClipboard())
        } else {
            val r = viewport.visibleDocumentRect
            controller.paste((r.left + r.right) / 2, (r.top + r.bottom) / 2)
        }
    }

    // Leaving the document or closing the window stores the open text box.
    DisposableEffect(controller) {
        val store = { controller.endEditing() }
        container.beforeClose += store
        onDispose {
            container.beforeClose -= store
            controller.endEditing()
        }
    }
    DisposableEffect(controller) {
        // While the title is typed, keys belong to it.
        keys.handler = { event -> !titleEditing.value && handleKey(event, controller, ::pasteHere) }
        onDispose { keys.handler = { false } }
    }

    Column(Modifier.fillMaxSize()) {
        val toolbarTools = ToolbarTools(
            state = tools,
            hasSelection = selection.isNotEmpty(),
            canPaste = clip != null,
            onTool = controller::selectTool,
            onPen = controller::selectPen,
            onEraser = controller::selectEraser,
            onColor = controller::selectColor,
            onSize = controller::selectSize,
            onEraserSize = controller::selectEraserSize,
            onInsert = { DesktopMedia.chooseFile(null, strings.insert)?.let { insertFile(DesktopMedia.read(it)) } },
            onDeleteSelection = { controller.deleteSelection() },
            onCopySelection = { controller.copySelection() },
            onCutSelection = { controller.cutSelection() },
            onPaste = ::pasteHere,
            onTodo = controller::selectTodo,
            onTodoInk = controller::selectTodoInk,
        )
        NoteToolbar(
            strings = strings,
            canUndo = canUndo || editing != null || todoEdit != null,
            canRedo = canRedo,
            onUndo = { controller.undo() },
            onRedo = { controller.redo() },
            editing = editing?.controller ?: todoEdit?.text,
            onDeleteText = { if (todoEdit != null) controller.todo.delete() else controller.text.delete() },
            tools = toolbarTools,
            end = { if (sync != null) SyncStatusIcon(sync, strings) },
        )
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .endsTitleEditing(headerState)
                    .dragAndDropTarget(
                        shouldStartDragAndDrop = { true },
                        target = remember(controller) {
                            object : DragAndDropTarget {
                                override fun onDrop(event: DragAndDropEvent): Boolean {
                                    val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                                    val file = files.firstNotNullOfOrNull { uri -> runCatching { DesktopMedia.read(File(java.net.URI(uri))) }.getOrNull() }
                                    if (files.isEmpty()) return false
                                    insertFile(file)
                                    return true
                                }
                            }
                        },
                    ),
            ) {
                PageView(
                    doc,
                    painter,
                    viewport,
                    onViewportChange = { viewport = it },
                    hiddenTextBox = editing?.boxId,
                    controller = controller,
                    overlay = PagePainter.Overlay(
                        gesture = gesture,
                        selection = selection,
                        selectionFrame = if (selection.isEmpty()) null else controller.selectionFrame(),
                        resizable = controller.resizable() != null,
                        todo = todoEdit?.preview(s.device),
                        todoInEditor = todoEdit?.text != null,
                    ),
                )
                editing?.let { e ->
                    if (viewport.hasSize) TextBoxOverlay(e, viewport, strings, controller)
                }
                todoEdit?.let { e ->
                    if (viewport.hasSize) TodoOverlay(e, viewport, strings, controller)
                }
                if (viewport.hasSize) {
                    DocumentHeader(
                        title = header.title ?: doc.meta.title,
                        created = strings.created(doc.meta.createdAtEpochMs),
                        showTitle = header.showTitle,
                        showDate = header.showCreated,
                        viewport = viewport,
                        onRename = header.onRename,
                        state = headerState,
                        onEditingChange = { titleEditing.value = it },
                    )
                }
                DocumentPath(header.path, Modifier.align(Alignment.BottomEnd))
                message?.let { text ->
                    LaunchedEffect(text) {
                        kotlinx.coroutines.delay(4000)
                        message = null
                    }
                    Surface(
                        Modifier.align(Alignment.BottomCenter).padding(16.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
                    }
                }
            }
            // The text tool shows its formatting in the tool bar; settings only for drawing tools.
            ToolSettingsBar(strings, toolbarTools)
        }
    }
}

/** What the page shows about the document itself: title and creation time on top, path below. */
class HeaderInfo(
    val path: List<String>,
    /** The library title; null for a document outside the library (then the stored title). */
    val title: String?,
    val showTitle: Boolean,
    val showCreated: Boolean,
    /** Renames the document; null: the title is read-only. */
    val onRename: ((String) -> Unit)?,
)

/** Space above a to-do revealed from the list. */
private const val REVEAL_MARGIN_MM = 15f

/** Edits are collected this long before the to-do list is rebuilt. */
private const val TODO_REFRESH_MS = 800L

/** Shortcuts that work anywhere in the window; text editing keys stay with the editor. */
private fun handleKey(event: KeyEvent, controller: NoteController, paste: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val todo = controller.todo.editing.value
    if (todo != null && todo.isInk) {
        return when (event.key) {
            Key.Escape -> {
                controller.endEditing()
                true
            }
            Key.Delete, Key.Backspace -> controller.todo.delete()
            else -> false
        }
    }
    val editing = controller.text.editing.value ?: todo?.let { com.stemaker.nibsync.text.TextEditing(it.key, it.itemId, it.x, it.y, it.widthMm, it.text!!) }
    if (editing != null) {
        return when {
            event.key == Key.Escape -> {
                controller.endEditing()
                true
            }
            event.isCtrlPressed && event.key == Key.B -> {
                editing.controller.toggleBold()
                true
            }
            event.isCtrlPressed && event.key == Key.I -> {
                editing.controller.toggleItalic()
                true
            }
            // Ctrl+Z/Y, Ctrl+C/V/X: the editor's own history and clipboard.
            else -> false
        }
    }
    return when {
        event.key == Key.Escape -> {
            controller.escape()
            true
        }
        event.key == Key.Delete || event.key == Key.Backspace -> controller.deleteSelection()
        event.isCtrlPressed && (event.key == Key.Z || event.key == Key.Y) -> {
            if (event.key == Key.Z && !event.isShiftPressed) controller.undo() else controller.redo()
            true
        }
        event.isCtrlPressed && event.key == Key.C -> controller.copySelection()
        event.isCtrlPressed && event.key == Key.X -> controller.cutSelection()
        event.isCtrlPressed && event.key == Key.V -> {
            paste()
            true
        }
        else -> false
    }
}
