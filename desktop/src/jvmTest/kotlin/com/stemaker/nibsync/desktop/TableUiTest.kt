package com.stemaker.nibsync.desktop

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

}
