package de.nibsync.desktop

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
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.google.common.truth.Truth.assertThat
import de.nibsync.model.MarkStyle
import de.nibsync.model.TextMark
import de.nibsync.text.TextTags
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
}
