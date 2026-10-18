package de.nibsync.sync.webdav

import com.google.common.truth.Truth.assertThat
import de.nibsync.document.DocumentSession
import de.nibsync.library.FileLibraryRepository
import de.nibsync.library.Library
import de.nibsync.model.BrushFamily
import de.nibsync.model.BrushSpec
import de.nibsync.model.DeviceId
import de.nibsync.model.InputTool
import de.nibsync.model.StrokeDraft
import de.nibsync.model.StrokePoint
import de.nibsync.storage.FileDocumentRepository
import de.nibsync.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * Two devices syncing through a real Nextcloud. Runs only when configured, e.g. against the
 * development container:
 *
 * ```
 * NIBSYNC_NEXTCLOUD_URL=http://localhost:8089 NIBSYNC_NEXTCLOUD_USER=nibsync \
 * NIBSYNC_NEXTCLOUD_PASSWORD=... ./gradlew :sync:webdav:jvmTest
 * ```
 */
class NextcloudIntegrationTest {
    private val url = System.getenv("NIBSYNC_NEXTCLOUD_URL")
    private val user = System.getenv("NIBSYNC_NEXTCLOUD_USER")
    private val password = System.getenv("NIBSYNC_NEXTCLOUD_PASSWORD")

    @Test
    fun twoDevicesSyncThroughNextcloud() = runBlocking {
        assumeTrue("Nextcloud not configured", url != null && user != null && password != null)
        val folder = "nibsync-it-${UUID.randomUUID().toString().take(8)}"
        val fs = FakeFileSystem()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var now = 1000L

        class Device(name: String) {
            val id = DeviceId("device-$name")
            val root = "/$name".toPath()
            val docs = FileDocumentRepository(fs, root / "documents", Dispatchers.IO)
            val library = Library(FileLibraryRepository(fs, root / "library", Dispatchers.IO), docs, clock = { now++ })
            val engine = SyncEngine(fs, root / "documents", root / "state.json", library, id, WebDavRemoteStore(url!!, user!!, password!!, folder), Dispatchers.IO)
        }

        val a = Device("a")
        val b = Device("b")
        try {
            a.library.load("Integration", "Seite")
            b.library.load("Von B", "Seite B")
            val doc = a.library.tree.value.documents.single().id.asDocumentId
            val session = DocumentSession(a.docs.load(doc), a.id, a.docs.journal(doc, a.id), scope, clock = { now++ })
            session.addStroke(StrokeDraft(BrushSpec(BrushFamily.BALLPOINT, 0xff000000.toInt(), 0.5f), InputTool.STYLUS,
                listOf(StrokePoint(1f, 2f, 0, 0.3f), StrokePoint(5f, 2f, 10, 0.4f))))
            session.flush()

            a.engine.sync()
            val report = b.engine.sync()
            assertThat(report.upToDate).isFalse()
            assertThat(b.docs.load(doc).strokeCount).isEqualTo(1)
            a.engine.sync()
            assertThat(a.library.tree.value.books.map { it.title }).containsExactly("Integration", "Von B")

            // Settled: a further sync is a single cheap request with nothing to do.
            a.engine.sync()
            assertThat(a.engine.sync().upToDate).isTrue()
        } finally {
            WebDavRemoteStore(url!!, user!!, password!!, "").delete(folder)
        }
    }
}
