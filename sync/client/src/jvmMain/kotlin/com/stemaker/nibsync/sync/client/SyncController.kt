package com.stemaker.nibsync.sync.client

import com.stemaker.nibsync.document.DocumentOp
import com.stemaker.nibsync.library.Library
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.sync.RemoteStore
import com.stemaker.nibsync.sync.SyncEngine
import com.stemaker.nibsync.sync.BookScope
import com.stemaker.nibsync.library.NodeId
import kotlinx.coroutines.CancellationException
import com.stemaker.nibsync.sync.webdav.AuthenticationException
import com.stemaker.nibsync.sync.webdav.NextcloudAccount
import com.stemaker.nibsync.sync.webdav.WebDavRemoteStore
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

/** The cloud services NibSync can sync with. Persisted by name: never rename. */
enum class SyncService { NEXTCLOUD, ONEDRIVE, GOOGLE_DRIVE }

/** Where and as whom to sync. */
sealed interface SyncConfig {
    val service: SyncService

    /** Who and where, for the library and the settings ("stefan @ cloud.example.org / NibSync"). */
    val description: String

    /** Identifies the remote place; each has its own sync state on this device. */
    val stateKey: String

    companion object {
        const val DEFAULT_FOLDER = "NibSync"
    }
}

/** A folder in a Nextcloud, accessed with an app password (WebDAV). */
data class NextcloudConfig(val account: NextcloudAccount, val folder: String = SyncConfig.DEFAULT_FOLDER) : SyncConfig {
    override val service get() = SyncService.NEXTCLOUD
    override val description: String
        get() = "${account.loginName} @ ${account.serverUrl.removePrefix("https://").removePrefix("http://")} / $folder"

    // Unchanged from the first sync version, so existing sync state is kept.
    override val stateKey: String get() = "${account.serverUrl}|${account.loginName}|$folder"
}

/** The app folder "Apps/NibSync" of a OneDrive (Microsoft Graph), via an OAuth refresh token. */
data class OneDriveConfig(val user: String, val refreshToken: String) : SyncConfig {
    override val service get() = SyncService.ONEDRIVE
    override val description: String get() = "$user / OneDrive / Apps/NibSync"
    override val stateKey: String get() = "onedrive|$user"
}

/** The folder "NibSync" in a Google Drive, via an OAuth refresh token (scope drive.file). */
data class GoogleDriveConfig(val user: String, val refreshToken: String) : SyncConfig {
    override val service get() = SyncService.GOOGLE_DRIVE
    override val description: String get() = "$user / Google Drive / ${SyncConfig.DEFAULT_FOLDER}"
    override val stateKey: String get() = "gdrive|$user"
}

sealed interface SyncStatus {
    data object NotConfigured : SyncStatus
    data object Syncing : SyncStatus
    data class Synced(val atEpochMs: Long) : SyncStatus
    data class Failed(val message: String, val loginInvalid: Boolean) : SyncStatus
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
 * Runs the sync of all accounts while the app is in use: right away, every [INTERVAL_MS],
 * shortly after local changes and once more when the app goes to the background or closes.
 * Each account syncs only its books ([BookAccounts]); books of no account stay on this device.
 * Ops received from other devices go straight into open documents.
 */
@OptIn(FlowPreview::class)
class SyncController(
    private val host: SyncHost,
    private val store: AccountStore,
    private val scope: CoroutineScope,
    /** The OAuth clients this build has, for OneDrive and Google Drive. */
    val clients: CloudClients = CloudClients(),
    /** On Android: access tokens for a Google account (by email) from Google's sign-in. */
    private val googleTokens: ((String) -> com.stemaker.nibsync.sync.cloud.AccessTokens)? = null,
    /** The remote store for a configuration (tests replace it). */
    remoteFor: ((SyncConfig) -> RemoteStore)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val remoteFor: (SyncConfig) -> RemoteStore = remoteFor ?: { config ->
        CloudStores.storeFor(config, clients, (config as? GoogleDriveConfig)?.let { googleTokens?.invoke(it.user) }) { rememberLogin(it) }
    }

    /** Which services can be connected in this build. */
    fun available(service: SyncService): Boolean = when (service) {
        SyncService.NEXTCLOUD -> true
        SyncService.ONEDRIVE -> clients.oneDrive != null
        SyncService.GOOGLE_DRIVE -> clients.googleOnDevice || clients.googleDesktop != null
    }

    private val _accounts = MutableStateFlow(AccountsCodec.fromMap(store.load()))
    val accounts: StateFlow<List<SyncAccount>> = _accounts.asStateFlow()

    private val _statuses = MutableStateFlow<Map<AccountId, SyncStatus>>(emptyMap())

    /** Status per account (missing: not synced yet in this app run). */
    val statuses: StateFlow<Map<AccountId, SyncStatus>> = _statuses.asStateFlow()

    val books = BookAccounts(host.fs, host.stateDir / BOOK_ACCOUNTS_FILE)

    /** Tests pause the sync while they change the library behind its back. */
    @Volatile
    var paused = false

    private val engines = HashMap<AccountId, SyncEngine>()
    private val runLock = Mutex()
    private var loop: Job? = null

    init {
        rebuildEngines()
        scope.launch {
            host.localChanges.debounce(LOCAL_CHANGE_DELAY_MS).collect { if (loop != null) syncNow() }
        }
    }

    fun account(id: AccountId): SyncAccount? = _accounts.value.firstOrNull { it.id == id }

    /**
     * Adds an account and syncs it: its books on the server come to this device. Returns null if
     * the same login and place is set up already.
     */
    fun addAccount(name: String, config: SyncConfig): SyncAccount? = synchronized(this) {
        if (_accounts.value.any { it.config.stateKey == config.stateKey }) return null
        val account = SyncAccount(AccountId.new(), name.trim().ifEmpty { config.service.displayName }, config)
        saveAccounts(_accounts.value + account)
        account
    }.also { syncNow() }

    fun renameAccount(id: AccountId, name: String) = synchronized(this) {
        saveAccounts(_accounts.value.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })
    }

