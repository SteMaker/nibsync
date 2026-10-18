package com.stemaker.nibsync.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.TextMark
import com.stemaker.nibsync.text.TextTags
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class TextEditingUiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun clickTypeFormatAndClose() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val container = DesktopContainer(root)
        val settings = Settings(container.fs, root / "settings.properties")
        setContent { App(container, settings, Strings(german = true)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("page").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val docId = settings.lastDocument ?: runBlocking { container.library.tree.value.documents.single().id.asDocumentId }
        val session = runBlocking { container.openSession(docId) }

        // A click on the page opens an editor there; typing goes into it.
        onNodeWithTag("page").performMouseInput { click(Offset(300f, 200f)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(TextTags.EDITOR).fetchSemanticsNodes().isNotEmpty() }
        val editor: SemanticsNodeInteraction = onNodeWithTag(TextTags.EDITOR)
        editor.performTextInput("Hallo ")
        // Ctrl+B switches bold on for what follows.
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        editor.performTextInput("PC")
        // Escape closes the box and stores it.
        editor.performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(TextTags.EDITOR).fetchSemanticsNodes().isEmpty() }
        val box = session.state.value.textBoxes.single()
        assertThat(box.text.plainText).isEqualTo("Hallo PC")
        assertThat(box.text.paragraphs.single().marks).contains(TextMark(6, 8, MarkStyle.Bold))

        // Undo in the tool bar removes it again.
        onNodeWithTag(ToolbarTags.UNDO).performClick()
        waitUntil(timeoutMillis = 5000) { session.state.value.textBoxes.isEmpty() }
        runBlocking { container.closeAll() }
    }

    @Test
    fun selectedTextBecomesBoldByTheToolbarAndListsKeepTheirLayout() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val container = DesktopContainer(root)
        val settings = Settings(container.fs, root / "settings.properties")
        setContent { App(container, settings, Strings(german = true)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("page").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val docId = settings.lastDocument ?: runBlocking { container.library.tree.value.documents.single().id.asDocumentId }
        val session = runBlocking { container.openSession(docId) }

        onNodeWithTag("page").performMouseInput { click(Offset(300f, 200f)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(TextTags.EDITOR).fetchSemanticsNodes().isNotEmpty() }
        val editor = onNodeWithTag(TextTags.EDITOR)
        editor.performTextInput("Hallo Welt")
        // Select "Welt", then the tool bar's bold button: the selection stays and gets bold.
        editor.performTextInputSelection(TextRange(6, 10))
        onNodeWithTag(TextTags.BOLD).performClick()
        editor.assertIsFocused()
        // A line and an indented bullet list below it.
        editor.performTextInputSelection(TextRange(10))
        editor.performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag(TextTags.BULLETS).performClick()
        editor.performTextInput("Punkt eins")
        editor.performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag(TextTags.INDENT).performClick()
        editor.performTextInput("Unterpunkt")
        val editorHeight = editor.fetchSemanticsNode().size.height
        editor.performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(TextTags.EDITOR).fetchSemanticsNodes().isEmpty() }

        val box = session.state.value.textBoxes.single()
        assertThat(box.text.paragraphs.map { it.text }).containsExactly("Hallo Welt", "Punkt eins", "Unterpunkt").inOrder()
        assertThat(box.text.paragraphs[0].marks).contains(TextMark(6, 10, MarkStyle.Bold))
        assertThat(box.text.paragraphs.map { it.indent }).containsExactly(0, 0, 1).inOrder()
        // On the page the box is as tall as the editor was: three lines, no extra ones.
        val viewportScale = editorHeight / box.heightMm
        val layout = DesktopTextLayout().layout(box.text, box.widthMm)
        assertThat(layout.lineCount).isEqualTo(3)
        assertThat(viewportScale).isGreaterThan(0f)
        runBlocking { container.closeAll() }
    }
}
