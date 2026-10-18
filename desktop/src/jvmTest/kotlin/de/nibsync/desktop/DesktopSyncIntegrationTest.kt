package de.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import de.nibsync.model.Asset
import de.nibsync.model.ImageDraft
import de.nibsync.model.PageFormat
import de.nibsync.model.RichText
import de.nibsync.model.TextBoxDraft
import de.nibsync.sync.client.SyncConfig
import de.nibsync.sync.client.SyncConfigStore
import de.nibsync.sync.client.SyncController
import de.nibsync.sync.client.SyncStatus
import de.nibsync.sync.webdav.NextcloudAccount
import de.nibsync.sync.webdav.WebDavRemoteStore
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

    private class MemoryStore(var config: SyncConfig?) : SyncConfigStore {
        override fun load() = config
        override fun save(config: SyncConfig?) {
            this.config = config
        }
    }

    private fun pc(name: String): Pair<DesktopContainer, SyncController> {
        val container = DesktopContainer(tmp.newFolder(name).toOkioPath(), appScope = scope)
        return container to SyncController(container, MemoryStore(SyncConfig(account, folder)), scope)
    }

    @Before
    fun serverReachable() {
        val reachable = runCatching { runBlocking { WebDavRemoteStore(server, account.loginName, account.appPassword, "").list("") } != null }.getOrDefault(false)
        assumeTrue("Nextcloud at $server not reachable", reachable)
    }

    @After
    fun cleanUp() {
        runCatching { runBlocking { WebDavRemoteStore(server, account.loginName, account.appPassword, "").delete(folder) } }
        scope.cancel()
    }

    @Test
    fun notesWrittenOnOnePcAppearOnTheOther() = runBlocking {
        val (a, syncA) = pc("a")
        val (b, syncB) = pc("b")
        a.library.load("Notizen", "Seite 1")
        b.library.load("Notes", "Page 1")
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
        assertThat(syncA.status.value).isInstanceOf(SyncStatus.Synced::class.java)

        syncB.runOnce()
        assertThat(syncB.status.value).isInstanceOf(SyncStatus.Synced::class.java)
        val titles = b.library.tree.value.documents.map { it.title }
        assertThat(titles).containsAtLeast("Vom PC", "Seite 1", "Page 1")
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
    fun wrongPasswordIsReportedAsInvalidLogin() = runBlocking {
        val container = DesktopContainer(tmp.newFolder("c").toOkioPath(), appScope = scope)
        container.library.load("Notizen", "Seite 1")
        val sync = SyncController(container, MemoryStore(SyncConfig(account.copy(appPassword = "wrong"), folder)), scope)
        sync.runOnce()
        val status = sync.status.value
        assertThat(status).isInstanceOf(SyncStatus.Failed::class.java)
        assertThat((status as SyncStatus.Failed).loginInvalid).isTrue()
    }
}
