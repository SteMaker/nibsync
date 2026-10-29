package com.stemaker.nibsync.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.TextMark
import com.stemaker.nibsync.model.ListKind
import com.stemaker.nibsync.text.TextPreset
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
        onNodeWithTag(TextTags.INDENT).performScrollTo().performClick()
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

    @Test
    fun sizeButtonsStepThroughTheSizes() = runComposeUiTest {
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
        editor.performTextInput("klein gross")
        // "gross" two steps larger (12 -> 14 -> 18 pt), "klein" one step smaller (10 pt).
        editor.performTextInputSelection(TextRange(6, 11))
        onNodeWithTag(TextTags.GROW).performClick()
        onNodeWithTag(TextTags.GROW).performClick()
        editor.performTextInputSelection(TextRange(0, 5))
        onNodeWithTag(TextTags.SHRINK).performClick()
        editor.assertIsFocused()
        editor.performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(TextTags.EDITOR).fetchSemanticsNodes().isEmpty() }

        val marks = session.state.value.textBoxes.single().text.paragraphs.single().marks
        assertThat(marks).contains(TextMark(6, 11, MarkStyle.Size(18f)))
        assertThat(marks).contains(TextMark(0, 5, MarkStyle.Size(10f)))
        runBlocking { container.closeAll() }
    }

    @Test
    fun homeAndArrowsKeepTheCursorOutOfListMarkers() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val container = DesktopContainer(root)
        val settings = Settings(container.fs, root / "settings.properties")
        setContent { App(container, settings, Strings(german = true)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("page").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onNodeWithTag("page").performMouseInput { click(Offset(300f, 300f)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(TextTags.EDITOR).fetchSemanticsNodes().isNotEmpty() }
        val editor = onNodeWithTag(TextTags.EDITOR)
        editor.performTextInput("Titel")
        editor.performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag(TextTags.BULLETS).performClick()
        waitForIdle()
        editor.performTextInput("Punkt eins")
        editor.performKeyInput { pressKey(Key.Enter) }
        editor.performTextInput("Punkt zwei")
        waitForIdle()
        fun cursor(): Int = editor.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange].start
        fun shown(): String = editor.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        fun press(key: Key) {
            editor.performKeyInput { pressKey(key) }
            waitForIdle()
        }
        // "Titel • Punkt eins • Punkt zwei": Home goes behind the marker, not before it.
        val textStart = shown().lastIndexOf("Punkt zwei")
        press(Key.MoveHome)
        assertThat(cursor()).isEqualTo(textStart)
        // Left from there leaves the line (end of "Punkt eins"), right comes back behind the marker.
        press(Key.DirectionLeft)
        assertThat(cursor()).isEqualTo(shown().indexOf("Punkt eins") + "Punkt eins".length)
        press(Key.DirectionRight)
        assertThat(cursor()).isEqualTo(textStart)
        // Typing there goes into the text, the list stays intact.
        editor.performTextInput("X")
        editor.performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(TextTags.EDITOR).fetchSemanticsNodes().isEmpty() }
        val docId = settings.lastDocument ?: runBlocking { container.library.tree.value.documents.single().id.asDocumentId }
        val box = runBlocking { container.openSession(docId) }.state.value.textBoxes.single()
        assertThat(box.text.paragraphs.map { it.text to it.list }).containsExactly(
            "Titel" to com.stemaker.nibsync.model.ListKind.NONE,
            "Punkt eins" to com.stemaker.nibsync.model.ListKind.BULLET,
            "XPunkt zwei" to com.stemaker.nibsync.model.ListKind.BULLET,
        ).inOrder()
        runBlocking { container.closeAll() }
    }

    @Test
    fun presetMenuAndShiftEnterInAList() = runComposeUiTest {
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
        editor.performTextInput("Titel")
        // The style menu makes the whole line a heading; the button then names it.
        onNodeWithTag(TextTags.PRESET).performClick()
        onNodeWithTag(TextTags.preset(TextPreset.HEADING_2)).performClick()
        editor.assertIsFocused()
        onNodeWithTag(TextTags.PRESET).assertTextContains("Überschrift 2")
        editor.performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag(TextTags.PRESET).performClick()
        onNodeWithTag(TextTags.preset(TextPreset.NORMAL)).performClick()
        // A list item with a further line (Shift+Enter), then the next item (Enter).
        onNodeWithTag(TextTags.BULLETS).performScrollTo().performClick()
        editor.performTextInput("Punkt")
        editor.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        editor.performTextInput("weiter")
        editor.performKeyInput { pressKey(Key.Enter) }
        editor.performTextInput("Zwei")
        editor.performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(TextTags.EDITOR).fetchSemanticsNodes().isEmpty() }

        val p = session.state.value.textBoxes.single().text.paragraphs
        assertThat(p.map { it.text }).containsExactly("Titel", "Punkt", "weiter", "Zwei").inOrder()
        assertThat(p[0].marks).containsAtLeast(TextMark(0, 5, MarkStyle.Bold), TextMark(0, 5, MarkStyle.Size(18f)))
        assertThat(p.map { it.list }).containsExactly(ListKind.NONE, ListKind.BULLET, ListKind.BULLET, ListKind.BULLET).inOrder()
        assertThat(p.map { it.continued }).containsExactly(false, false, true, false).inOrder()
        assertThat(p[1].marks.map { it.style }).doesNotContain(MarkStyle.Bold)
        runBlocking { container.closeAll() }
    }
}
