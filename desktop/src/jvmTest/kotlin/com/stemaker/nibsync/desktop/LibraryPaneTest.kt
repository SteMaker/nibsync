package com.stemaker.nibsync.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.library.LibraryTree
import com.stemaker.nibsync.model.PageFormat
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class LibraryPaneTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun rowsFollowExpandedFolders() = runBlocking {
        val container = DesktopContainer(tmp.root.toOkioPath())
        val lib = container.library
        lib.load("Buch", "Seite 1")
        val book = lib.tree.value.books.single()
        val chapter = lib.createFolder(book.id, "Kapitel")
        lib.createDocument(chapter.id, "Seite 2", PageFormat())
        val tree: LibraryTree = lib.tree.value
        assertThat(treeRows(tree, emptySet()).map { it.node.title }).containsExactly("Buch")
        // Folders first, then documents.
        assertThat(treeRows(tree, setOf(book.id)).map { it.node.title }).containsExactly("Buch", "Kapitel", "Seite 1").inOrder()
        assertThat(treeRows(tree, setOf(book.id, chapter.id)).map { it.node.title to it.depth })
            .containsExactly("Buch" to 0, "Kapitel" to 1, "Seite 2" to 2, "Seite 1" to 1).inOrder()
    }

    @Test
    fun clickingADocumentOpensItAndIsRemembered() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val container = DesktopContainer(root)
        val settings = Settings(container.fs, root / "settings.properties")
        val second = runBlocking {
            container.library.load("Buch", "Seite 1")
            val book = container.library.tree.value.books.single()
            container.library.createDocument(book.id, "Seite 2", PageFormat())
        }
        setContent { App(container, settings, Strings(german = true)) }
        waitUntil(timeoutMillis = 5000) { runCatching { onNodeWithTag(LibraryTags.row("Buch")).assertExists() }.isSuccess }
        onNodeWithTag(LibraryTags.row("Buch")).performClick()
        onNodeWithTag(LibraryTags.row("Seite 2")).performClick()
        waitUntil(timeoutMillis = 5000) { settings.lastDocument == second.id.asDocumentId }
        // A new window opens the same document again.
        assertThat(Settings(container.fs, root / "settings.properties").lastDocument).isEqualTo(second.id.asDocumentId)
        runBlocking { container.closeAll() }
    }
}
