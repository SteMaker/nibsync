package com.stemaker.nibsync.sync

import com.stemaker.nibsync.library.FileLibraryRepository
import com.stemaker.nibsync.library.Library
import com.stemaker.nibsync.library.NodeId
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.IdGenerator
import com.stemaker.nibsync.model.PageFormat
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

/** Several accounts on one device: each book syncs only with its own remote. */
class SyncScopeTest {
    private val fs = FakeFileSystem()
    private val work = FakeRemoteStore()
    private val home = FakeRemoteStore()
    private var now = 1_000L

    /** Book → account, as the app keeps it; unassigned books stay on the device. */
    private class Assignments : HashMap<NodeId, String>() {
        fun scope(account: String) = object : BookScope {
            override fun owns(book: NodeId) = get(book) == account
            override fun isAssigned(book: NodeId) = containsKey(book)
            override suspend fun adopt(books: Set<NodeId>) = books.forEach { put(it, account) }
        }
    }

    private inner class Device(name: String, scope: TestScope) {
        val io = StandardTestDispatcher(scope.testScheduler)
        val root = "/$name".toPath()
        val documents = FileDocumentRepository(fs, root / "documents", io)
        val library = Library(FileLibraryRepository(fs, root / "library", io), documents, IdGenerator(Random(name.hashCode())), clock = { now++ })
        val books = Assignments()
        private val id = DeviceId("device-$name")
        private fun engine(account: String, remote: FakeRemoteStore) =
            SyncEngine(fs, root / "documents", root / "state-$account.json", library, id, remote, io, scope = books.scope(account))
        val workEngine = engine("work", work)
        val homeEngine = engine("home", home)

        suspend fun syncAll() {
            workEngine.sync()
            homeEngine.sync()
        }

        fun bookNamed(title: String) = library.tree.value.books.firstOrNull { it.title == title }
    }

    private fun bookFiles(remote: FakeRemoteStore, book: NodeId) = remote.files.keys.filter { it == "library/${book.value}.json" }

    @Test
    fun eachBookGoesOnlyToItsAccountAndLocalBooksStayHome() = runTest {
        val a = Device("a", this)
        a.library.load("Lokal", "Seite")
        val job = a.library.createBook("Arbeit")
        val jobDoc = a.library.createDocument(job.id, "Protokoll", PageFormat())
        val private = a.library.createBook("Privat")
        a.books[job.id] = "work"
        a.books[private.id] = "home"
        a.syncAll()

        assertEquals(1, bookFiles(work, job.id).size)
        assertTrue(bookFiles(home, job.id).isEmpty())
        assertTrue("documents/${jobDoc.id.value}/meta.json" in work.files)
        assertFalse(home.files.keys.any { jobDoc.id.value in it })
        assertEquals(1, bookFiles(home, private.id).size)
        val local = a.bookNamed("Lokal")!!
        assertTrue(bookFiles(work, local.id).isEmpty() && bookFiles(home, local.id).isEmpty())
        assertFalse(work.files.keys.any { it.startsWith("library/") && it.removePrefix("library/").removeSuffix(".json") in a.library.tree.value.descendantsOf(local.id).map { n -> n.id.value } })
    }

    @Test
    fun booksFromAnAccountArriveAndBelongToIt() = runTest {
        val a = Device("a", this)
        a.library.load("Lokal A", "Seite")
        val job = a.library.createBook("Arbeit")
        a.library.createDocument(job.id, "Protokoll", PageFormat())
        a.books[job.id] = "work"
        a.syncAll()

        val b = Device("b", this)
        b.library.load("Lokal B", "Seite")
        b.syncAll()
        assertEquals("work", b.books[job.id])
        assertEquals(1, b.library.tree.value.childrenOf(job.id).size)
        // B's own local book went nowhere, and A does not see it.
        a.syncAll()
        assertNull(a.bookNamed("Lokal B"))
    }

    @Test
    fun deletionsReachOnlyTheBooksAccount() = runTest {
        val a = Device("a", this)
        a.library.load("Lokal", "Seite")
        val job = a.library.createBook("Arbeit")
        val doc = a.library.createDocument(job.id, "Protokoll", PageFormat())
        a.books[job.id] = "work"
        a.syncAll()
        a.library.delete(doc.id)
        a.syncAll()
        assertFalse(work.files.keys.any { it.startsWith("documents/${doc.id.value}") })
        assertTrue(home.files.keys.none { doc.id.value in it })
    }

    @Test
    fun aLocalBookAssignedLaterIsUploaded() = runTest {
        val a = Device("a", this)
        a.library.load("Notizen", "Seite")
        a.syncAll()
        val book = a.bookNamed("Notizen")!!
        assertTrue(bookFiles(home, book.id).isEmpty())
        a.books[book.id] = "home"
        a.syncAll()
        assertEquals(1, bookFiles(home, book.id).size)
        val doc = a.library.tree.value.documents.single()
        assertTrue("documents/${doc.id.value}/meta.json" in home.files)
    }

    @Test
    fun theSameBookIdKeptLocallyIgnoresTheServerCopy() = runTest {
        // An account was removed (its books became local) and added again: the server's
        // entries of those books do not touch the local book until it is assigned again.
        val a = Device("a", this)
        a.library.load("Lokal", "Seite")
        val job = a.library.createBook("Arbeit")
        a.books[job.id] = "work"
        a.syncAll()
        a.books.remove(job.id)
        a.library.rename(job.id, "Arbeit (lokal)")
        a.syncAll()
        assertNull(a.books[job.id])
        assertEquals("Arbeit (lokal)", a.library.tree.value[job.id]!!.title)
    }
}
