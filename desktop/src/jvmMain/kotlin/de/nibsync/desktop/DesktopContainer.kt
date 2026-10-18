package de.nibsync.desktop

import de.nibsync.document.DocumentSession
import de.nibsync.library.FileLibraryRepository
import de.nibsync.library.Library
import de.nibsync.model.DeviceId
import de.nibsync.model.DocumentId
import de.nibsync.storage.DeviceIdStore
import de.nibsync.storage.FileDocumentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val fs: FileSystem = FileSystem.SYSTEM,
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val documentsRoot: Path = root / "documents"
    val libraryRoot: Path = root / "library"

    val deviceId: DeviceId by lazy {
        fs.createDirectories(root)
        DeviceIdStore.loadOrCreate(fs, root / "device-id")
    }

    val repository = FileDocumentRepository(fs, documentsRoot, Dispatchers.IO)

    val library = Library(
        nodes = FileLibraryRepository(fs, libraryRoot, Dispatchers.IO),
        documents = repository,
        clock = System::currentTimeMillis,
    )

    private val sessions = HashMap<DocumentId, DocumentSession>()
    private val sessionLock = Mutex()

    suspend fun openSession(id: DocumentId): DocumentSession = sessionLock.withLock {
        sessions.getOrPut(id) {
            DocumentSession(
                initial = repository.load(id),
                device = deviceId,
                journal = repository.journal(id, deviceId),
                scope = appScope,
                clock = System::currentTimeMillis,
            )
        }
    }

    /** Run before the sessions close, e.g. to store an open text box. */
    val beforeClose = mutableListOf<() -> Unit>()

    suspend fun closeAll() = sessionLock.withLock {
        beforeClose.toList().forEach { it() }
        sessions.values.forEach { it.flush(); it.close() }
        sessions.clear()
    }
}
