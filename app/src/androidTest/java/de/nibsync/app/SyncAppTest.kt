package de.nibsync.app

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import de.nibsync.sync.client.NextcloudConfig
import de.nibsync.sync.client.SyncConfig
import de.nibsync.sync.client.SyncStatus
import de.nibsync.document.DocumentOp
import de.nibsync.document.OpStamp
import de.nibsync.library.LibraryNode
import de.nibsync.library.NodeId
import de.nibsync.library.NodeKind
import de.nibsync.model.BrushFamily
import de.nibsync.model.BrushSpec
import de.nibsync.model.DeviceId
import de.nibsync.model.DocumentId
import de.nibsync.model.InputTool
import de.nibsync.model.Layer
import de.nibsync.model.PageFormat
import de.nibsync.model.Stroke
import de.nibsync.model.StrokeId
import de.nibsync.model.StrokePoint
import de.nibsync.storage.OpLogCodec
import de.nibsync.sync.webdav.NextcloudAccount
import de.nibsync.sync.webdav.WebDavRemoteStore
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Sync end to end on the device against a Nextcloud (by default the development container on
 * the developer machine, see app/build.gradle.kts). Uses a throw-away server folder and book;
 * the user's own sync settings are restored afterwards. Skipped if the server is unreachable.
 */
@RunWith(AndroidJUnit4::class)
class SyncAppTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<NibSyncApp>()
    private val serverUrl = InstrumentationRegistry.getArguments().getString("nextcloudUrl") ?: "http://192.168.4.100:8089"
    private val account = NextcloudAccount(serverUrl, "nibsync", "nibsync-test-pass")
    private val folder = "nibsync-app-test-" + UUID.randomUUID().toString().take(8)
    private val remote = WebDavRemoteStore(serverUrl, account.loginName, account.appPassword, folder)
    private var previousConfig: SyncConfig? = null
    private lateinit var book: LibraryNode
    private var docId: String = ""
    private val doc: DocumentId get() = DocumentId(docId)
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() = runBlocking {
        val reachable = runCatching { WebDavRemoteStore(serverUrl, account.loginName, account.appPassword, "").list("") != null }.getOrDefault(false)
        assumeTrue("Nextcloud at $serverUrl not reachable", reachable)
        previousConfig = app.sync.config.value
        previousStateFiles = stateFiles()
        // Switch to the test server first: the test book must never reach the account
        // configured on the device.
        app.sync.configure(NextcloudConfig(account, folder))
        app.container.ensureLibraryLoaded("Notizen", "Notizen")
        book = app.container.library.createBook("Sync-Test-" + UUID.randomUUID().toString().take(6))
        docId = app.container.library.createDocument(book.id, "Seite", PageFormat()).id.value
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_DOCUMENT_ID, doc.value))
        waitUntil("canvas") { activity { it.canvasView }?.viewport?.hasSize == true && activity { it.session } != null }
    }

    @After
    fun tearDown() = runBlocking {
        if (!::scenario.isInitialized) return@runBlocking
        scenario.close()
        // Remove the test book (and what other "devices" added) while the test server is
        // still configured, then go back to the previous account.
        app.sync.paused = true
        val lib = app.container.library
        val ids = lib.tree.value.descendantsOf(book.id).map { it.id } + book.id
        app.container.beforeDelete(ids.map { it.asDocumentId }.toSet())
        lib.delete(book.id)
        ids.forEach { java.io.File(app.container.libraryRoot.toString(), "${it.value}.json").delete() }
        runCatching { WebDavRemoteStore(serverUrl, account.loginName, account.appPassword, "").delete(folder) }
        (stateFiles() - previousStateFiles).forEach { it.delete() }
        app.sync.configure(previousConfig)
        app.sync.paused = false
    }

    private var previousStateFiles: Set<java.io.File> = emptySet()

    private fun stateFiles(): Set<java.io.File> =
        app.container.localRoot.toFile().listFiles { f -> f.name.startsWith("sync-state-") }.orEmpty().toSet()

    private fun <T> activity(get: (MainActivity) -> T): T {
        var value: T? = null
        scenario.onActivity { value = get(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun waitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) { condition() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    private fun strokeCount() = activity { it.session }?.state?.value?.strokeCount ?: -1

    private fun drawLine(y: Float) {
        val view = checkNotNull(activity { it.canvasView })
        val origin = IntArray(2).also { loc -> scenario.onActivity { view.getLocationOnScreen(loc) } }
        val down = SystemClock.uptimeMillis()
        for (i in 0..30) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 30 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coords = MotionEvent.PointerCoords().apply { x = origin[0] + 200f + i * 15; this.y = origin[1] + y; pressure = 0.3f; size = 0.1f }
            val e = MotionEvent.obtain(down, down + i * 8L, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN, 0)
            instrumentation.sendPointerSync(e)
            e.recycle()
        }
    }

    @Test
    fun strokesGoToNextcloudAndComeBackFromOtherDevices() = runBlocking {
        // 1. Write locally, sync: the stroke and the library entry are on the server.
        drawLine(300f)
        waitUntil("stroke") { strokeCount() == 1 }
        app.sync.runOnce()
        assertThat(app.sync.status.value).isInstanceOf(SyncStatus.Synced::class.java)
        val ownLog = "${app.container.deviceId.value}.oplog"
        assertThat(remote.list("documents/${doc.value}/ops")!![ownLog]).isNotNull()
        assertThat(remote.list("library")!!["${book.id.value}.json"]).isNotNull()

        // 2. Another device adds a stroke and a chapter on the server.
        val other = DeviceId("other-device")
        val stroke = Stroke(
            StrokeId("from-other-device"), Layer.INK, BrushSpec(BrushFamily.BALLPOINT, 0xffc62828.toInt(), 0.5f), InputTool.STYLUS,
            listOf(StrokePoint(20f, 60f, 0, 0.5f), StrokePoint(80f, 60f, 100, 0.5f)), 0, other,
        )
        val log = Buffer().write(OpLogCodec.header()).write(OpLogCodec.encodeRecord(DocumentOp.AddStrokes(OpStamp(1000, other), listOf(stroke))))
        remote.write("documents/${doc.value}/ops/other-device.oplog", log.readByteArray())
        val chapter = LibraryNode(NodeId("chapter-from-other-device"), NodeKind.FOLDER, book.id, "Vom anderen Gerät", 1)
        remote.write("library/${chapter.id.value}.json", app.container.library.encode(chapter))

        // 3. Sync: both show up live, without reopening anything.
        app.sync.runOnce()
        waitUntil("remote stroke in the open document") { strokeCount() == 2 }
        waitUntil("canvas shows it") { activity { it.canvasView }?.strokes?.any { it.id == stroke.id } == true }
        waitUntil("chapter in the library") { app.container.library.tree.value.childrenOf(book.id).any { it.title == "Vom anderen Gerät" } }

        // 4. Nothing changed: the next sync has nothing to do.
        app.sync.runOnce()
        app.sync.runOnce()
        assertThat(app.sync.status.value).isInstanceOf(SyncStatus.Synced::class.java)
    }

    @Test
    fun wrongPasswordIsReportedAsInvalidLogin() = runBlocking {
        app.sync.configure(NextcloudConfig(account.copy(appPassword = "wrong"), folder))
        app.sync.runOnce()
        val status = app.sync.status.value
        assertThat(status).isInstanceOf(SyncStatus.Failed::class.java)
        assertThat((status as SyncStatus.Failed).loginInvalid).isTrue()
    }
}
