package de.nibsync.sync.cloud

import com.google.common.truth.Truth.assertThat
import de.nibsync.document.DocumentSession
import de.nibsync.library.FileLibraryRepository
import de.nibsync.library.Library
import de.nibsync.model.BrushFamily
import de.nibsync.model.BrushSpec
import de.nibsync.model.DeviceId
import de.nibsync.model.IdGenerator
import de.nibsync.model.InputTool
import de.nibsync.model.StrokeDraft
import de.nibsync.model.StrokePoint
import de.nibsync.storage.FileDocumentRepository
import de.nibsync.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class GoogleDriveRemoteStoreTest {
    private val drive = FakeDrive()

    private val tokens = object : AccessTokens {
        override suspend fun current() = "good"
        override suspend fun refresh() = "good"
    }

    @Before
    fun start() = drive.start()

    @After
    fun stop() = drive.shutdown()

    private fun store() = GoogleDriveRemoteStore(tokens, apiUrl = drive.api, uploadUrl = drive.upload)

    @Test
    fun theSyncFolderIsMadeOnceAndFilesWork() = runBlocking<Unit> {
        val a = store()
        assertThat(a.list("")!!.entries).isEmpty()
        val b = store()
        b.list("")
        // Both use the same "NibSync" folder in My Drive.
        assertThat(drive.files.values.count { it.parent == "root" }).isEqualTo(1)
        a.makeDirectory("documents")
        a.makeDirectory("documents/d1")
        a.write("documents/d1/log", "hello world".toByteArray())
        a.write("documents/d1/log", "hello world, again".toByteArray())
        assertThat(drive.files.values.count { it.name == "log" }).isEqualTo(1)
        // Like the sync engine: listing the sync folder brings in the other device's changes.
        b.list("")
        assertThat(String(b.read("documents/d1/log", 6))).isEqualTo("world, again")
        assertThat(b.list("documents/d1")!!["log"]!!.size).isEqualTo(18)
        a.delete("documents/d1")
        b.list("")
        assertThat(b.list("documents")!!.entries).isEmpty()
        val etag = b.list("")!!.etag
        assertThat(b.list("")!!.etag).isEqualTo(etag)
    }

    @Test
    fun sameNamedFoldersFromTwoDevicesAreMerged() = runBlocking<Unit> {
        val a = store()
        a.makeDirectory("library")
        val root = drive.files.values.single { it.parent == "root" }
        // Another device made "library" at the same time and put a file into its own copy.
        val other = drive.create("library", root.id, isFolder = true)
        drive.create("n2.json", other.id, isFolder = false, bytes = "{}".toByteArray())
        a.write("library/n1.json", "{}".toByteArray())
        a.list("")
        assertThat(a.list("library")!!.entries.map { it.name }).containsExactly("n1.json", "n2.json")
    }

    @Test
    fun aFolderDeletedElsewhereDisappears() = runBlocking<Unit> {
        val a = store()
        a.makeDirectory("documents")
        a.makeDirectory("documents/d1")
        a.write("documents/d1/x", byteArrayOf(1))
        drive.remove(drive.files.values.single { it.name == "d1" }.id)
        a.list("")
        assertThat(a.list("documents")!!.entries).isEmpty()
    }

    @Test
    fun twoDevicesSyncNotesThroughGoogleDrive() = runBlocking<Unit> {
        val fs = FakeFileSystem()
        var now = 1_000L
        fun device(name: String): Triple<Library, FileDocumentRepository, SyncEngine> {
            val root = "/$name".toPath()
            val docs = FileDocumentRepository(fs, root / "documents", Dispatchers.Unconfined)
            val lib = Library(FileLibraryRepository(fs, root / "library", Dispatchers.Unconfined), docs, IdGenerator(Random(name.hashCode())), clock = { now++ })
            return Triple(lib, docs, SyncEngine(fs, root / "documents", root / "state.json", lib, DeviceId("dev-$name"), store(), Dispatchers.Unconfined))
        }
        val (libA, docsA, syncA) = device("a")
        val (libB, docsB, syncB) = device("b")
        libA.load("Notizen", "Seite 1")
        libB.load("Notes", "Page 1")
        val doc = libA.tree.value.documents.single().id.asDocumentId
        val session = DocumentSession(docsA.load(doc), DeviceId("dev-a"), docsA.journal(doc, DeviceId("dev-a")), this, clock = { now++ })
        session.addStroke(StrokeDraft(BrushSpec(BrushFamily.BALLPOINT, 0xff000000.toInt(), 0.5f), InputTool.STYLUS, listOf(StrokePoint(1f, 2f, 0), StrokePoint(3f, 4f, 10))))
        session.flush()
        session.close()
        syncA.sync()
        syncB.sync()
        assertThat(libB.tree.value.documents.map { it.title }).containsAtLeast("Seite 1", "Page 1")
        assertThat(docsB.load(doc).strokeCount).isEqualTo(1)
        syncA.sync()
        assertThat(libA.tree.value.books.map { it.title }).containsAtLeast("Notizen", "Notes")
    }
}
