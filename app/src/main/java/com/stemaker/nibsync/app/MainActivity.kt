package com.stemaker.nibsync.app

import com.stemaker.nibsync.theme.NibSyncTheme
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stemaker.nibsync.app.media.MediaActions
import com.stemaker.nibsync.app.sync.SyncStatusLine
import com.stemaker.nibsync.app.sync.googleAuthorize
import com.stemaker.nibsync.app.sync.googleConfig
import com.stemaker.nibsync.app.sync.isUnregisteredApp
import com.stemaker.nibsync.app.sync.signingSha1
import com.stemaker.nibsync.app.sync.oneDriveLogin
import com.stemaker.nibsync.sync.client.SyncConfig
import com.stemaker.nibsync.sync.client.SyncService
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.Identity
import com.stemaker.nibsync.app.sync.SyncStatusIcon
import com.stemaker.nibsync.canvas.CanvasViewModel
import com.stemaker.nibsync.canvas.NoteCanvasView
import com.stemaker.nibsync.canvas.NoteScreen
import com.stemaker.nibsync.text.DocumentHeader
import com.stemaker.nibsync.text.DocumentHeaderState
import com.stemaker.nibsync.text.endsTitleEditing
import com.stemaker.nibsync.text.DocumentPath
import com.stemaker.nibsync.canvas.TickedTodo
import com.stemaker.nibsync.canvas.TodoListScreen
import com.stemaker.nibsync.canvas.ToolState
import com.stemaker.nibsync.library.OpenTodo
import com.stemaker.nibsync.library.TodoCollector
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.flow.debounce
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.library.NodeId
import com.stemaker.nibsync.library.ui.AccountChoice
import com.stemaker.nibsync.library.ui.BookSyncUi
import com.stemaker.nibsync.library.ui.LibraryDrawer
import com.stemaker.nibsync.sync.client.AccountId
import com.stemaker.nibsync.sync.client.displayName
import com.stemaker.nibsync.library.ui.LibraryViewModel
import com.stemaker.nibsync.model.DocumentId
import android.widget.Toast
import com.stemaker.nibsync.model.LinkTarget
import com.stemaker.nibsync.model.AnchorDraft
import com.stemaker.nibsync.model.Anchor
import com.stemaker.nibsync.library.LinkTargets
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.shapes.GeometricShapeRecognizer
import kotlinx.coroutines.launch

/**
 * Shows one note document; the library drawer (menu button, top left) switches documents.
 * [EXTRA_DOCUMENT_ID] opens a specific document directly (used by tests).
 */
class MainActivity : ComponentActivity() {

    private val container get() = (application as NibSyncApp).container
    private val sync get() = (application as NibSyncApp).sync
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

    /** View model of the open document. Exposed for instrumented tests. */
    var canvasViewModel: CanvasViewModel? = null
        private set

    private val media by lazy { MediaActions(this, container) }

