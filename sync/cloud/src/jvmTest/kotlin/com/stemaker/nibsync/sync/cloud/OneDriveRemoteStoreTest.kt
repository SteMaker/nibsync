package com.stemaker.nibsync.sync.cloud

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.library.FileLibraryRepository
import com.stemaker.nibsync.library.Library
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.IdGenerator
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokePoint
import com.stemaker.nibsync.storage.FileDocumentRepository
import com.stemaker.nibsync.sync.SyncEngine
import com.stemaker.nibsync.sync.webdav.AuthenticationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertFailsWith

class OneDriveRemoteStoreTest {
    private val graph = FakeGraph()

    private class Tokens(var current: String, private val fresh: String = "good") : AccessTokens {
        var refreshes = 0
        override suspend fun current() = current
        override suspend fun refresh(): String {
            refreshes++
            current = fresh
            return fresh
        }
    }

    @Before
    fun start() = graph.start()

    @After
    fun stop() = graph.shutdown()

    private fun store(tokens: AccessTokens = Tokens("good")) = OneDriveRemoteStore(tokens, baseUrl = graph.base)

    @Test
    fun foldersFilesAndRangedReads() = runBlocking<Unit> {
        val s = store()
        assertThat(s.list("")!!.entries).isEmpty()
        s.makeDirectory("documents")
        s.makeDirectory("documents") // again: nothing happens
        s.makeDirectory("documents/d1")
        s.write("documents/d1/log", "hello world".toByteArray())
        assertThat(String(s.read("documents/d1/log"))).isEqualTo("hello world")
        assertThat(String(s.read("documents/d1/log", 6))).isEqualTo("world")
        assertThat(s.read("documents/d1/log", 11)).isEmpty()
        val before = s.list("")!!.etag
        s.write("documents/d1/log", "hello world, again".toByteArray())
        assertThat(s.list("")!!.etag).isNotEqualTo(before)
        assertThat(s.list("documents/d1")!!["log"]!!.size).isEqualTo(18)
        s.delete("documents/d1")
        assertThat(s.list("documents")!!.entries).isEmpty()
        assertThat(s.list("nothing")).isNull()
    }

    @Test
    fun changesFromAnotherDeviceArriveThroughDelta() = runBlocking<Unit> {
        val a = store()
        val b = store()
        b.list("")
        a.makeDirectory("library")
        a.write("library/n1.json", "{}".toByteArray())
        assertThat(b.list("")!!.entries.map { it.name }).containsExactly("library")
        assertThat(String(b.read("library/n1.json"))).isEqualTo("{}")
        a.delete("library/n1.json")
        assertThat(b.list("")!!.entries.map { it.name }).containsExactly("library")
        assertThat(b.list("library")!!.entries).isEmpty()
        // Unchanged: the same etag, and only one request (the delta query).
        val etag = b.list("")!!.etag
        val requests = graph.requests
        assertThat(b.list("")!!.etag).isEqualTo(etag)
        assertThat(graph.requests - requests).isEqualTo(1)
    }

    @Test
    fun withoutDeltaTheFolderIsScanned() = runBlocking<Unit> {
        graph.deltaSupported = false
        val a = store()
        a.makeDirectory("library")
        a.write("library/n1.json", "{}".toByteArray())
        val b = store()
        assertThat(b.list("library")!!.entries.map { it.name }).containsExactly("n1.json")
    }

    @Test
    fun anExpiredTokenIsRefreshedOnce() = runBlocking<Unit> {
        val tokens = Tokens("expired")
        assertThat(store(tokens).list("")).isNotNull()
        assertThat(tokens.refreshes).isEqualTo(1)
        // A token that never works is a login problem.
        assertFailsWith<AuthenticationException> { store(Tokens("expired", fresh = "still-bad")).list("") }
    }

    @Test
    fun twoDevicesSyncNotesThroughOneDrive() = runBlocking<Unit> {
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
        // Nothing new on the second run of b... and a sees b's book.
        syncA.sync()
        assertThat(libA.tree.value.books.map { it.title }).containsAtLeast("Notizen", "Notes")
    }
}
