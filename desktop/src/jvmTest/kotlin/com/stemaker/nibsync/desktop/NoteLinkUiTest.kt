package com.stemaker.nibsync.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.model.Anchor
import com.stemaker.nibsync.model.NoteLink
import com.stemaker.nibsync.model.PageFormat
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class NoteLinkUiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun setAnAnchorLinkToItAndFollowTheLink() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val container = DesktopContainer(root)
        val settings = Settings(container.fs, root / "settings.properties")
        setContent { App(container, settings, Strings(german = true)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("page").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val start = settings.lastDocument ?: runBlocking { container.library.tree.value.documents.single().id.asDocumentId }
        val book = runBlocking { container.library.tree.value.books.single() }
        val target = runBlocking { container.library.createDocument(book.id, "Ziel", PageFormat()).id.asDocumentId }
        runBlocking { container.library.rename(com.stemaker.nibsync.library.NodeId(start.value), "Start") }

        // An anchor in the target document.
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(LibraryTags.row("Ziel")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(LibraryTags.row("Ziel")).performClick()
        waitUntil(timeoutMillis = 5000) { settings.lastDocument == target }
        onNodeWithTag(ToolbarTags.INSERT).performClick()
        onNodeWithTag(ToolbarTags.INSERT_ANCHOR).performClick()
        onNodeWithTag(LibraryDialogTags.NAME).performTextInput("Kapitel")
        onNodeWithTag(LibraryDialogTags.CONFIRM).performClick()
        val targetSession = runBlocking { container.openSession(target) }
        waitUntil(timeoutMillis = 5000) { targetSession.state.value.anchors.isNotEmpty() }
        val anchor: Anchor = targetSession.state.value.anchors.single()
        assertThat(anchor.name).isEqualTo("Kapitel")

        // A link to it from the first document.
        onNodeWithTag(LibraryTags.row("Start")).performClick()
        waitUntil(timeoutMillis = 5000) { settings.lastDocument == start }
        waitForIdle()
        onNodeWithTag(ToolbarTags.INSERT).performClick()
        onNodeWithTag(ToolbarTags.INSERT_NOTE_LINK).performClick()
        onNodeWithTag(NoteLinkTags.SEARCH).performTextInput("zie")
        onNodeWithTag(NoteLinkTags.document(target)).performClick()
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(NoteLinkTags.anchor("Kapitel")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(NoteLinkTags.anchor("Kapitel")).performClick()
        val session = runBlocking { container.openSession(start) }
        waitUntil(timeoutMillis = 5000) { session.state.value.elements.any { it is NoteLink } }
        val link = session.state.value.elements.filterIsInstance<NoteLink>().single()
        assertThat(link.target).isEqualTo(target)
        assertThat(link.anchorId).isEqualTo(anchor.anchorId)
        assertThat(link.label("Ziel")).isEqualTo("Ziel › Kapitel")

        // It is placed in the middle of the view; a double click follows it.
        waitForIdle()
        onNodeWithTag("page").performMouseInput { doubleClick(center) }
        waitUntil(timeoutMillis = 5000) { settings.lastDocument == target }
        runBlocking { container.closeAll() }
    }
}
