package com.stemaker.nibsync.desktop

import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.model.CellKey
import com.stemaker.nibsync.model.TableElement
import com.stemaker.nibsync.theme.TableSizeTags
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class TableUiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TableElement.textAt(row: Int, col: Int) = cell(CellKey(rows[row].rowId, columns[col].colId)).text.plainText

    @Test
    fun insertTypeTabAddRowsJoinAndUndo() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val container = DesktopContainer(root)
        val settings = Settings(container.fs, root / "settings.properties")
        setContent { App(container, settings, Strings(german = true)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("page").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val docId = settings.lastDocument ?: runBlocking { container.library.tree.value.documents.single().id.asDocumentId }
        val session = runBlocking { container.openSession(docId) }

        // Insert > Table…, 2 x 3 with a header row.
        onNodeWithTag(ToolbarTags.INSERT).performClick()
        onNodeWithTag(ToolbarTags.INSERT_TABLE).performClick()
        onNodeWithTag(TableSizeTags.cell(2, 3)).performClick()
        onNodeWithTag(TableSizeTags.CONFIRM).performClick()
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(OverlayTags.TABLE_CELL).fetchSemanticsNodes().isNotEmpty() }
        val table = session.state.value.tables.single()
        assertThat(table.rows).hasSize(2)
        assertThat(table.columns).hasSize(3)
        assertThat(table.header).isTrue()

        // Typing and Tab through the cells; Tab in the last cell adds a row.
        fun type(text: String) = onNodeWithTag(OverlayTags.TABLE_CELL).performTextInput(text)
        fun tab() = onNodeWithTag(OverlayTags.TABLE_CELL).performKeyInput { pressKey(Key.Tab) }
        for (t in listOf("Name", "Menge", "Preis", "Äpfel", "3", "1,20")) {
            type(t)
            tab()
        }
        type("Birnen")
        onNodeWithTag(OverlayTags.TABLE_CELL).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        waitForIdle()
        var t = session.state.value.tables.single()
        assertThat(t.rows).hasSize(3)
        assertThat(listOf(t.textAt(0, 0), t.textAt(0, 2), t.textAt(1, 1), t.textAt(2, 0))).containsExactly("Name", "Preis", "3", "Birnen").inOrder()

        // Back to the first cell (Shift+Tab from row 2, column 3), then join it with the next one.
        repeat(5) { onNodeWithTag(OverlayTags.TABLE_CELL).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } } }
        waitForIdle()
        onNodeWithTag(ToolbarTags.TABLE_MENU).performScrollTo().performClick()
        onNodeWithTag(ToolbarTags.tableAction("join-right")).performClick()
        waitForIdle()
        t = session.state.value.tables.single()
        assertThat(t.geometry.merged.single().colSpan).isEqualTo(2)
        assertThat(t.textAt(0, 0)).isEqualTo("Name\nMenge")
        // One undo restores both cells.
        onNodeWithTag(OverlayTags.TABLE_CELL).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithTag(ToolbarTags.UNDO).performClick()
        waitUntil(timeoutMillis = 5000) { session.state.value.tables.single().geometry.merged.isEmpty() }
        assertThat(session.state.value.tables.single().textAt(0, 1)).isEqualTo("Menge")
        runBlocking { container.closeAll() }
    }


    @Test
    fun typingAboveATablePushesItDownAndATableCanBeInsertedAtTheCursor() = runComposeUiTest {
        val root = tmp.root.toOkioPath()
        val container = DesktopContainer(root)
        val settings = Settings(container.fs, root / "settings.properties")
        setContent { App(container, settings, Strings(german = true)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("page").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val docId = settings.lastDocument ?: runBlocking { container.library.tree.value.documents.single().id.asDocumentId }
        val session = runBlocking { container.openSession(docId) }

        // A text box near the top, a table in it below the text.
        onNodeWithTag("page").performMouseInput { click(androidx.compose.ui.geometry.Offset(300f, 150f)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(com.stemaker.nibsync.text.TextTags.EDITOR).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(com.stemaker.nibsync.text.TextTags.EDITOR).performTextInput("eins")
        onNodeWithTag(ToolbarTags.INSERT_TABLE_HERE).performScrollTo().performClick()
        onNodeWithTag(TableSizeTags.cell(2, 2)).performClick()
        onNodeWithTag(TableSizeTags.CONFIRM).performClick()
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(OverlayTags.TABLE_CELL).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(OverlayTags.TABLE_CELL).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        val box = session.state.value.textBoxes.single()
        val tableTop = session.state.value.tables.single().y
        assertThat(tableTop).isGreaterThan(box.bounds.bottom)

        // Two more lines in the box: the table moves down by the box's growth.
        onNodeWithTag("page").performMouseInput { click(androidx.compose.ui.geometry.Offset(330f, 152f)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(com.stemaker.nibsync.text.TextTags.EDITOR).fetchSemanticsNodes().isNotEmpty() }
        val editor = onNodeWithTag(com.stemaker.nibsync.text.TextTags.EDITOR)
        editor.performTextInputSelection(androidx.compose.ui.text.TextRange(4))
        editor.performKeyInput { pressKey(Key.Enter) }
        editor.performTextInput("zwei")
        editor.performKeyInput { pressKey(Key.Enter) }
        editor.performTextInput("drei")
        editor.performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        val grown = session.state.value.textBoxes.single()
        val growth = grown.heightMm - box.heightMm
        assertThat(growth).isGreaterThan(5f)
        assertThat(session.state.value.tables.single().y).isWithin(0.1f).of(tableTop + growth)

        // The cursor after "zwei", then a table right there: the box is split around it.
        onNodeWithTag("page").performMouseInput { click(androidx.compose.ui.geometry.Offset(330f, 152f)) }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTag(com.stemaker.nibsync.text.TextTags.EDITOR).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(com.stemaker.nibsync.text.TextTags.EDITOR).performTextInputSelection(androidx.compose.ui.text.TextRange(9))
        onNodeWithTag(ToolbarTags.INSERT_TABLE_HERE).performScrollTo().performClick()
        onNodeWithTag(TableSizeTags.cell(1, 2)).performClick()
        onNodeWithTag(TableSizeTags.CONFIRM).performClick()
        waitUntil(timeoutMillis = 5000) { session.state.value.tables.size == 2 }
        val boxes = session.state.value.textBoxes.sortedBy { it.y }
        assertThat(boxes.map { it.text.plainText }).containsExactly("eins\nzwei", "drei").inOrder()
        val inserted = session.state.value.tables.minBy { it.y }
        assertThat(inserted.y).isGreaterThan(boxes[0].bounds.bottom)
        assertThat(boxes[1].y).isGreaterThan(inserted.bounds.bottom)
        assertThat(session.state.value.tables.maxBy { it.y }.y).isGreaterThan(boxes[1].bounds.bottom)
        runBlocking { container.closeAll() }
    }
}
