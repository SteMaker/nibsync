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
            // Dialogs get the keys (pasting into a field must not paste into the document).
            SideEffect { keys.blocked = (showSettings && sync != null) || newBook }
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
            if (showSettings && sync != null) {
                SettingsDialog(sync, strings, openBrowser, cloudLogin = cloudLogin, onClose = { showSettings = false })
            }
            // One clipboard for the window, so elements can be copied between documents.
            val clipboard = remember { DesktopClipboard() }
            var documentId by remember { mutableStateOf<DocumentId?>(null) }
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
                    current = documentId?.let { NodeId(it.value) },
                    strings = strings,
                    onOpen = {
                        documentId = it.asDocumentId
                        settings.lastDocument = it.asDocumentId
                    },
                    footer = { if (sync != null) SyncStatusLine(sync, strings) },
                    onSettings = if (sync != null) ({ showSettings = true }) else null,
                    onNewBook = { newBook = true },
                    bookAccounts = sync?.let { BookAccountsUi(accounts, { id -> bookAccounts[id] }, it::assignBook) },
                )
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    val id = documentId
                    if (id == null) {
                        if (ready) Text(strings.noDocument) else CircularProgressIndicator()
                    } else {
                        DocumentPane(container, id, strings, keys, sync, clipboard)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DocumentPane(container: DesktopContainer, id: DocumentId, strings: Strings, keys: KeyRouter, sync: SyncController?, clipboard: DesktopClipboard) {
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
    val tools by controller.tools.collectAsState()
    val selection by controller.selection.collectAsState()
    val gesture by controller.gesture.collectAsState()
    val clip by controller.clipboardContent.collectAsState()
    val canUndo by s.canUndo.collectAsState()
    val canRedo by s.canRedo.collectAsState()
    var viewport by remember(id) { mutableStateOf(initialViewport(doc.meta.format.widthMm, doc.meta.format.pageHeightMm)) }

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
        keys.handler = { event -> handleKey(event, controller, ::pasteHere) }
        onDispose { keys.handler = { false } }
    }

    Column(Modifier.fillMaxSize()) {
        NoteToolbar(
            strings = strings,
            canUndo = canUndo || editing != null,
            canRedo = canRedo,
            onUndo = { controller.undo() },
            onRedo = { controller.redo() },
            editing = editing?.controller,
            onDeleteText = { controller.text.delete() },
            tools = ToolbarTools(
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
            ),
            end = { if (sync != null) SyncStatusIcon(sync, strings) },
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
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
                ),
            )
            editing?.let { e ->
                if (viewport.hasSize) TextBoxOverlay(e, viewport, strings, controller)
            }
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
    }
}

/** Shortcuts that work anywhere in the window; text editing keys stay with the editor. */
private fun handleKey(event: KeyEvent, controller: NoteController, paste: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val editing = controller.text.editing.value
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
