package de.nibsync.sync.client

import de.nibsync.document.DocumentOp
import de.nibsync.library.Library
import de.nibsync.model.DeviceId
import de.nibsync.model.DocumentId
import de.nibsync.sync.RemoteStore
import de.nibsync.sync.SyncEngine
import de.nibsync.sync.webdav.AuthenticationException
import de.nibsync.sync.webdav.NextcloudAccount
import de.nibsync.sync.webdav.WebDavRemoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import java.security.MessageDigest

/** Where and as whom to sync. */
data class SyncConfig(val account: NextcloudAccount, val folder: String) {
    companion object {
        const val DEFAULT_FOLDER = "NibSync"
    }
}

sealed interface SyncStatus {
    data object NotConfigured : SyncStatus
    data object Syncing : SyncStatus
    data class Synced(val atEpochMs: Long) : SyncStatus
    data class Failed(val message: String, val loginInvalid: Boolean) : SyncStatus
}

/** Keeps the login between app runs (platform specific: app-private storage). */
interface SyncConfigStore {
    fun load(): SyncConfig?
    fun save(config: SyncConfig?)
}

/** What the sync needs from the app: its files, library and open documents. */
interface SyncHost {
    val fs: FileSystem
    val documentsRoot: Path

    /** Where this device keeps its sync state files. */
    val stateDir: Path
    val library: Library
    val deviceId: DeviceId

    /** Emits after this device changed a document. */
    val localChanges: Flow<Unit>

    /** Waits until open documents have written their changes. */
    suspend fun flushOpenSessions()

    /** Shows ops received from other devices in the open session of [doc], if any. */
    suspend fun applyRemote(doc: DocumentId, ops: List<DocumentOp>)

    /** Closes documents that another device deleted, before their files go. */
    suspend fun beforeDocumentsDeleted(docs: Set<DocumentId>)

    /** Files (pictures, PDFs) of these documents just arrived. */
    fun assetsArrived(docs: Set<DocumentId>)
}

/**
 * Runs the sync while the app is in use: right away, every [INTERVAL_MS], shortly after local
 * changes and once more when the app goes to the background or closes. Ops received from
 * other devices go straight into open documents.
 */
@OptIn(FlowPreview::class)
class SyncController(
    private val host: SyncHost,
    private val store: SyncConfigStore,
    private val scope: CoroutineScope,
    private val remoteFor: (SyncConfig) -> RemoteStore = {
        WebDavRemoteStore(it.account.serverUrl, it.account.loginName, it.account.appPassword, it.folder)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<SyncConfig?> = _config.asStateFlow()

    /**
     * While true no sync runs. Instrumented tests set it so they never sync test data with
     * the account configured on the device. Not persisted.
     */
    @Volatile
    var paused: Boolean = false

    private var engine: SyncEngine? = null
    private val runLock = Mutex()
    private var loop: Job? = null

    init {
        rebuildEngine()
        scope.launch {
            host.localChanges.debounce(LOCAL_CHANGE_DELAY_MS).collect { if (loop != null) syncNow() }
        }
    }

    fun configure(config: SyncConfig?) {
        store.save(config)
        _config.value = config
        rebuildEngine()
        if (config != null) syncNow()
    }

    /** The app came to the foreground (or started). */
    fun start() {
        if (loop != null) return
        loop = scope.launch {
            while (true) {
                runOnce()
                delay(INTERVAL_MS)
            }
        }
    }

    /** The app went to the background: stop polling, push what is left. */
    fun stop() {
        loop?.cancel()
        loop = null
        syncNow()
    }

    fun syncNow() {
        scope.launch { runOnce() }
    }

    /**
     * One sync; returns when done. Runs are serialised, so a run requested while another is in
     * progress still happens afterwards with the current configuration (an unchanged server
     * costs one PROPFIND).
     */
    suspend fun runOnce() {
        if (paused) return
        runLock.withLock {
            val engine = engine ?: return
            // Flush local writes first so the newest changes go out in this run.
            host.flushOpenSessions()
            _status.value = SyncStatus.Syncing
            _status.value = try {
                val report = engine.sync()
                if (report.receivedAssets.isNotEmpty()) host.assetsArrived(report.receivedAssets)
                for ((doc, ops) in report.receivedOps) host.applyRemote(doc, ops)
                SyncStatus.Synced(clock())
            } catch (e: AuthenticationException) {
                SyncStatus.Failed(e.message ?: "Login", loginInvalid = true)
            } catch (e: Exception) {
                SyncStatus.Failed(e.message ?: e.javaClass.simpleName, loginInvalid = false)
            }
        }
    }

    private fun rebuildEngine() {
        val c = _config.value
        engine = c?.let {
            SyncEngine(
                fs = host.fs,
                documentsRoot = host.documentsRoot,
                stateFile = stateFile(host.stateDir, it),
                library = host.library,
                device = host.deviceId,
                remote = remoteFor(it),
                io = Dispatchers.IO,
                beforeDocumentsDeleted = host::beforeDocumentsDeleted,
            )
        }
        if (c == null) _status.value = SyncStatus.NotConfigured
    }

    companion object {
        const val INTERVAL_MS = 10_000L
        const val LOCAL_CHANGE_DELAY_MS = 2_000L

        /** Separate state per server, login and folder: switching starts a fresh comparison. */
        fun stateFile(dir: Path, config: SyncConfig): Path {
            val key = "${config.account.serverUrl}|${config.account.loginName}|${config.folder}"
            val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            return dir / "sync-state-$hash.json"
        }
    }
}
