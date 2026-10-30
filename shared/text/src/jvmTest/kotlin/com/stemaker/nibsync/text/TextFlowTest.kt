package com.stemaker.nibsync.text

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TextMark
import com.stemaker.nibsync.model.TextParagraph
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

/** Text flow: what is below a growing or shrinking text box or table moves along. */
class TextFlowTest {
    // Every paragraph one 6 mm line, whatever the width.
    private val lines: (RichText, Float) -> Float = { text, _ -> text.paragraphs.size * 6f }

    private fun setup(): Triple<DocumentSession, TextBoxEditing, TableEditing> {
        val session = DocumentSession(DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)), DeviceId("a"), { }, CoroutineScope(Dispatchers.Unconfined), clock = { 0L })
        val text = TextBoxEditing(session).also { it.measureText = lines }
        val tables = TableEditing(session).also { it.measureText = lines }
        return Triple(session, text, tables)
    }

    @Test
    fun typingMoreLinesPushesTheTableBelowDownAndDeletingPullsItUp() {
        val (s, text, tables) = setup()
        text.startNew(20f, 20f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain("eins"))
        text.finish()
        tables.insert(20f, 40f, rows = 2, columns = 2, header = false, pageWidthMm = 210f)
        tables.finish()
        val tableTop = s.state.value.tables.single().y
        // Two more lines in the box: the table moves 12 mm down.
        text.open(s.state.value.textBoxes.single())
        text.editing.value!!.controller.setContent(RichText.plain("eins\nzwei\ndrei"))
        assertThat(text.flowPreview()!!.dy).isWithin(0.01f).of(12f)
        text.save()
        assertThat(s.state.value.tables.single().y).isWithin(0.05f).of(tableTop + 12f)
        // Back to one line: up again, the gap is as before.
        text.editing.value!!.controller.setContent(RichText.plain("eins"))
        text.finish()
        assertThat(s.state.value.tables.single().y).isWithin(0.05f).of(tableTop)
        // Undo goes back step by step, box and table together.
        s.undo()
        assertThat(s.state.value.tables.single().y).isWithin(0.05f).of(tableTop + 12f)
        assertThat(s.state.value.textBoxes.single().heightMm).isWithin(0.05f).of(18f)
    }

    @Test
    fun aTableRowPushesTheTextBelowDown() {
        val (s, text, tables) = setup()
        tables.insert(20f, 20f, rows = 1, columns = 2, header = false, pageWidthMm = 210f)
        text.startNew(20f, 40f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain("darunter"))
        text.finish()
        val boxTop = s.state.value.textBoxes.single().y
        val table = s.state.value.tables.single()
        tables.open(table, com.stemaker.nibsync.model.CellKey(table.rows[0].rowId, table.columns[0].colId))
        tables.insertRow(below = true)
        assertThat(s.state.value.textBoxes.single().y).isWithin(0.05f).of(boxTop + com.stemaker.nibsync.model.TableElement.MIN_ROW_MM)
    }

    @Test
    fun aNewTableOnlyPushesWhatItWouldCover() {
        val (s, text, tables) = setup()
        text.startNew(20f, 100f, 120f)
        text.editing.value!!.controller.setContent(RichText.plain("weit unten"))
        text.finish()
        // Far above: nothing moves.
        tables.insert(20f, 20f, rows = 2, columns = 2, header = false, pageWidthMm = 210f)
        assertThat(s.state.value.textBoxes.single().y).isWithin(0.05f).of(100f)
        tables.finish()
        // Right on top of it: it moves below the table.
        val second = tables.insert(20f, 95f, rows = 2, columns = 2, header = false, pageWidthMm = 210f)
        assertThat(s.state.value.textBoxes.single().y).isAtLeast(second.bounds.bottom)
    }

    @Test
    fun insertingATableAtTheCursorSplitsTheBox() {
        val (s, text, tables) = setup()
        text.startNew(20f, 20f, 120f)
        val controller = text.editing.value!!.controller
        controller.setContent(RichText(listOf(TextParagraph("vorher fett", marks = listOf(TextMark(7, 11, MarkStyle.Bold))), TextParagraph("nachher"))))
        text.save()
        tables.insert(20f, 80f, rows = 1, columns = 1, header = false, pageWidthMm = 210f)
        tables.finish()
        val farTable = s.state.value.tables.single()
        text.open(s.state.value.textBoxes.single())
        // Cursor after "vorher fe".
        text.editing.value!!.controller.state.selection = TextRange(9)
        val table = tables.insertInto(text, rows = 2, columns = 3, header = true)!!
        val boxes = s.state.value.textBoxes.sortedBy { it.y }
        assertThat(boxes.map { it.text.plainText }).containsExactly("vorher fe", "tt\nnachher").inOrder()
        assertThat(boxes[1].text.paragraphs[0].marks).containsExactly(TextMark(0, 2, MarkStyle.Bold))
        assertThat(table.y).isGreaterThan(boxes[0].bounds.bottom)
        assertThat(boxes[1].y).isGreaterThan(table.bounds.bottom)
        assertThat(table.widthMm).isWithin(0.1f).of(120f)
        // The table further down moved below the new stack.
        val moved = s.state.value.tables.first { it.tableId == farTable.tableId }
        assertThat(moved.y).isGreaterThan(farTable.y)
        assertThat(tables.editing.value).isNotNull()
        // One undo: the single box again, the far table back in place.
        tables.finish()
        s.undo()
        assertThat(s.state.value.textBoxes.single().text.plainText).isEqualTo("vorher fett\nnachher")
        assertThat(s.state.value.tables.single().y).isWithin(0.05f).of(farTable.y)
    }
}
