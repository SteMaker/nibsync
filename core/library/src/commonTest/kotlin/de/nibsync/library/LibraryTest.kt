package de.nibsync.library

import de.nibsync.model.DocumentId
import de.nibsync.model.DocumentMeta
import de.nibsync.model.Orientation
import de.nibsync.model.PageFormat
import de.nibsync.model.PaperSize
import de.nibsync.storage.FileDocumentRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryTest {
    private val fs = FakeFileSystem()
    private var now = 1000L

    private fun TestScope.documents() = FileDocumentRepository(fs, "/data/documents".toPath(), StandardTestDispatcher(testScheduler))
    private fun TestScope.nodes() = FileLibraryRepository(fs, "/data/library".toPath(), StandardTestDispatcher(testScheduler))
    private fun TestScope.library() = Library(nodes(), documents(), clock = { now++ })

    private val LibraryTree.titles: List<String> get() = books.map { it.title }

    @Test
    fun newInstallationGetsABookWithOneDocument() = runTest {
        val lib = library()
        lib.load("Notizen", "Erste Seite")
        val tree = lib.tree.value
        assertEquals(listOf("Notizen"), tree.titles)
        val doc = tree.childrenOf(tree.books.single().id).single()
        assertEquals(NodeKind.DOCUMENT, doc.kind)
        assertEquals("Erste Seite", doc.title)
        assertTrue(documents().exists(doc.id.asDocumentId))
    }

    @Test
    fun existingNotesAreAdoptedIntoTheFirstBook() = runTest {
        documents().create(DocumentMeta(DocumentId("default"), "old", createdAtEpochMs = 0))
        val lib = library()
        lib.load("Notizen", "Notizen", legacyDocument = DocumentId("default"))
        val tree = lib.tree.value
        val doc = tree.childrenOf(tree.books.single().id).single()
        assertEquals(NodeId("default"), doc.id)
        assertEquals(1, fs.list("/data/documents".toPath()).size) // no extra document created
    }

    @Test
    fun loadingAgainDoesNotCreateMore() = runTest {
        library().load("A", "B")
        val lib = library()
        lib.load("A", "B")
        assertEquals(1, lib.tree.value.books.size)
        assertEquals(1, lib.tree.value.documents.size)
    }

    @Test
    fun nestedChaptersAndDocuments() = runTest {
        val lib = library()
        lib.load("Book", "Doc")
        val book = lib.createBook("Mathe")
        val ch1 = lib.createFolder(book.id, "Kapitel 1")
        val ch11 = lib.createFolder(ch1.id, "Abschnitt 1.1")
        val doc = lib.createDocument(ch11.id, "Integrale", PageFormat(PaperSize.A5, Orientation.LANDSCAPE))
        val beside = lib.createDocument(ch1.id, "Übersicht", PageFormat())

        val tree = lib.tree.value
        assertEquals(listOf(ch11.id, beside.id), tree.childrenOf(ch1.id).map { it.id }) // folders first
        assertEquals(listOf("Mathe", "Kapitel 1", "Abschnitt 1.1", "Integrale"), tree.pathTo(doc.id).map { it.title })
        // The stored document has the chosen format.
        val meta = documents().load(doc.id.asDocumentId).meta
        assertEquals(PageFormat(PaperSize.A5, Orientation.LANDSCAPE), meta.format)
        assertEquals("Integrale", meta.title)
    }

    @Test
    fun treeSurvivesReload() = runTest {
        val lib = library()
        lib.load("Book", "Doc")
        val book = lib.createBook("Mathe")
        lib.createFolder(book.id, "Kapitel 1")
        val again = library()
        again.load("x", "y")
        assertEquals(listOf("Book", "Mathe"), again.tree.value.titles)
        assertEquals(listOf("Kapitel 1"), again.tree.value.childrenOf(book.id).map { it.title })
    }

    @Test
    fun documentsCannotContainAnything() = runTest {
        val lib = library()
        lib.load("Book", "Doc")
        val doc = lib.tree.value.documents.single()
        assertFailsWith<IllegalArgumentException> { lib.createFolder(doc.id, "x") }
        assertFailsWith<IllegalArgumentException> { lib.createDocument(doc.id, "x", PageFormat()) }
        assertFailsWith<IllegalArgumentException> { LibraryNode(NodeId("d"), NodeKind.DOCUMENT, null, "x", 0) }
    }

    @Test
    fun sortingIsFoldersFirstThenByTitle() = runTest {
        val lib = library()
        lib.load("Book", "zeta")
        val book = lib.tree.value.books.single()
        lib.createDocument(book.id, "alpha", PageFormat())
        lib.createFolder(book.id, "Zoo")
        lib.createFolder(book.id, "apfel")
        assertEquals(listOf("apfel", "Zoo", "alpha", "zeta"), lib.tree.value.childrenOf(book.id).map { it.title })
    }

    @Test
    fun deletingAFolderRemovesEverythingInsideAndKeepsTombstones() = runTest {
        val lib = library()
        lib.load("Book", "Doc")
        val book = lib.createBook("Mathe")
        val ch = lib.createFolder(book.id, "Kapitel")
        val doc = lib.createDocument(ch.id, "Seite", PageFormat())
        lib.delete(book.id)
        assertEquals(listOf("Book"), lib.tree.value.titles)
        assertNull(lib.tree.value[doc.id])
        assertFalse(documents().exists(doc.id.asDocumentId))
        // Tombstones remain on disk so a sync cannot resurrect the entries.
        val stored = nodes().loadAll().filter { it.deleted }.map { it.id }.toSet()
        assertEquals(setOf(book.id, ch.id, doc.id), stored)
    }

    @Test
    fun renameKeepsPlace() = runTest {
        val lib = library()
        lib.load("Book", "Doc")
        val book = lib.tree.value.books.single()
        lib.rename(book.id, "  Tagebuch ")
        assertEquals("Tagebuch", lib.tree.value[book.id]!!.title)
        lib.rename(book.id, "   ")
        assertEquals("Tagebuch", lib.tree.value[book.id]!!.title) // blank names are ignored
    }

    @Test
    fun orphansAreHidden() {
        val tree = LibraryTree(listOf(
            LibraryNode(NodeId("b"), NodeKind.FOLDER, null, "Book", 0),
            LibraryNode(NodeId("gone"), NodeKind.FOLDER, NodeId("b"), "Deleted", 0, deleted = true),
            LibraryNode(NodeId("orphan"), NodeKind.DOCUMENT, NodeId("gone"), "Lost", 0),
            LibraryNode(NodeId("x"), NodeKind.FOLDER, NodeId("y"), "Cycle 1", 0),
            LibraryNode(NodeId("y"), NodeKind.FOLDER, NodeId("x"), "Cycle 2", 0),
        ))
        assertEquals(listOf("Book"), tree.titles)
        assertEquals(emptyList(), tree.childrenOf(NodeId("b")))
        assertNull(tree[NodeId("orphan")])
        assertEquals(emptyList(), tree.pathTo(NodeId("x")))
        assertEquals(emptyList(), tree.documents)
    }

    @Test
    fun damagedNodeFilesAreSkipped() = runTest {
        val lib = library()
        lib.load("Book", "Doc")
        fs.write("/data/library/broken.json".toPath()) { writeUtf8("{ nope") }
        val again = library()
        again.load("x", "y")
        assertEquals(listOf("Book"), again.tree.value.titles)
    }
}
