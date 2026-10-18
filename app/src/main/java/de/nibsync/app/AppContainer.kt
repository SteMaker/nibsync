package de.nibsync.app

import de.nibsync.canvas.StrokeClipboard
import de.nibsync.document.DocumentOp
import de.nibsync.document.DocumentSession
import de.nibsync.sync.client.SyncHost
import de.nibsync.document.OpJournal
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import de.nibsync.library.FileLibraryRepository
import de.nibsync.library.Library
import de.nibsync.model.DeviceId
import de.nibsync.model.DocumentId
import de.nibsync.model.DocumentMeta
import de.nibsync.storage.DeviceIdStore
import de.nibsync.storage.DocumentRepository
import de.nibsync.storage.FileDocumentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path

/**
 * Manual dependency wiring of the app. Documents live below [documentsRoot]; this directory
 * will later be mirrored to a Nextcloud share. Device-local files live outside of it.
 */
class AppContainer(
    override val fs: FileSystem,
    val localRoot: Path,
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SyncHost {
    override val documentsRoot: Path = localRoot / "documents"

    override val stateDir: Path get() = localRoot

    override val deviceId: DeviceId by lazy { DeviceIdStore.loadOrCreate(fs, localRoot / "device-id") }

    val repository: DocumentRepository = FileDocumentRepository(fs, documentsRoot, Dispatchers.IO)

    val libraryRoot: Path = localRoot / "library"

    override val library = Library(
        nodes = FileLibraryRepository(fs, libraryRoot, Dispatchers.IO),
        documents = repository,
        clock = System::currentTimeMillis,
    )
    private var libraryLoaded = false
    private val libraryLock = Mutex()

    /** Shared by all documents, so strokes can be copied from one document into another. */
    val clipboard = StrokeClipboard()

    /** Loads the library once per process; notes from before the library existed are adopted. */
    suspend fun ensureLibraryLoaded(bookTitle: String, documentTitle: String) = libraryLock.withLock {
        if (!libraryLoaded) {
            library.load(bookTitle, documentTitle, legacyDocument = LEGACY_DOCUMENT)
            libraryLoaded = true
        }
    }

    /** Closes open sessions of the documents before they are deleted, so nothing is written back. */
    suspend fun beforeDelete(documents: Set<DocumentId>) {
        documents.forEach { closeSession(it) }
    }

    private val _localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits after this device wrote a change to any document (for syncing soon). */
    override val localChanges: SharedFlow<Unit> = _localChanges.asSharedFlow()

    private val _assetsArrived = MutableSharedFlow<Set<DocumentId>>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Documents whose files (pictures, PDFs) were just fetched by the sync. */
    val assetsArrived: SharedFlow<Set<DocumentId>> = _assetsArrived.asSharedFlow()

    override fun assetsArrived(docs: Set<DocumentId>) {
        _assetsArrived.tryEmit(docs)
    }

    override suspend fun applyRemote(doc: DocumentId, ops: List<DocumentOp>) {
        openSessionOrNull(doc)?.applyRemote(ops)
    }

    override suspend fun beforeDocumentsDeleted(docs: Set<DocumentId>) = beforeDelete(docs)

    private val sessions = HashMap<DocumentId, DocumentSession>()
    private val sessionLock = Mutex()

    /** Opens (creating it on first use) the document and returns its shared editing session. */
    suspend fun openSession(id: DocumentId, title: String): DocumentSession = sessionLock.withLock {
        sessions[id]?.let { return it }
        if (!repository.exists(id)) {
            repository.create(DocumentMeta(id = id, title = title, createdAtEpochMs = System.currentTimeMillis()))
        }
        val session = DocumentSession(
            initial = repository.load(id),
            device = deviceId,
            journal = repository.journal(id, deviceId).let { journal ->
                OpJournal { op ->
                    journal.append(op)
                    _localChanges.tryEmit(Unit)
                }
            },
            scope = appScope,
            clock = System::currentTimeMillis,
        )
        sessions[id] = session
        session
    }

    companion object {
        /** The single document of app versions before the library. */
        val LEGACY_DOCUMENT = DocumentId("default")
    }

    /** The open session of [id], if any, e.g. to show changes from other devices. */
    suspend fun openSessionOrNull(id: DocumentId): DocumentSession? = sessionLock.withLock { sessions[id] }

    /** Waits until all open documents have written their changes. */
    override suspend fun flushOpenSessions() {
        sessionLock.withLock { sessions.values.toList() }.forEach { it.flush() }
    }

    /** Closes the session and forgets it; the next [openSession] reloads from storage. */
    suspend fun closeSession(id: DocumentId) = sessionLock.withLock {
        sessions.remove(id)?.let {
            it.flush()
            it.close()
        }
    }
}
