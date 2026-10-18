package de.nibsync.app.sync

import de.nibsync.app.AppContainer
import de.nibsync.sync.SyncEngine
import de.nibsync.sync.webdav.AuthenticationException
import de.nibsync.sync.webdav.WebDavRemoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

sealed interface SyncStatus {
    data object NotConfigured : SyncStatus
    data object Syncing : SyncStatus
    data class Synced(val atEpochMs: Long) : SyncStatus
    data class Failed(val message: String, val loginInvalid: Boolean) : SyncStatus
}

/**
 * Runs the sync while the app is in the foreground: right away, every [INTERVAL_MS], shortly
 * after local changes and when the app goes to the background. Ops received from other
 * devices go straight into open documents.
 */
@OptIn(FlowPreview::class)
class SyncController(
    private val container: AppContainer,
    private val accounts: SyncAccountStore,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _config = MutableStateFlow(accounts.load())
    val config: StateFlow<SyncConfig?> = _config.asStateFlow()

    private var engine: SyncEngine? = null
    private val runLock = Mutex()
    private var loop: Job? = null

    init {
        rebuildEngine()
        scope.launch {
            container.localChanges.debounce(LOCAL_CHANGE_DELAY_MS).collect { if (loop != null) syncNow() }
        }
    }

    fun configure(config: SyncConfig?) {
        accounts.save(config)
        _config.value = config
        rebuildEngine()
        if (config != null) syncNow()
    }

    /** The app came to the foreground. */
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
        runLock.withLock {
            val engine = engine ?: return
            // Flush local writes first so the newest strokes go out in this run.
            container.flushOpenSessions()
            _status.value = SyncStatus.Syncing
            _status.value = try {
                val report = engine.sync()
                for ((doc, ops) in report.receivedOps) container.openSessionOrNull(doc)?.applyRemote(ops)
                SyncStatus.Synced(System.currentTimeMillis())
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
                fs = container.fs,
                documentsRoot = container.documentsRoot,
                // Separate state per server and folder: switching starts a fresh comparison.
                stateFile = container.localRoot / "sync-state-${hash("${it.account.serverUrl}|${it.account.loginName}|${it.folder}")}.json",
                library = container.library,
                device = container.deviceId,
                remote = WebDavRemoteStore(it.account.serverUrl, it.account.loginName, it.account.appPassword, it.folder),
                io = Dispatchers.IO,
                beforeDocumentsDeleted = container::beforeDelete,
            )
        }
        if (c == null) _status.value = SyncStatus.NotConfigured
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    companion object {
        const val INTERVAL_MS = 10_000L
        const val LOCAL_CHANGE_DELAY_MS = 2_000L
    }
}
