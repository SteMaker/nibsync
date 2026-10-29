package com.stemaker.nibsync.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.library.LibraryTree
import com.stemaker.nibsync.model.BackgroundKind
import com.stemaker.nibsync.model.Orientation
import com.stemaker.nibsync.model.PageFormat
import com.stemaker.nibsync.model.PaperSize
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
        // The book of the shown document is open already.
        waitUntil(timeoutMillis = 5000) { runCatching { onNodeWithTag(LibraryTags.row("Seite 2")).assertExists() }.isSuccess }
        onNodeWithTag(LibraryTags.row("Seite 2")).performClick()
        waitUntil(timeoutMillis = 5000) { settings.lastDocument == second.id.asDocumentId }
        // A new window opens the same document again.
        assertThat(Settings(container.fs, root / "settings.properties").lastDocument).isEqualTo(second.id.asDocumentId)
        runBlocking { container.closeAll() }
    }

    @Test
    fun rightClickCreatesRenamesAndDeletesEntries() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val container = DesktopContainer(root)
        val settings = Settings(container.fs, root / "settings.properties")
        runBlocking { container.library.load("Buch", "Seite 1") }
        val lib = container.library
        fun tree() = lib.tree.value
        fun menu(row: String, item: String) {
            onNodeWithTag(LibraryTags.row(row)).performMouseInput { rightClick() }
            onNodeWithTag(item).performClick()
        }
        setContent { App(container, settings, Strings(german = true)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(LibraryTags.row("Seite 1")).fetchSemanticsNodes().isNotEmpty() }

        // A chapter in the book.
        menu("Buch", LibraryTags.NEW_CHAPTER)
        onNodeWithTag(LibraryDialogTags.NAME).performTextInput("Projekte")
        onNodeWithTag(LibraryDialogTags.CONFIRM).performClick()
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(LibraryTags.row("Projekte")).fetchSemanticsNodes().isNotEmpty() }
        val chapter = tree().books.single().let { b -> tree().childrenOf(b.id).first { it.title == "Projekte" } }

        // A document in the chapter: A5 landscape, lined; it opens right away.
        menu("Projekte", LibraryTags.NEW_DOCUMENT)
        onNodeWithTag(LibraryDialogTags.NAME).performTextInput("Protokoll")
        onNodeWithTag(LibraryDialogTags.paper(PaperSize.A5)).performClick()
        onNodeWithTag(LibraryDialogTags.orientation(Orientation.LANDSCAPE)).performClick()
        onNodeWithTag(LibraryDialogTags.background(BackgroundKind.LINED)).performClick()
        onNodeWithTag(LibraryDialogTags.CONFIRM).performClick()
        waitUntil(timeoutMillis = 5000) { tree().childrenOf(chapter.id).any { it.title == "Protokoll" } }
        val doc = tree().childrenOf(chapter.id).single()
        waitUntil(timeoutMillis = 5000) { settings.lastDocument == doc.id.asDocumentId }
        val meta = runBlocking { container.repository.load(doc.id.asDocumentId).meta }
        assertThat(meta.format).isEqualTo(PageFormat(PaperSize.A5, Orientation.LANDSCAPE))
        assertThat(meta.background.kind).isEqualTo(BackgroundKind.LINED)
        onNodeWithTag(LibraryTags.row("Protokoll")).assertExists()

        // Rename: the old name is selected, typing replaces it.
        menu("Protokoll", LibraryTags.RENAME)
        onNodeWithTag(LibraryDialogTags.NAME).performTextInput("Sitzung")
        onNodeWithTag(LibraryDialogTags.CONFIRM).performClick()
        waitUntil(timeoutMillis = 5000) { tree()[doc.id]?.title == "Sitzung" }

        // Deleting the chapter takes the open document with it; another one is shown.
        menu("Projekte", LibraryTags.DELETE)
        onNodeWithTag(LibraryDialogTags.CONFIRM).performClick()
        waitUntil(timeoutMillis = 5000) { tree()[chapter.id] == null && tree()[doc.id] == null }
        assertThat(runBlocking { container.repository.exists(doc.id.asDocumentId) }).isFalse()
        waitUntil(timeoutMillis = 5000) { settings.lastDocument != doc.id.asDocumentId || onAllNodesWithTag(LibraryTags.row("Sitzung")).fetchSemanticsNodes().isEmpty() }
        onNodeWithTag(LibraryTags.row("Sitzung")).assertDoesNotExist()
        runBlocking { container.closeAll() }
    }

    @Test
    fun dragADocumentIntoAChapterOfAnotherBookHoldingOpensFolders() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val container = DesktopContainer(root)
        val settings = Settings(container.fs, root / "settings.properties")
        val lib = container.library
        val (doc, chapter) = runBlocking {
            lib.load("Buch", "Seite 1")
            val other = lib.createBook("Arbeit")
            val chapter = lib.createFolder(other.id, "Projekte")
            lib.createDocument(chapter.id, "Alt", PageFormat())
            lib.tree.value.documents.first { it.title == "Seite 1" } to chapter
        }
        settings.lastDocument = doc.id.asDocumentId
        setContent { App(container, settings, Strings(german = true)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(LibraryTags.row("Seite 1")).fetchSemanticsNodes().isNotEmpty() }
        // "Arbeit" is closed: its chapter is not visible yet.
        onNodeWithTag(LibraryTags.row("Projekte")).assertDoesNotExist()
        fun center(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center

        onRoot().performMouseInput {
            moveTo(center(LibraryTags.row("Seite 1")))
            press()
            moveBy(Offset(0f, 12f))
            moveTo(center(LibraryTags.row("Arbeit")))
        }
        onNodeWithTag(LibraryTags.DRAGGED).assertExists()
        // Held over the closed book, it opens.
        mainClock.advanceTimeBy(1000)
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(LibraryTags.row("Projekte")).fetchSemanticsNodes().isNotEmpty() }
        onRoot().performMouseInput {
            moveTo(center(LibraryTags.row("Projekte")))
        }
        // A document row as target means its folder: dropping on "Alt" would do the same.
        onRoot().performMouseInput { release() }
        waitUntil(timeoutMillis = 5000) { lib.tree.value[doc.id]?.parent == chapter.id }
        assertThat(lib.tree.value.pathTo(doc.id).map { it.title }).containsExactly("Arbeit", "Projekte", "Seite 1").inOrder()
        onNodeWithTag(LibraryTags.DRAGGED).assertDoesNotExist()
        // The document stays usable and shown in its new place.
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(LibraryTags.row("Seite 1")).fetchSemanticsNodes().isNotEmpty() }

        // A chapter cannot go into itself; nothing happens.
        onRoot().performMouseInput {
            moveTo(center(LibraryTags.row("Projekte")))
            press()
            moveBy(Offset(0f, 12f))
            moveTo(center(LibraryTags.row("Alt")))
            release()
        }
        waitForIdle()
        assertThat(lib.tree.value[chapter.id]?.parent).isEqualTo(lib.tree.value.books.first { it.title == "Arbeit" }.id)
        runBlocking { container.closeAll() }
    }

    @Test
    fun searchFindsADocumentAndABookGetsAColour() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val container = DesktopContainer(root)
        val settings = Settings(container.fs, root / "settings.properties")
        val (book, wanted) = runBlocking {
            container.library.load("Buch", "Seite 1")
            val book = container.library.tree.value.books.single()
            val chapter = container.library.createFolder(book.id, "Projekte")
            book to container.library.createDocument(chapter.id, "Kickoff Atlas", PageFormat())
        }
        setContent { App(container, settings, Strings(german = true)) }
        waitUntil(timeoutMillis = 5000) { runCatching { onNodeWithTag(LibraryTags.row("Buch")).assertExists() }.isSuccess }

        // A search shows matching documents (also inside closed chapters); a click opens one.
        onNodeWithTag(LibraryTags.SEARCH).performTextInput("atlas")
        onNodeWithTag(LibraryTags.SEARCH_RESULTS).assertExists()
        onNodeWithTag(LibraryTags.row("Kickoff Atlas")).performClick()
        waitUntil(timeoutMillis = 5000) { settings.lastDocument == wanted.id.asDocumentId }
        onNodeWithTag(LibraryTags.SEARCH_RESULTS).assertDoesNotExist()

        // The book's menu sets its colour.
        onNodeWithTag(LibraryTags.row("Buch")).performMouseInput { rightClick() }
        onNodeWithTag(LibraryTags.bookColor(4)).performClick()
        waitUntil(timeoutMillis = 5000) { container.library.tree.value[book.id]?.colorArgb == com.stemaker.nibsync.library.BookColors.PALETTE[4] }
        runBlocking { container.closeAll() }
    }
}
