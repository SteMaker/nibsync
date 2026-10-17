package de.nibsync.app

import de.nibsync.canvas.StrokeClipboard
import de.nibsync.document.DocumentSession
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
    private val fs: FileSystem,
    localRoot: Path,
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val documentsRoot: Path = localRoot / "documents"

    val deviceId: DeviceId by lazy { DeviceIdStore.loadOrCreate(fs, localRoot / "device-id") }

    val repository: DocumentRepository = FileDocumentRepository(fs, documentsRoot, Dispatchers.IO)

    val libraryRoot: Path = localRoot / "library"

    val library = Library(
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
            journal = repository.journal(id, deviceId),
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

    /** Closes the session and forgets it; the next [openSession] reloads from storage. */
    suspend fun closeSession(id: DocumentId) = sessionLock.withLock {
        sessions.remove(id)?.let {
            it.flush()
            it.close()
        }
    }
}
