package de.nibsync.desktop

import androidx.compose.foundation.layout.Box
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
import de.nibsync.document.DocumentSession
import de.nibsync.library.NodeId
import de.nibsync.model.DocumentId
import de.nibsync.sync.client.SyncController
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
                    footer = { if (sync != null) SyncPanel(sync, strings, openBrowser) },
                )
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    val id = documentId
                    if (id == null) {
                        if (ready) Text(strings.noDocument) else CircularProgressIndicator()
                    } else {
                        DocumentPane(container, id, strings, keys, sync)
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentPane(container: DesktopContainer, id: DocumentId, strings: Strings, keys: KeyRouter, sync: SyncController?) {
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
    val controller = remember(s) { NoteController(s, textLayout::heightMm) }
    val editing by controller.text.editing.collectAsState()
    val canUndo by s.canUndo.collectAsState()
    val canRedo by s.canRedo.collectAsState()
    var viewport by remember(id) { mutableStateOf(initialViewport(doc.meta.format.widthMm, doc.meta.format.pageHeightMm)) }

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
        keys.handler = { event -> handleKey(event, controller) }
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
            end = { if (sync != null) SyncStatusIcon(sync, strings) },
        )
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            PageView(
                doc,
                painter,
                viewport,
                onViewportChange = { viewport = it },
                hiddenTextBox = editing?.boxId,
                onClick = { x, y -> controller.clickAt(x, y, viewport.pageWidthMm) },
            )
            editing?.let { e ->
                if (viewport.hasSize) TextBoxOverlay(e, viewport, strings, controller)
            }
        }
    }
}

/** Shortcuts that work anywhere in the window; text editing keys stay with the editor. */
private fun handleKey(event: KeyEvent, controller: NoteController): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val editing = controller.text.editing.value
    return when {
        event.key == Key.Escape && editing != null -> {
            controller.endEditing()
            true
        }
        event.isCtrlPressed && event.key == Key.B && editing != null -> {
            editing.controller.toggleBold()
            true
        }
        event.isCtrlPressed && event.key == Key.I && editing != null -> {
            editing.controller.toggleItalic()
            true
        }
        // While typing, Ctrl+Z/Y undo within the text box (the editor's own history).
        editing == null && event.isCtrlPressed && (event.key == Key.Z || event.key == Key.Y) -> {
            if (event.key == Key.Z && !event.isShiftPressed) controller.undo() else controller.redo()
            true
        }
        else -> false
    }
}
