package com.stemaker.nibsync.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.model.TodoContent
import com.stemaker.nibsync.text.TextTags
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class TodoUiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun typeATodoResizeItAndTickItOffInTheList() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val container = DesktopContainer(root)
        val settings = Settings(container.fs, root / "settings.properties")
        setContent { App(container, settings, Strings(german = true)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("page").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val docId = settings.lastDocument ?: runBlocking { container.library.tree.value.documents.single().id.asDocumentId }
        val session = runBlocking { container.openSession(docId) }

        // The mouse picks the keyboard; the settings bar offers the pen instead.
        onNodeWithTag(ToolbarTags.TODO).performClick()
        onNodeWithTag(ToolbarTags.TODO_KEYBOARD).assertExists()
        onNodeWithTag(ToolbarTags.TODO_INK).assertExists()
        onNodeWithTag("page").performMouseInput { click(Offset(300f, 200f)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(TextTags.EDITOR).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(TextTags.EDITOR).performTextInput("Rechnung bezahlen")
        // Dragging the corner makes it larger.
        onNodeWithTag(TodoTags.RESIZE).performMouseInput {
            moveTo(center)
            press()
            for (i in 1..10) moveTo(center + Offset(i * 8f, i * 4f))
            release()
        }
        waitUntil(timeoutMillis = 5000) { session.state.value.todos.isNotEmpty() }
        val before = session.state.value.todos.single()
        assertThat(before.widthMm).isGreaterThan(com.stemaker.nibsync.model.TodoItem.DEFAULT_WIDTH_MM)
        assertThat(before.heightMm).isGreaterThan(com.stemaker.nibsync.model.TodoItem.DEFAULT_HEIGHT_MM)
        assertThat((before.content as TodoContent.Text).text.plainText).isEqualTo("Rechnung bezahlen")

        // The list shows it; ticking it off there sets it done in the document.
        onNodeWithTag(LibraryTags.TODOS).performClick()
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(TodoListTags.item(before.taskId)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(TodoListTags.item(before.taskId)).performMouseInput { click(Offset(10f, 15f)) }
        waitUntil(timeoutMillis = 5000) { session.state.value.isDone(before.taskId) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(TodoListTags.EMPTY).fetchSemanticsNodes().isNotEmpty() }
        // Undo reopens it.
        onNodeWithTag(TodoListTags.UNDO).performClick()
        waitUntil(timeoutMillis = 5000) { !session.state.value.isDone(before.taskId) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(TodoListTags.item(before.taskId)).fetchSemanticsNodes().isNotEmpty() }

        // A click on the entry opens the document.
        onNodeWithTag(TodoListTags.item(before.taskId)).performMouseInput { click(Offset(width - 5f, 15f)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("page").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { container.closeAll() }
    }
}
