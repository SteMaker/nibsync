package com.stemaker.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.model.Asset
import com.stemaker.nibsync.model.ImageDraft
import com.stemaker.nibsync.model.PageFormat
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TextBoxDraft
import com.stemaker.nibsync.sync.client.NextcloudConfig
import com.stemaker.nibsync.sync.client.AccountStore
import com.stemaker.nibsync.sync.client.SyncController
import com.stemaker.nibsync.sync.client.SyncStatus
import com.stemaker.nibsync.sync.webdav.NextcloudAccount
import com.stemaker.nibsync.sync.webdav.WebDavRemoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * Two PC installations (separate data folders) sync through the local Docker Nextcloud
 * (see docs/architecture.md); skipped when it is not running.
 */
class DesktopSyncIntegrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = System.getenv("NIBSYNC_TEST_NEXTCLOUD_URL") ?: "http://localhost:8089"
    private val account = NextcloudAccount(server, "nibsync", "nibsync-test-pass")
    private val folder = "nibsync-pc-test-" + UUID.randomUUID().toString().take(8)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val privateFolder = "$folder-private"

    private class MemoryStore(var entries: Map<String, String> = emptyMap()) : AccountStore {
        override fun load() = entries
        override fun save(entries: Map<String, String>) {
            this.entries = entries
        }
    }

    /** A PC whose first book syncs with the (work) account in [folder], like after setting it up. */
    private suspend fun pc(name: String, firstBook: String): Pair<DesktopContainer, SyncController> {
        val container = DesktopContainer(tmp.newFolder(name).toOkioPath(), appScope = scope)
        container.library.load(firstBook, "Seite 1")
        val sync = SyncController(container, MemoryStore(), scope)
        val work = sync.addAccount("Arbeit", NextcloudConfig(account, folder))!!
        sync.assignBook(container.library.tree.value.books.single().id, work.id)
        return container to sync
    }

    private fun SyncController.statusOf(name: String) = statuses.value[accounts.value.first { it.name == name }.id]

    @Before
    fun serverReachable() {
        val reachable = runCatching { runBlocking { WebDavRemoteStore(server, account.loginName, account.appPassword, "").list("") } != null }.getOrDefault(false)
        assumeTrue("Nextcloud at $server not reachable", reachable)
    }

    @After
    fun cleanUp() {
        runCatching { runBlocking { WebDavRemoteStore(server, account.loginName, account.appPassword, "").delete(folder) } }
        runCatching { runBlocking { WebDavRemoteStore(server, account.loginName, account.appPassword, "").delete(privateFolder) } }
        scope.cancel()
    }

    @Test
    fun notesWrittenOnOnePcAppearOnTheOther() = runBlocking {
        val (a, syncA) = pc("a", "Notizen")
        val (b, syncB) = pc("b", "Notes")
        val book = a.library.tree.value.books.single()
        val doc = a.library.createDocument(book.id, "Vom PC", PageFormat()).id.asDocumentId
        val picture = a.repository.putAsset(doc, ByteArray(3000) { it.toByte() })
        a.openSession(doc).replaceElements(
            emptySet(),
            listOf(
                TextBoxDraft(20f, 20f, 100f, 8f, RichText.plain("Hallo vom PC")),
                ImageDraft(20f, 40f, 50f, 30f, Asset(picture, Asset.PNG)),
            ),
        )
        syncA.runOnce()
        assertThat(syncA.statusOf("Arbeit")).isInstanceOf(SyncStatus.Synced::class.java)

        syncB.runOnce()
        assertThat(syncB.statusOf("Arbeit")).isInstanceOf(SyncStatus.Synced::class.java)
        val titles = b.library.tree.value.documents.map { it.title }
        assertThat(titles).containsAtLeast("Vom PC", "Seite 1")
        assertThat(b.library.tree.value.books.map { it.title }).containsExactly("Notizen", "Notes")
        val state = b.openSession(doc).state.value
        assertThat(state.textBoxes.single().text.plainText).isEqualTo("Hallo vom PC")
        assertThat(b.repository.hasAsset(doc, picture)).isTrue()

        // An edit on b reaches the session that is open on a, without reopening it.
        val box = state.textBoxes.single()
        b.openSession(doc).replaceElements(listOf(box.id), listOf(TextBoxDraft(20f, 20f, 100f, 8f, RichText.plain("Antwort von b"))))
        syncB.runOnce()
        syncA.runOnce()
        assertThat(a.openSession(doc).state.value.textBoxes.single().text.plainText).isEqualTo("Antwort von b")
        a.closeAll()
        b.closeAll()
    }

    @Test
    fun privateAndWorkBooksStayInTheirAccounts() = runBlocking {
        val (a, syncA) = pc("a", "Arbeit A")
        val privat = syncA.addAccount("Privat", NextcloudConfig(account, privateFolder))!!
        val diary = a.library.createBook("Tagebuch")
        syncA.assignBook(diary.id, privat.id)
        val local = a.library.createBook("Nur hier")
        syncA.runOnce()
        assertThat(syncA.statusOf("Privat")).isInstanceOf(SyncStatus.Synced::class.java)

        // The colleague's PC knows only the work account: the private and local books never arrive.
        val (b, syncB) = pc("b", "Arbeit B")
        syncB.runOnce()
        val books = b.library.tree.value.books.map { it.title }
        assertThat(books).containsExactly("Arbeit A", "Arbeit B")
        assertThat(WebDavRemoteStore(server, account.loginName, account.appPassword, privateFolder).list("library")!!.entries.map { it.name })
            .contains("${diary.id.value}.json")
        assertThat(WebDavRemoteStore(server, account.loginName, account.appPassword, folder).list("library")!!.entries.map { it.name })
            .containsNoneOf("${diary.id.value}.json", "${local.id.value}.json")

        // Removing the private account keeps its book on the PC.
        syncA.removeAccount(privat.id)
        assertThat(a.library.tree.value[diary.id]).isNotNull()
        assertThat(syncA.books.accountOf(diary.id)).isNull()
        a.closeAll()
        b.closeAll()
    }

    @Test
    fun wrongPasswordIsReportedAsInvalidLogin() = runBlocking {
        val container = DesktopContainer(tmp.newFolder("c").toOkioPath(), appScope = scope)
        container.library.load("Notizen", "Seite 1")
        val sync = SyncController(container, MemoryStore(), scope)
        sync.addAccount("Arbeit", NextcloudConfig(account.copy(appPassword = "wrong"), folder))
        sync.runOnce()
        val status = sync.statusOf("Arbeit")
        assertThat(status).isInstanceOf(SyncStatus.Failed::class.java)
        assertThat((status as SyncStatus.Failed).loginInvalid).isTrue()
    }
}
