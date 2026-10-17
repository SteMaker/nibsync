package de.nibsync.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nibsync.canvas.CanvasViewModel
import de.nibsync.canvas.NoteCanvasView
import de.nibsync.canvas.NoteScreen
import de.nibsync.document.DocumentSession
import de.nibsync.library.NodeId
import de.nibsync.library.ui.LibraryDrawer
import de.nibsync.library.ui.LibraryViewModel
import de.nibsync.model.DocumentId
import de.nibsync.shapes.GeometricShapeRecognizer
import kotlinx.coroutines.launch

/**
 * Shows one note document; the library drawer (menu button, top left) switches documents.
 * [EXTRA_DOCUMENT_ID] opens a specific document directly (used by tests).
 */
class MainActivity : ComponentActivity() {

    private val container get() = (application as NibSyncApp).container
    private val settings by lazy { AppSettings(this) }

    /** Document requested by the launching intent; such documents bypass the library. */
    private val requestedDocument: DocumentId? by lazy { intent.getStringExtra(EXTRA_DOCUMENT_ID)?.let(::DocumentId) }

    /** The document shown right now. */
    var documentId: DocumentId? by mutableStateOf(null)
        private set

    /** The canvas, once created. Exposed for instrumented tests. */
    var canvasView: NoteCanvasView? = null
        private set

    var session: DocumentSession? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val bookTitle = getString(R.string.default_book_title)
        val documentTitle = getString(R.string.default_document_title)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xff1a3fa8))) {
                val scope = rememberCoroutineScope()
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val libraryVm = viewModel { LibraryViewModel(container.library, container::beforeDelete) }
                val tree by container.library.tree.collectAsStateWithLifecycle()
                var libraryReady by remember { mutableStateOf(false) }
                var showPageBreaks by remember { mutableStateOf(settings.showPageBreaks) }

                LaunchedEffect(Unit) {
                    container.ensureLibraryLoaded(bookTitle, documentTitle)
                    libraryReady = true
                }
                // Pick a document: the requested one, else the last used, else the first; and switch away
                // when the shown document was deleted.
                LaunchedEffect(libraryReady, tree) {
                    val requested = requestedDocument
                    if (requested != null) {
                        documentId = requested
                        return@LaunchedEffect
                    }
                    if (!libraryReady) return@LaunchedEffect
                    val docs = tree.documents.map { it.id.asDocumentId }
                    val current = documentId
                    if (current == null || current !in docs) {
                        documentId = settings.lastDocument?.takeIf { it in docs } ?: docs.firstOrNull()
                    }
                }

                fun open(id: DocumentId) {
                    documentId = id
                    settings.lastDocument = id
                    scope.launch { drawerState.close() }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    // No swipe-to-open: it would fight with writing near the left edge.
                    gesturesEnabled = drawerState.isOpen,
                    drawerContent = {
                        ModalDrawerSheet(Modifier.width(440.dp)) {
                            LibraryDrawer(
                                viewModel = libraryVm,
                                currentDocument = documentId?.let { NodeId(it.value) },
                                onOpenDocument = { open(it.asDocumentId) },
                                modifier = Modifier.safeDrawingPadding(),
                            )
                        }
                    },
                ) {
                    Surface(Modifier.fillMaxSize()) {
                        val openLibrary: () -> Unit = {
                            documentId?.let {
                                libraryVm.reveal(NodeId(it.value))
                                libraryVm.onDocumentOpened(NodeId(it.value))
                            }
                            scope.launch { drawerState.open() }
                        }
                        val docId = documentId
                        if (docId == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (!libraryReady) {
                                    CircularProgressIndicator()
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text(stringResource(R.string.no_document))
                                        FilledTonalButton(onClick = openLibrary) { Text(stringResource(R.string.open_library)) }
                                    }
                                }
                            }
                        } else {
                            DocumentPane(docId, documentTitle, openLibrary, showPageBreaks) {
                                showPageBreaks = it
                                settings.showPageBreaks = it
                            }
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun DocumentPane(
        docId: DocumentId,
        fallbackTitle: String,
        onMenu: () -> Unit,
        showPageBreaks: Boolean,
        onTogglePageBreaks: (Boolean) -> Unit,
    ) {
        var openSession by remember(docId) { mutableStateOf<DocumentSession?>(null) }
        LaunchedEffect(docId) {
            openSession = container.openSession(docId, fallbackTitle).also { session = it }
        }
        val current = openSession
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val vm = viewModel(key = docId.value) { CanvasViewModel(current, GeometricShapeRecognizer(), container.clipboard) }
            NoteScreen(
                vm,
                Modifier.safeDrawingPadding(),
                onMenu = onMenu,
                showPageBreaks = showPageBreaks,
                onTogglePageBreaks = onTogglePageBreaks,
                onCanvasCreated = { canvasView = it },
            )
        }
    }

    /**
     * Keyboard shortcuts: Ctrl+Z undo, Ctrl+Y / Ctrl+Shift+Z redo. Handled before the view
     * hierarchy so focused Compose elements cannot swallow them; dialogs (e.g. rename) are own
     * windows and keep their normal text editing keys.
     */
    // Lint false positive: androidx.core's ComponentActivity marks this override as restricted,
    // but it is the public Activity.dispatchKeyEvent.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = session
        if (s != null && event.isCtrlPressed && (event.keyCode == KeyEvent.KEYCODE_Z || event.keyCode == KeyEvent.KEYCODE_Y)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.keyCode == KeyEvent.KEYCODE_Z && !event.isShiftPressed) s.undo() else s.redo()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // Make sure everything written so far reaches the disk while the app is in background.
        val s = session ?: return
        container.appScope.launch { s.flush() }
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "de.nibsync.extra.DOCUMENT_ID"
    }
}
