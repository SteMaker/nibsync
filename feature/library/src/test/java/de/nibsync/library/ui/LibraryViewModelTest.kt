package de.nibsync.library.ui

import com.google.common.truth.Truth.assertThat
import de.nibsync.library.FileLibraryRepository
import de.nibsync.library.Library
import de.nibsync.model.DocumentId
import de.nibsync.model.PageFormat
import de.nibsync.storage.FileDocumentRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Test

class LibraryViewModelTest {
    private val fs = FakeFileSystem()
    private val deleted = mutableListOf<Set<DocumentId>>()

    private suspend fun TestScope.vm(): LibraryViewModel {
        val io = StandardTestDispatcher(testScheduler)
        val library = Library(FileLibraryRepository(fs, "/l".toPath(), io), FileDocumentRepository(fs, "/d".toPath(), io), clock = { 0L })
        library.load("Book", "Doc")
        return LibraryViewModel(library) { deleted += it }
    }

    private fun LibraryViewModel.visibleTitles() = rows(tree.value, expanded.value).map { "  ".repeat(it.depth) + it.node.title }

    @Test
    fun foldersStartClosed() = runTest {
        val vm = vm()
        assertThat(vm.visibleTitles()).containsExactly("Book")
        assertThat(vm.selectedFolder.value).isNull()
    }

    @Test
    fun tappingSelectsAndOpensThenTogglesTheSelectedFolder() = runTest {
        val vm = vm()
        val book = vm.tree.value.books.single().id
        vm.onFolderTapped(book)
        assertThat(vm.selectedFolder.value).isEqualTo(book)
        assertThat(vm.visibleTitles()).containsExactly("Book", "  Doc").inOrder()
        vm.onFolderTapped(book) // second tap closes
        assertThat(vm.visibleTitles()).containsExactly("Book")
        assertThat(vm.selectedFolder.value).isEqualTo(book)
    }

    @Test
    fun createInSelectedFolder() = runTest {
        val vm = vm()
        assertThat(vm.createFolder("x")).isNull() // nothing selected
        val book = vm.tree.value.books.single().id
        vm.onFolderTapped(book)
        val chapter = vm.createFolder("Kapitel")!!
        // The new chapter is selected and open, so the next document goes into it.
        assertThat(vm.selectedFolder.value).isEqualTo(chapter.id)
        val doc = vm.createDocument("Seite", PageFormat())!!
        assertThat(doc.parent).isEqualTo(chapter.id)
        assertThat(vm.visibleTitles()).containsExactly("Book", "  Kapitel", "    Seite", "  Doc").inOrder()
    }

    @Test
    fun newBookIsSelected() = runTest {
        val vm = vm()
        val book = vm.createBook("Zweites")
        assertThat(vm.selectedFolder.value).isEqualTo(book.id)
    }

    @Test
    fun revealOpensThePathToADocument() = runTest {
        val vm = vm()
        val book = vm.tree.value.books.single().id
        vm.onFolderTapped(book)
        val chapter = vm.createFolder("Kapitel")!!
        val doc = vm.createDocument("Seite", PageFormat())!!
        vm.onFolderTapped(book) // selects the (open) book
        vm.onFolderTapped(book) // closes it
        assertThat(vm.visibleTitles()).containsExactly("Book")
        vm.reveal(doc.id)
        assertThat(vm.visibleTitles()).contains("    Seite")
        assertThat(vm.expanded.value).containsAtLeast(book, chapter.id)
    }

    @Test
    fun deleteReportsTheDocumentsAndClearsSelection() = runTest {
        val vm = vm()
        val book = vm.tree.value.books.single().id
        val firstDoc = vm.tree.value.documents.single().id
        vm.onFolderTapped(book)
        val chapter = vm.createFolder("Kapitel")!!
        val doc = vm.createDocument("Seite", PageFormat())!!
        vm.delete(book)
        assertThat(deleted.single()).containsExactly(firstDoc.asDocumentId, doc.id.asDocumentId)
        assertThat(vm.selectedFolder.value).isNull()
        assertThat(vm.tree.value.books).isEmpty()
        assertThat(vm.expanded.value).doesNotContain(chapter.id)
    }
}
