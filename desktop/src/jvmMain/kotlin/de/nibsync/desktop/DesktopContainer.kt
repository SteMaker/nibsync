package de.nibsync.desktop

import de.nibsync.document.DocumentOp
import de.nibsync.document.DocumentSession
import de.nibsync.document.OpJournal
import de.nibsync.library.FileLibraryRepository
import de.nibsync.library.Library
import de.nibsync.model.DeviceId
import de.nibsync.model.DocumentId
import de.nibsync.storage.DeviceIdStore
import de.nibsync.storage.FileDocumentRepository
import de.nibsync.sync.client.SyncHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path

/**
 * Wiring of the PC app, the counterpart of the Android AppContainer: the same file layout
 * under [root], so a synced folder looks the same on every device.
 */
class DesktopContainer(
    val root: Path,
    override val fs: FileSystem = FileSystem.SYSTEM,
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SyncHost {
    override val documentsRoot: Path = root / "documents"
    val libraryRoot: Path = root / "library"
    override val stateDir: Path get() = root

    override val deviceId: DeviceId by lazy {
        fs.createDirectories(root)
        DeviceIdStore.loadOrCreate(fs, root / "device-id")
    }

    val repository = FileDocumentRepository(fs, documentsRoot, Dispatchers.IO)

    override val library = Library(
        nodes = FileLibraryRepository(fs, libraryRoot, Dispatchers.IO),
        documents = repository,
        clock = System::currentTimeMillis,
    )

    private val _localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val localChanges: SharedFlow<Unit> = _localChanges.asSharedFlow()

    private val _assetsArrived = MutableSharedFlow<Set<DocumentId>>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Documents whose files (pictures, PDFs) were just fetched by the sync. */
    val assetsArrivedFlow: SharedFlow<Set<DocumentId>> = _assetsArrived.asSharedFlow()

    private val sessions = HashMap<DocumentId, DocumentSession>()
    private val sessionLock = Mutex()

    suspend fun openSession(id: DocumentId): DocumentSession = sessionLock.withLock {
        sessions.getOrPut(id) {
            val journal = repository.journal(id, deviceId)
            DocumentSession(
                initial = repository.load(id),
                device = deviceId,
                journal = OpJournal { op ->
                    journal.append(op)
                    _localChanges.tryEmit(Unit)
                },
                scope = appScope,
                clock = System::currentTimeMillis,
            )
        }
    }

    suspend fun openSessionOrNull(id: DocumentId): DocumentSession? = sessionLock.withLock { sessions[id] }

    /** Run before the sessions close, e.g. to store an open text box. */
    val beforeClose = mutableListOf<() -> Unit>()

    suspend fun closeAll() = sessionLock.withLock {
        beforeClose.toList().forEach { it() }
        sessions.values.forEach { it.flush(); it.close() }
        sessions.clear()
    }

    override suspend fun flushOpenSessions() {
        sessionLock.withLock { sessions.values.toList() }.forEach { it.flush() }
    }

    override suspend fun applyRemote(doc: DocumentId, ops: List<DocumentOp>) {
        openSessionOrNull(doc)?.applyRemote(ops)
    }

    override suspend fun beforeDocumentsDeleted(docs: Set<DocumentId>) = sessionLock.withLock {
        for (doc in docs) sessions.remove(doc)?.let { it.flush(); it.close() }
    }

    override fun assetsArrived(docs: Set<DocumentId>) {
        _assetsArrived.tryEmit(docs)
    }
}