    /** A new login for the same place (e.g. after the old one was revoked); books stay with the account. */
    fun updateLogin(id: AccountId, config: SyncConfig) {
        synchronized(this) { saveAccounts(_accounts.value.map { if (it.id == id) it.copy(config = config) else it }) }
        syncNow()
    }

    /** Removes the account from this device; its books stay here, unsynced. Server and other devices are untouched. */
    fun removeAccount(id: AccountId) {
        val account = account(id) ?: return
        synchronized(this) { saveAccounts(_accounts.value.filter { it.id != id }) }
        books.release(id)
        host.fs.delete(stateFile(host.stateDir, account.config), mustExist = false)
        _statuses.value = _statuses.value - id
    }

    /** Syncs a book (only on this device so far) with [account] from now on. */
    fun assignBook(book: NodeId, account: AccountId) {
        if (books.accountOf(book) != null || account(account) == null) return
        books.assign(listOf(book), account)
        syncNow()
    }

    /** Stores a renewed login (new refresh token) without restarting the sync. */
    private fun rememberLogin(config: SyncConfig) = synchronized(this) {
        val list = _accounts.value
        if (list.none { it.config.stateKey == config.stateKey }) return
        val updated = list.map { if (it.config.stateKey == config.stateKey) it.copy(config = config) else it }
        store.save(AccountsCodec.toMap(updated))
        _accounts.value = updated
    }

    private fun saveAccounts(list: List<SyncAccount>) {
        store.save(AccountsCodec.toMap(list))
        _accounts.value = list
        rebuildEngines()
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
     * One sync of every account; returns when done. Runs are serialised, so a run requested
     * while another is in progress still happens afterwards with the current accounts (an
     * unchanged server costs one listing). A failing account does not hold up the others.
     */
    suspend fun runOnce() {
        if (paused || !host.library.isLoaded) return
        runLock.withLock {
            migrateBooks()
            val current = synchronized(this) { engines.toMap() }
            if (current.isEmpty()) return
            // Flush local writes first so the newest changes go out in this run.
            host.flushOpenSessions()
            for ((id, engine) in current) {
                if (account(id) == null) continue
                setStatus(id, SyncStatus.Syncing)
                setStatus(id, try {
                    val report = engine.sync()
                    if (report.receivedAssets.isNotEmpty()) host.assetsArrived(report.receivedAssets)
                    for ((doc, ops) in report.receivedOps) host.applyRemote(doc, ops)
                    SyncStatus.Synced(clock())
                } catch (e: AuthenticationException) {
                    SyncStatus.Failed(e.message ?: "Login", loginInvalid = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SyncStatus.Failed(e.message ?: e.javaClass.simpleName, loginInvalid = false)
                })
            }
        }
    }

    /** Versions before accounts synced every book with their one login: those books stay with it. */
    private suspend fun migrateBooks() {
        if (books.exists) return
        if (account(AccountId.LEGACY) != null) {
            val all = host.library.allNodes().filter { it.parent == null && it.isFolder }.map { it.id }
            books.assign(all, AccountId.LEGACY)
        } else {
            books.markMigrated()
        }
    }

    private fun setStatus(id: AccountId, status: SyncStatus) {
        if (account(id) != null) _statuses.value = _statuses.value + (id to status)
    }

    private fun rebuildEngines() = synchronized(this) {
        val list = _accounts.value
        engines.keys.retainAll(list.map { it.id }.toSet())
        for (account in list) {
            // A changed login gets a new engine (same state file: same place).
            engines[account.id] = SyncEngine(
                fs = host.fs,
                documentsRoot = host.documentsRoot,
                stateFile = stateFile(host.stateDir, account.config),
                library = host.library,
                device = host.deviceId,
                remote = remoteFor(account.config),
                io = Dispatchers.IO,
                beforeDocumentsDeleted = host::beforeDocumentsDeleted,
                scope = bookScope(account.id),
            )
        }
    }

    private fun bookScope(id: AccountId) = object : BookScope {
        override fun owns(book: NodeId) = books.accountOf(book) == id
        override fun isAssigned(book: NodeId) = books.accountOf(book) != null
        override suspend fun adopt(books: Set<NodeId>) = this@SyncController.books.assign(books, id)
    }

    companion object {
        const val INTERVAL_MS = 10_000L
        const val LOCAL_CHANGE_DELAY_MS = 2_000L
        const val BOOK_ACCOUNTS_FILE = "book-accounts.txt"

        /** Separate state per remote place: switching starts a fresh comparison. */
        fun stateFile(dir: Path, config: SyncConfig): Path {
            val key = config.stateKey
            val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            return dir / "sync-state-$hash.json"
        }
    }
}