    /** Google's consent screen; its answer completes [googleConsentAnswer]. */
    private var googleConsentAnswer: kotlinx.coroutines.CompletableDeferred<Intent?>? = null
    private val googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        googleConsentAnswer?.complete(result.data)
    }

    /** Logs in with OneDrive (browser) or Google Drive (Google's sign-in on the device). */
    private suspend fun cloudLogin(service: SyncService): SyncConfig = when (service) {
        SyncService.ONEDRIVE -> oneDriveLogin(this, sync.clients)
        SyncService.GOOGLE_DRIVE -> try {
            var result = googleAuthorize(this)
            if (result.hasResolution()) {
                val answer = kotlinx.coroutines.CompletableDeferred<Intent?>().also { googleConsentAnswer = it }
                googleConsent.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
                result = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(answer.await())
            }
            googleConfig(result.accessToken ?: error("No access"))
        } catch (e: com.google.android.gms.common.api.ApiException) {
            if (!isUnregisteredApp(e.statusCode, e.message)) throw e
            error(getString(R.string.sync_google_not_registered, packageName, signingSha1(this)))
        }
        SyncService.NEXTCLOUD -> error("Nextcloud has its own dialog")
    }

    /** A to-do opened from the list (document, height in mm), shown once its page is there. */
    private var revealTodo: Pair<DocumentId, Float>? = null

    /** A picture or PDF shared from another app, inserted once the document is open. */
    private var pendingShare: Uri? by mutableStateOf(null)

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val doc = documentId
        val vm = canvasViewModel
        if (uri != null && doc != null && vm != null) lifecycleScope.launch { media.insert(vm, doc, uri, canvasView?.viewport) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    private fun takeShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        pendingShare = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) takeShared(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Files that arrived with the sync: draw pictures that were missing so far.
                container.assetsArrived.collect { docs ->
                    if (documentId in docs) canvasView?.let { it.mediaRenderer.retryMissing(); it.invalidate() }
                }
            }
        }
        val bookTitle = getString(R.string.default_book_title)
        val documentTitle = getString(R.string.default_document_title)
        setContent {
            NibSyncTheme {
                val scope = rememberCoroutineScope()
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val libraryVm = viewModel { LibraryViewModel(container.library, container::beforeDelete) }
                val tree by container.library.tree.collectAsStateWithLifecycle()
                var libraryReady by remember { mutableStateOf(false) }
                var showPageBreaks by remember { mutableStateOf(settings.showPageBreaks) }
                var drawWithFinger by remember { mutableStateOf(settings.drawWithFinger) }
                var showSettings by remember { mutableStateOf(false) }
                var showTitle by remember { mutableStateOf(settings.showTitle) }
                var showCreated by remember { mutableStateOf(settings.showCreated) }
                var penWidths by remember { mutableStateOf(settings.pens.widths) }
                if (showSettings) {
                    SettingsScreen(
                        sync = sync,
                        cloudLogin = ::cloudLogin,
                        showPageBreaks = showPageBreaks,
                        onShowPageBreaks = {
                            showPageBreaks = it
                            settings.showPageBreaks = it
                        },
                        drawWithFinger = drawWithFinger,
                        onDrawWithFinger = {
                            drawWithFinger = it
                            settings.drawWithFinger = it
                        },
                        showTitle = showTitle,
                        onShowTitle = {
                            showTitle = it
                            settings.showTitle = it
                        },
                        showCreated = showCreated,
                        onShowCreated = {
                            showCreated = it
                            settings.showCreated = it
                        },
                        penWidths = penWidths,
                        onPenWidths = {
                            penWidths = it
                            settings.pens = settings.pens.copy(widths = it)
                        },
                        onClose = { showSettings = false },
                    )
                }

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

                var showTodos by remember { mutableStateOf(false) }
                fun open(id: DocumentId) {
                    showTodos = false
                    documentId = id
                    settings.lastDocument = id
                    scope.launch { drawerState.close() }
                }

                // The generated page of open to-dos: derived from the documents, never stored.
                val todoCollector = remember {
                    TodoCollector({ container.openSessionOrNull(it)?.state?.value }, { container.repository.load(it) })
                }
                var todos by remember { mutableStateOf<List<OpenTodo>?>(null) }
                var todoRefresh by remember { mutableIntStateOf(0) }
                var ticked by remember { mutableStateOf<TickedTodo?>(null) }
                val syncStatuses by sync.statuses.collectAsStateWithLifecycle()
                LaunchedEffect(syncStatuses) {
                    // The sync may have changed stored documents.
                    todoCollector.invalidate()
                    todoRefresh++
                }
                LaunchedEffect(Unit) {
                    container.localChanges.debounce(TODO_REFRESH_MS).collect { todoRefresh++ }
                }
                LaunchedEffect(libraryReady, tree, todoRefresh) {
                    if (libraryReady) todos = todoCollector.collect(tree)
                }

                val accounts by sync.accounts.collectAsStateWithLifecycle()
                val bookAccounts by sync.books.map.collectAsStateWithLifecycle()
                val bookSync = BookSyncUi(
                    accounts = accounts.map { AccountChoice(it.id.value, "${it.name} · ${it.config.service.displayName}") },
                    accountOf = { bookAccounts[it]?.value },
                    initial = settings.lastBookAccount,
                    onCreated = { book, account ->
                        settings.lastBookAccount = account
                        if (account != null) sync.assignBook(book, AccountId(account))
                    },
                    onAssign = { book, account -> sync.assignBook(book, AccountId(account)) },
                )

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
                                footer = { SyncStatusLine(sync) },
                                onSettings = { showSettings = true },
                                bookSync = bookSync,
                                onOpenTodos = {
                                    showTodos = true
                                    scope.launch { drawerState.close() }
                                },
                                todoCount = todos?.size,
                                todosShown = showTodos,
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
                        if (showTodos) {
                            TodoListScreen(
                                todos = todos,
                                onMenu = { scope.launch { drawerState.open() } },
                                onTick = { t ->
                                    scope.launch {
                                        container.openSession(t.document, documentTitle).setTodoDone(t.item.taskId, true)
                                        ticked = TickedTodo(t)
                                        todoRefresh++
                                    }
                                },
                                onOpen = { t ->
                                    revealTodo = t.document to t.item.y
                                    open(t.document)
                                },
                                ticked = ticked,
                                onUndo = { t ->
                                    ticked = null
                                    scope.launch {
                                        container.openSession(t.todo.document, documentTitle).setTodoDone(t.todo.item.taskId, false)
                                        todoRefresh++
                                    }
                                },
                                onUndoGone = { ticked = null },
                                modifier = Modifier.safeDrawingPadding(),
                            )
                        } else if (docId == null) {
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
                            val node = tree[NodeId(docId.value)]
                            val header = PageHeader(
                                path = tree.pathTo(NodeId(docId.value)).map { it.title },
                                title = node?.title,
                                showTitle = showTitle,
                                showCreated = showCreated,
                                onRename = node?.let { n -> { title: String -> scope.launch { container.library.rename(n.id, title) }; Unit } },
                            )
                            DocumentPane(docId, documentTitle, openLibrary, showPageBreaks, drawWithFinger, header, penWidths)
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
        drawWithFinger: Boolean,
        header: PageHeader,
        penWidths: Map<BrushFamily, List<Float>>,
    ) {
        var openSession by remember(docId) { mutableStateOf<DocumentSession?>(null) }
        LaunchedEffect(docId) {
            openSession = container.openSession(docId, fallbackTitle).also { session = it }
        }
        val current = openSession
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val vm = viewModel(key = docId.value) {
                CanvasViewModel(current, GeometricShapeRecognizer(), container.clipboard, ToolState.from(settings.pens))
            }
            LaunchedEffect(vm, penWidths) { vm.setPenWidths(penWidths) }
            // The pen and its width are remembered for the next document and app start.
            LaunchedEffect(vm) {
                vm.tools.collect { tools ->
                    val pens = settings.pens
                    val used = pens.used(tools.activePen, tools.activeSettings.sizeMm)
                    if (used != pens) settings.pens = used
                }
            }
            var anchorDialog by remember(docId) { mutableStateOf<AnchorDialog?>(null) }
            var linkDialog by remember(docId) { mutableStateOf(false) }
            DisposableEffect(vm) {
                canvasViewModel = vm
                vm.openFile = { media.openLink(docId, it) }
                vm.openNote = ::followLink
                vm.editAnchor = { anchorDialog = AnchorDialog(it) }
                vm.copyAssets = media::copyAssets
                // Leaving the document (or the screen) ends text editing, so the text is stored.
                onDispose { vm.finishText() }
            }
            val shared = pendingShare
            LaunchedEffect(shared, vm) {
                if (shared != null) {
                    pendingShare = null
                    media.insert(vm, docId, shared, canvasView?.viewport)
                }
            }
            val scope = rememberCoroutineScope()
            val headerState = remember(docId) { DocumentHeaderState() }
            NoteScreen(
                vm,
                Modifier.safeDrawingPadding(),
                onMenu = onMenu,
                showPageBreaks = showPageBreaks,
                drawWithFinger = drawWithFinger,
                toolbarEnd = { SyncStatusIcon(sync) },
                assetFile = { media.assetFile(docId, it) },
                onInsertFile = { pickFile.launch(media.mimeTypes) },
                onInsertFromClipboard = { scope.launch { media.insertFromClipboard(vm, docId, canvasView?.viewport) } },
                onInsertAnchor = { anchorDialog = AnchorDialog(null) },
                onInsertNoteLink = { linkDialog = true },
                pageOverlay = { viewport ->
                    val state by current.state.collectAsStateWithLifecycle()
                    DocumentHeader(
                        title = header.title ?: state.meta.title,
                        created = remember(state.meta.createdAtEpochMs) {
                            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(state.meta.createdAtEpochMs))
                        },
                        showTitle = header.showTitle,
                        showDate = header.showCreated,
                        viewport = viewport,
                        onRename = header.onRename,
                        state = headerState,
                    )
                    DocumentPath(header.path, Modifier.align(Alignment.BottomEnd))
                },
                pageModifier = Modifier.endsTitleEditing(headerState),
                onCanvasCreated = { view ->
                    canvasView = view
                    view.linkRenderer.titleOf = { container.library.tree.value[NodeId(it.value)]?.title }
                    // Opened from the list of to-dos: show the to-do.
                    revealTodo?.takeIf { it.first == docId }?.let { view.revealY(it.second) }
                    revealTodo = null
                },
            )
            anchorDialog?.let { dialog ->
                val anchor = dialog.anchor
                AnchorNameDialog(anchor?.name ?: "", onDismiss = { anchorDialog = null }) { name ->
                    anchorDialog = null
                    if (anchor != null) {
                        vm.renameAnchor(anchor, name)
                    } else {
                        val v = canvasView?.viewport?.takeIf { it.hasSize }
                        val r = v?.visibleDocumentRect
                        val pageWidth = v?.pageWidthMm ?: current.state.value.meta.format.widthMm
                        val w = Anchor.HEIGHT_MM
                        val cx = r?.let { (it.left + it.right) / 2 } ?: (pageWidth / 2)
                        val x = (cx - w / 2).coerceIn(0f, maxOf(0f, pageWidth - w))
                        vm.insert(listOf(AnchorDraft(x, maxOf(0f, r?.let { (it.top + it.bottom) / 2 } ?: 50f), name)))
                    }
                }
            }
            if (linkDialog) {
                val tree by container.library.tree.collectAsStateWithLifecycle()
                NoteLinkDialog(
                    tree = tree,
                    anchorsOf = { doc -> LinkTargets.anchors(container.openSessionOrNull(doc)?.state?.value ?: container.repository.load(doc)) },
                    onDismiss = { linkDialog = false },
                ) { target, anchor ->
                    linkDialog = false
                    val v = canvasView?.viewport?.takeIf { it.hasSize }
                    val r = v?.visibleDocumentRect
                    val pageWidth = v?.pageWidthMm ?: current.state.value.meta.format.widthMm
                    val cx = r?.let { (it.left + it.right) / 2 } ?: (pageWidth / 2)
                    val cy = r?.let { (it.top + it.bottom) / 2 } ?: 50f
                    vm.insert(listOf(LinkTargets.draft(target, anchor, cx, cy, pageWidth)))
                }
            }
        }
    }

    /** Opens the document a link leads to, at its anchor; a short message if it is not here. */
    private fun followLink(target: LinkTarget) {
        lifecycleScope.launch {
            val tree = container.library.tree.value
            val node = tree[NodeId(target.document.value)]
            val doc = if (node != null) runCatching { container.openSession(target.document, node.title).state.value }.getOrNull() else null
            val y = LinkTargets.revealY(target, tree, doc)
            if (y == null) {
                Toast.makeText(this@MainActivity, R.string.note_not_found, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (target.document == documentId) {
                canvasView?.revealY(y)
            } else {
                revealTodo = target.document to y
                documentId = target.document
                settings.lastDocument = target.document
            }
        }
    }

    /** The anchor name dialog: for a new anchor ([anchor] null) or renaming one. */
    private class AnchorDialog(val anchor: Anchor?)

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
        // While a text box or a typed to-do is edited, the editor handles its own undo and redo.
        if (canvasViewModel?.textEditing?.value != null || canvasViewModel?.todoEditing?.value?.text != null) return super.dispatchKeyEvent(event)
        // Ctrl+V: a picture or PDF from the system clipboard, else copied strokes.
        if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_V) {
            val vm = canvasViewModel
            val doc = documentId
            if (event.action == KeyEvent.ACTION_DOWN && vm != null && doc != null) {
                val viewport = canvasView?.viewport
                if (media.clipboardUri() != null) {
                    lifecycleScope.launch { media.insertFromClipboard(vm, doc, viewport) }
                } else if (viewport != null && viewport.hasSize) {
                    val r = viewport.visibleDocumentRect
                    vm.paste((r.left + r.right) / 2, (r.top + r.bottom) / 2)
                }
            }
            return true
        }
        if (s != null && event.isCtrlPressed && (event.keyCode == KeyEvent.KEYCODE_Z || event.keyCode == KeyEvent.KEYCODE_Y)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.keyCode == KeyEvent.KEYCODE_Z && !event.isShiftPressed) s.undo() else s.redo()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        sync.start()
    }

    override fun onStop() {
        super.onStop()
        sync.stop()
        canvasViewModel?.saveText()
        canvasViewModel?.saveTodo()
        // Make sure everything written so far reaches the disk while the app is in background.
        val s = session ?: return
        container.appScope.launch { s.flush() }
    }

    /** What the page shows about the document itself: title and creation time on top, path below. */
    class PageHeader(
        val path: List<String>,
        /** The library title; null for a document outside the library (then the stored title). */
        val title: String?,
        val showTitle: Boolean,
        val showCreated: Boolean,
        /** Renames the document; null: the title is read-only. */
        val onRename: ((String) -> Unit)?,
    )

    companion object {
        /** Edits are collected this long before the list of open to-dos is rebuilt. */
        private const val TODO_REFRESH_MS = 800L

        const val EXTRA_DOCUMENT_ID = "com.stemaker.nibsync.extra.DOCUMENT_ID"
    }
}
