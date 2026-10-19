package com.stemaker.nibsync.sync

import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.library.FileLibraryRepository
import com.stemaker.nibsync.library.Library
import com.stemaker.nibsync.library.LibraryNode
import com.stemaker.nibsync.library.NodeId
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.IdGenerator
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.PageFormat
import com.stemaker.nibsync.model.PaperSize
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokePoint
import com.stemaker.nibsync.storage.FileDocumentRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {
    private val fs = FakeFileSystem()
    private val remote = FakeRemoteStore()
    private var now = 1_000L

    /** One installation of the app: its own files, library and sync engine. */
    private inner class Device(val name: String, val scope: TestScope) {
        val id = DeviceId("device-$name")
        val io = StandardTestDispatcher(scope.testScheduler)
        val root = "/$name".toPath()
        val documents = FileDocumentRepository(fs, root / "documents", io)
        val library = Library(FileLibraryRepository(fs, root / "library", io), documents, IdGenerator(Random(name.hashCode())), clock = { now++ })
        val closedForDeletion = mutableListOf<Set<DocumentId>>()
        val engine = SyncEngine(fs, root / "documents", root / "sync-state.json", library, id, remote, io, beforeDocumentsDeleted = { closedForDeletion += it })
        private val sessions = HashMap<DocumentId, DocumentSession>()

        suspend fun session(doc: DocumentId): DocumentSession = sessions.getOrPut(doc) {
            DocumentSession(documents.load(doc), id, documents.journal(doc, id), scope.backgroundScope, clock = { now++ })
        }

        suspend fun draw(doc: DocumentId, y: Float) {
            val s = session(doc)
            s.addStroke(StrokeDraft(BrushSpec(BrushFamily.BALLPOINT, 0xff000000.toInt(), 0.5f), InputTool.STYLUS,
                listOf(StrokePoint(10f, y, 0, 0.5f), StrokePoint(20f, y, 10, 0.5f))))
            s.flush()
        }

        suspend fun strokesOn(doc: DocumentId) = documents.load(doc).strokes.map { it.points.first().y }.sorted()
    }

    private suspend fun TestScope.device(name: String, firstBook: String = "Notizen") =
        Device(name, this).also { it.library.load(firstBook, "Seite 1") }

    private fun Device.firstDoc() = library.tree.value.documents.first().id.asDocumentId

    @Test
    fun firstSyncUploadsLibraryAndDocuments() = runTest {
        val a = device("a")
        a.draw(a.firstDoc(), 5f)
        val report = a.engine.sync()
        assertFalse(report.upToDate)
        val doc = a.firstDoc().value
        assertTrue(remote.files.keys.any { it.startsWith("library/") })
        assertTrue("documents/$doc/meta.json" in remote.files)
        assertTrue("documents/$doc/ops/device-a.oplog" in remote.files)
    }

    @Test
    fun picturesTravelWithTheirFiles() = runTest {
        val a = device("a")
        val doc = a.firstDoc()
        a.engine.sync()
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val sha = a.documents.putAsset(doc, bytes)
        val s = a.session(doc)
        s.replaceElements(emptySet(), listOf(com.stemaker.nibsync.model.ImageDraft(10f, 10f, 50f, 30f, com.stemaker.nibsync.model.Asset(sha, com.stemaker.nibsync.model.Asset.PNG))))
        s.flush()
        val up = a.engine.sync()
        assertTrue("documents/${doc.value}/assets/$sha" in remote.files)
        assertTrue(up.uploads >= 2)
        // Nothing new: the asset is not uploaded again (the first run notices its own upload).
        val writes = remote.writes
        a.engine.sync()
        assertTrue(a.engine.sync().upToDate)
        assertEquals(writes, remote.writes)

        val b = device("b")
        val report = b.engine.sync()
        assertTrue(doc in report.receivedAssets)
        assertTrue(b.documents.hasAsset(doc, sha))
        assertEquals(bytes.toList(), fs.read(b.documents.assetPath(doc, sha)) { readByteArray() }.toList())
        assertEquals(sha, b.documents.load(doc).files.single().asset.sha256)
        // b does not send it back.
        val writesBefore = remote.writes
        b.engine.sync()
        assertEquals(writesBefore, remote.writes)
    }

    @Test
    fun secondDeviceGetsEverything() = runTest {
        val a = device("a")
        val doc = a.firstDoc()
        a.draw(doc, 5f)
        a.draw(doc, 6f)
        a.engine.sync()

        val b = device("b", firstBook = "B's book")
        b.engine.sync()
        // b now knows a's book and document, with the strokes.
        assertTrue(b.library.tree.value.books.any { it.title == "Notizen" })
        assertEquals(listOf(5f, 6f), b.strokesOn(doc))
        // ... and a gets b's own first book.
        a.engine.sync()
        assertTrue(a.library.tree.value.books.any { it.title == "B's book" })
    }

    @Test
    fun todosAndTheirDoneStateSync() = runTest {
        val a = device("a")
        val doc = a.firstDoc()
        val sa = a.session(doc)
        val item = sa.replaceElements(emptySet(), listOf(com.stemaker.nibsync.model.TodoDraft(10f, 10f, 80f, 14f, com.stemaker.nibsync.model.TodoContent.Text(com.stemaker.nibsync.model.RichText.plain("Milch"))))).single() as com.stemaker.nibsync.model.TodoItem
        sa.flush()
        a.engine.sync()
        val b = device("b")
        b.engine.sync()
        assertEquals(listOf(item.taskId), b.documents.load(doc).todos.map { it.taskId })
        // b ticks it off, a moves it at the same time: both survive.
        val sb = b.session(doc)
        sb.setTodoDone(item.taskId, true)
        sb.flush()
        sa.replaceElements(listOf(item.id), listOf(com.stemaker.nibsync.model.TodoDraft(10f, 40f, 80f, 14f, item.content, item.taskId)))
        sa.flush()
        b.engine.sync()
        a.engine.sync()
        b.engine.sync()
        for (d in listOf(a, b)) {
            val state = d.documents.load(doc)
            assertEquals(listOf(40f), state.todos.map { it.y })
            assertTrue(state.isDone(item.taskId))
        }
    }

    @Test
    fun concurrentEditsOnTheSameDocumentMerge() = runTest {
        val a = device("a")
        val doc = a.firstDoc()
        a.engine.sync()
        val b = device("b")
        b.engine.sync()

        a.draw(doc, 1f)
        b.draw(doc, 2f)
        b.draw(doc, 3f)
        a.engine.sync()
        b.engine.sync()
        a.engine.sync()

        assertEquals(listOf(1f, 2f, 3f), a.strokesOn(doc))
        assertEquals(listOf(1f, 2f, 3f), b.strokesOn(doc))
        // Same z-order everywhere.
        assertEquals(a.documents.load(doc).strokes.map { it.id }, b.documents.load(doc).strokes.map { it.id })
    }

    @Test
    fun receivedOpsAreReportedForOpenDocuments() = runTest {
        val a = device("a")
        val doc = a.firstDoc()
        a.engine.sync()
        val b = device("b")
        b.engine.sync()
        val open = b.session(doc) // b has the document open

        a.draw(doc, 7f)
        a.engine.sync()
        val report = b.engine.sync()
        val ops = report.receivedOps.getValue(doc)
        assertEquals(1, ops.size)
        open.applyRemote(ops)
        assertEquals(listOf(7f), open.state.value.strokes.map { it.points.first().y })
    }

    @Test
    fun onlyNewBytesAreDownloaded() = runTest {
        val a = device("a")
        val doc = a.firstDoc()
        a.draw(doc, 1f)
        a.engine.sync()
        val b = device("b")
        b.engine.sync()
        a.draw(doc, 2f)
        a.engine.sync()
        val fullSize = remote.files.getValue("documents/${doc.value}/ops/device-a.oplog").size
        val before = b.documents.load(doc).strokeCount
        val report = b.engine.sync()
        assertEquals(1, report.receivedOps.getValue(doc).size)
        assertEquals(before + 1, b.documents.load(doc).strokeCount)
        assertTrue(fullSize > 0)
    }

    @Test
    fun unchangedStateCostsOneRequest() = runTest {
        val a = device("a")
        a.draw(a.firstDoc(), 1f)
        a.engine.sync()
        a.engine.sync() // settles etags changed by our own uploads
        remote.requests = 0
        val report = a.engine.sync()
        assertTrue(report.upToDate)
        assertEquals(1, remote.requests)
    }

    @Test
    fun syncIsStableAfterSettling() = runTest {
        val a = device("a")
        val b = device("b")
        repeat(3) { a.engine.sync(); b.engine.sync() }
        remote.requests = 0
        assertTrue(a.engine.sync().upToDate)
        assertTrue(b.engine.sync().upToDate)
        assertEquals(2, remote.requests)
    }

    @Test
    fun renameAndNewChaptersTravel() = runTest {
        val a = device("a")
        a.engine.sync()
        val b = device("b")
        b.engine.sync()
        val book = a.library.tree.value.books.first { it.title == "Notizen" }
        a.library.rename(book.id, "Tagebuch")
        val chapter = a.library.createFolder(book.id, "Oktober")
        val doc = a.library.createDocument(chapter.id, "Montag", PageFormat(PaperSize.A5))
        a.draw(doc.id.asDocumentId, 4f)
        a.engine.sync()
        val report = b.engine.sync()
        assertTrue(report.libraryChanged)
        assertEquals("Tagebuch", b.library.tree.value[book.id]!!.title)
        assertEquals(listOf("Tagebuch", "Oktober", "Montag"), b.library.tree.value.pathTo(doc.id).map { it.title })
        assertEquals(PaperSize.A5, b.documents.load(doc.id.asDocumentId).meta.format.paper)
        assertEquals(listOf(4f), b.strokesOn(doc.id.asDocumentId))
    }

    @Test
    fun deletionReachesOtherDevicesAndTheServer() = runTest {
        val a = device("a")
        val doc = a.firstDoc()
        a.draw(doc, 1f)
        a.engine.sync()
        val b = device("b")
        b.engine.sync()
        assertTrue(b.documents.exists(doc))

        a.library.delete(NodeId(doc.value))
        a.engine.sync()
        assertFalse(remote.dirs.any { it == "documents/${doc.value}" })
        b.engine.sync()
        assertFalse(b.documents.exists(doc))
        assertEquals(listOf(setOf(doc)), b.closedForDeletion)
        assertNull(b.library.tree.value[NodeId(doc.value)])
    }

    @Test
    fun deletionWinsOverAConcurrentRename() = runTest {
        val a = device("a")
        a.engine.sync()
        val b = device("b")
        b.engine.sync()
        val book = a.library.tree.value.books.first { it.title == "Notizen" }
        a.library.delete(book.id)
        b.library.rename(book.id, "Später umbenannt")
        a.engine.sync(); b.engine.sync(); a.engine.sync()
        assertNull(a.library.tree.value[book.id])
        assertNull(b.library.tree.value[book.id])
    }

    @Test
    fun documentsOutsideTheLibraryAreNotSynced() = runTest {
        val a = device("a")
        val stray = DocumentId("test-123")
        a.documents.create(com.stemaker.nibsync.model.DocumentMeta(stray, "Test", createdAtEpochMs = 0))
        a.draw(stray, 1f)
        a.engine.sync()
        assertFalse(remote.files.keys.any { "test-123" in it })
    }

    @Test
    fun aFailedSyncCanBeRepeated() = runTest {
        val a = device("a")
        val doc = a.firstDoc()
        a.draw(doc, 1f)
        a.engine.sync()
        val b = device("b")
        b.engine.sync()
        a.draw(doc, 2f)
        remote.failNext = RuntimeException("network down")
        runCatching { a.engine.sync() }
        a.engine.sync()
        b.engine.sync()
        assertEquals(listOf(1f, 2f), b.strokesOn(doc))
    }

    @Test
    fun truncatedRemoteLogIsTakenOnlyUpToCompleteRecords() = runTest {
        val a = device("a")
        val doc = a.firstDoc()
        a.draw(doc, 1f)
        a.draw(doc, 2f)
        a.engine.sync()
        // Simulate a log cut off mid-record on the server.
        val path = "documents/${doc.value}/ops/device-a.oplog"
        remote.files[path] = remote.files.getValue(path).let { it.copyOf(it.size - 3) }
        val b = device("b")
        b.engine.sync()
        assertEquals(listOf(1f), b.strokesOn(doc))
        // When the full log arrives, the rest follows.
        a.draw(doc, 3f)
        a.engine.sync()
        b.engine.sync()
        assertEquals(listOf(1f, 2f, 3f), b.strokesOn(doc))
    }
}
