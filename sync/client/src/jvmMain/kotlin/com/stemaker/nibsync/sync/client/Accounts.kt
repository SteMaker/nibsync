package com.stemaker.nibsync.sync.client

import com.stemaker.nibsync.library.NodeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.FileSystem
import okio.Path
import java.util.UUID

/** Id of a sync account on this device (other devices have their own ids). */
@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '-' }) { "Invalid AccountId '$value'" }
    }

    companion object {
        fun new() = AccountId(UUID.randomUUID().toString())

        /** The single sync login of versions before accounts. */
        val LEGACY = AccountId("legacy")
    }
}

/** A named place to sync books with, e.g. "Privat" on a Nextcloud or "Arbeit" on OneDrive. */
data class SyncAccount(val id: AccountId, val name: String, val config: SyncConfig)

/** The brand name of a service (the same in every language). */
val SyncService.displayName: String
    get() = when (this) {
        SyncService.NEXTCLOUD -> "Nextcloud"
        SyncService.ONEDRIVE -> "OneDrive"
        SyncService.GOOGLE_DRIVE -> "Google Drive"
    }

/** Keeps the accounts (with their logins) in private platform storage as key/value pairs. */
interface AccountStore {
    fun load(): Map<String, String>
    fun save(entries: Map<String, String>)
}

/**
 * Accounts as key/value pairs: "accounts" lists the ids, "account.<id>.name" and
 * "account.<id>.<config key>" hold each one. Entries of the versions before accounts (one
 * configuration, see [SyncConfigCodec]) become the account [AccountId.LEGACY].
 */
object AccountsCodec {
    fun toMap(accounts: List<SyncAccount>): Map<String, String> = buildMap {
        put(ACCOUNTS, accounts.joinToString(",") { it.id.value })
        for (a in accounts) {
            put("account.${a.id.value}.name", a.name)
            SyncConfigCodec.toMap(a.config).forEach { (k, v) -> put("account.${a.id.value}.$k", v) }
        }
    }

    fun fromMap(map: Map<String, String>): List<SyncAccount> {
        val ids = map[ACCOUNTS] ?: return SyncConfigCodec.fromMap(map)
            ?.let { listOf(SyncAccount(AccountId.LEGACY, it.service.displayName, it)) }
            .orEmpty()
        return ids.split(',').filter { it.isNotBlank() }.mapNotNull { raw ->
            val id = runCatching { AccountId(raw) }.getOrNull() ?: return@mapNotNull null
            val prefix = "account.$raw."
            val config = SyncConfigCodec.fromMap(SyncConfigCodec.KEYS.associateWith { map[prefix + it] }) ?: return@mapNotNull null
            SyncAccount(id, map[prefix + "name"] ?: config.service.displayName, config)
        }
    }

    private const val ACCOUNTS = "accounts"
}

/**
 * Which account each book syncs with, on this device (account ids differ between devices; a
 * book that arrives from an account's server belongs to it). Books without an entry stay on
 * this device. Kept as lines "<book>=<account>" in [file].
 */
class BookAccounts(private val fs: FileSystem, private val file: Path) {
    private val lock = Any()
    private val _map = MutableStateFlow(load())
    val map: StateFlow<Map<NodeId, AccountId>> = _map.asStateFlow()

    /** False until the first save: the assignments of versions before accounts are still to be made. */
    var exists: Boolean = fs.exists(file)
        private set

    fun accountOf(book: NodeId): AccountId? = _map.value[book]

    fun assign(books: Collection<NodeId>, account: AccountId) = update { m -> books.forEach { m[it] = account } }

    /** The account's books stay on this device only. */
    fun release(account: AccountId) = update { m -> m.values.removeAll { it == account } }

    /** Writes the file even without assignments (marks the migration as done). */
    fun markMigrated() = update { }

    private fun update(change: (MutableMap<NodeId, AccountId>) -> Unit) = synchronized(lock) {
        val m = _map.value.toMutableMap()
        change(m)
        file.parent?.let(fs::createDirectories)
        val tmp = file.parent!! / "${file.name}.tmp"
        fs.write(tmp) { writeUtf8(m.entries.joinToString("") { "${it.key.value}=${it.value.value}\n" }) }
        fs.atomicMove(tmp, file)
        exists = true
        _map.value = m
    }

    private fun load(): Map<NodeId, AccountId> {
        if (!fs.exists(file)) return emptyMap()
        return fs.read(file) { readUtf8() }.lineSequence().mapNotNull { line ->
            val (book, account) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            runCatching { NodeId(book.trim()) to AccountId(account.trim()) }.getOrNull()
        }.toMap()
    }
}
