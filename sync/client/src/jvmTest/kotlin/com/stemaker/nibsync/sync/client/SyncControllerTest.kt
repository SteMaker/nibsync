package com.stemaker.nibsync.sync.client

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.document.DocumentOp
import com.stemaker.nibsync.library.FileLibraryRepository
import com.stemaker.nibsync.library.Library
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.storage.FileDocumentRepository
import com.stemaker.nibsync.sync.webdav.NextcloudAccount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Background runs (after adding an account etc.) never start: the tests run the sync themselves. */
    private val scope = CoroutineScope(SupervisorJob() + object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    })
    private val remotes = HashMap<String, FakeRemoteStore>()

    @After
    fun stop() = scope.cancel()

    private class Host(root: Path) : SyncHost {
        override val fs = FileSystem.SYSTEM
        override val documentsRoot = root / "documents"
        override val stateDir = root
        override val deviceId = DeviceId("dev")
        val documents = FileDocumentRepository(fs, documentsRoot, Dispatchers.IO)
        override val library = Library(FileLibraryRepository(fs, root / "library", Dispatchers.IO), documents, clock = System::currentTimeMillis)
        override val localChanges = MutableSharedFlow<Unit>()
        override suspend fun flushOpenSessions() = Unit
        override suspend fun applyRemote(doc: DocumentId, ops: List<DocumentOp>) = Unit
        override suspend fun beforeDocumentsDeleted(docs: Set<DocumentId>) = Unit
        override fun assetsArrived(docs: Set<DocumentId>) = Unit
    }

    private class MemoryStore(var entries: Map<String, String> = emptyMap()) : AccountStore {
        override fun load() = entries
        override fun save(entries: Map<String, String>) {
            this.entries = entries
        }
    }

    private fun nextcloud(folder: String) = NextcloudConfig(NextcloudAccount("https://cloud.example.org", "stefan", "pw"), folder)

    private fun controller(host: Host, store: AccountStore) =
        SyncController(host, store, scope, remoteFor = { remotes.getOrPut((it as NextcloudConfig).folder) { FakeRemoteStore() } })

    private fun bookFiles(folder: String) = remotes[folder]?.files?.keys.orEmpty().filter { it.startsWith("library/") }

    @Test
    fun accountsSyncOnlyTheirBooksAndRemovingOneKeepsItsBooks() = runBlocking<Unit> {
        val host = Host(tmp.root.toOkioPath())
        host.library.load("Lokal", "Seite")
        val store = MemoryStore()
        val sync = controller(host, store)
        val work = sync.addAccount("Arbeit", nextcloud("work"))!!
        val home = sync.addAccount("Privat", nextcloud("home"))!!
        assertThat(sync.addAccount("Nochmal", nextcloud("home"))).isNull()

        val job = host.library.createBook("Projekte")
        sync.assignBook(job.id, work.id)
        sync.runOnce()
        assertThat(bookFiles("work")).containsExactly("library/${job.id.value}.json")
        assertThat(bookFiles("home")).isEmpty()
        remotes["work"]!!.broken = null
        assertThat(bookFiles("home")).isEmpty()
        assertThat(sync.statuses.value[work.id]).isInstanceOf(SyncStatus.Synced::class.java)

        // One account failing does not stop the other.
        remotes["work"]!!.broken = RuntimeException("offline")
        sync.runOnce()
        assertThat(sync.statuses.value[work.id]).isInstanceOf(SyncStatus.Failed::class.java)
        assertThat(sync.statuses.value[home.id]).isInstanceOf(SyncStatus.Synced::class.java)

        // Accounts survive a restart.
        assertThat(controller(host, MemoryStore(store.entries)).accounts.value.map { it.name }).containsExactly("Arbeit", "Privat")

        sync.removeAccount(work.id)
        assertThat(sync.books.accountOf(job.id)).isNull()
        assertThat(host.library.tree.value[job.id]).isNotNull()
        assertThat(sync.accounts.value.map { it.id }).containsExactly(home.id)
    }

    @Test
    fun theLoginOfOlderVersionsBecomesAnAccountWithAllBooks() = runBlocking<Unit> {
        val host = Host(tmp.root.toOkioPath())
        host.library.load("Notizen", "Seite")
        val second = host.library.createBook("Zweites")
        val old = nextcloud("NibSync")
        val store = MemoryStore(SyncConfigCodec.toMap(old))
        val sync = controller(host, store)
        val account = sync.accounts.value.single()
        assertThat(account.id).isEqualTo(AccountId.LEGACY)
        assertThat(account.name).isEqualTo("Nextcloud")
        assertThat(account.config.stateKey).isEqualTo(old.stateKey)
        sync.runOnce()
        assertThat(sync.books.accountOf(second.id)).isEqualTo(AccountId.LEGACY)
        assertThat(bookFiles("NibSync")).containsAtLeast("library/${second.id.value}.json", "library/${host.library.tree.value.books.first { it.title == "Notizen" }.id.value}.json")
        // Books created afterwards stay local unless assigned.
        val later = host.library.createBook("Später")
        sync.runOnce()
        assertThat(sync.books.accountOf(later.id)).isNull()
    }

    @Test
    fun aFreshInstallHasOnlyLocalBooks() = runBlocking<Unit> {
        val host = Host(tmp.root.toOkioPath())
        host.library.load("Notizen", "Seite")
        val sync = controller(host, MemoryStore())
        sync.runOnce()
        val account = sync.addAccount("Privat", nextcloud("home"))!!
        sync.runOnce()
        assertThat(sync.books.map.value).isEmpty()
        assertThat(bookFiles("home")).isEmpty()
        assertThat(sync.accounts.value.single()).isEqualTo(account)
    }

    @Test
    fun codecKeepsSeveralAccounts() {
        val accounts = listOf(
            SyncAccount(AccountId.new(), "Privat", nextcloud("a")),
            SyncAccount(AccountId.new(), "Arbeit", OneDriveConfig("me@work.com", "rt")),
            SyncAccount(AccountId.new(), "Drive", GoogleDriveConfig("me@gmail.com", "")),
        )
        assertThat(AccountsCodec.fromMap(AccountsCodec.toMap(accounts))).isEqualTo(accounts)
        assertThat(AccountsCodec.fromMap(emptyMap())).isEmpty()
    }
}
