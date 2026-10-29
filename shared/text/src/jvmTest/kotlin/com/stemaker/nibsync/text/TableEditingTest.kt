package com.stemaker.nibsync.text

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.CellKey
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TableElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class TableEditingTest {
    private fun editing(): Pair<DocumentSession, TableEditing> {
        val session = DocumentSession(DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)), DeviceId("a"), { }, CoroutineScope(Dispatchers.Unconfined), clock = { 0L })
        return session to TableEditing(session)
    }

    private fun TableEditing.type(text: String) = editing.value!!.controller.setContent(RichText.plain(text))

    private fun DocumentSession.table(): TableElement = state.value.tables.single()

    private fun TableElement.textAt(row: Int, col: Int) = cell(CellKey(rows[row].rowId, columns[col].colId)).text.plainText

    @Test
    fun typeAndTabThroughTheCellsTheLastTabAddsARow() {
        val (s, t) = editing()
        val table = t.insert(20f, 30f, rows = 2, columns = 2, header = false, pageWidthMm = 210f)
        assertThat(table.widthMm).isWithin(0.01f).of(TableElement.DEFAULT_WIDTH_MM)
        t.type("A")
        t.move(CellMove.NEXT)
        t.type("B")
        t.move(CellMove.DOWN)
        t.type("D")
        t.move(CellMove.PREVIOUS)
        t.type("C")
        t.move(CellMove.NEXT)
        t.move(CellMove.NEXT) // last cell: a new row
        t.type("E")
        t.finish()
        val done = s.table()
        assertThat(done.rows).hasSize(3)
        assertThat(listOf(done.textAt(0, 0), done.textAt(0, 1), done.textAt(1, 0), done.textAt(1, 1), done.textAt(2, 0)))
            .containsExactly("A", "B", "C", "D", "E").inOrder()
        assertThat(t.editing.value).isNull()
    }

    @Test
    fun rowsAndColumnsAreInsertedAndDeletedAroundTheCell() {
        val (s, t) = editing()
        t.insert(20f, 30f, rows = 2, columns = 2, header = true, pageWidthMm = 210f)
        t.type("Kopf")
        t.insertRow(below = true)
        assertThat(s.table().rows).hasSize(3)
        assertThat(s.table().textAt(0, 0)).isEqualTo("Kopf")
        t.insertColumn(right = false)
        // The table keeps its width.
        assertThat(s.table().columns).hasSize(3)
        assertThat(s.table().widthMm).isWithin(0.1f).of(TableElement.DEFAULT_WIDTH_MM)
        assertThat(s.table().textAt(0, 1)).isEqualTo("Kopf")
        t.deleteColumn()
        assertThat(s.table().columns).hasSize(2)
        t.toggleHeader()
        assertThat(s.table().header).isFalse()
        // Each change is one undo step.
        s.undo()
        assertThat(s.table().header).isTrue()
    }

    @Test
    fun joiningTakesTheOtherTextAndIsUndoneInOneStep() {
        val (s, t) = editing()
        t.insert(20f, 30f, rows = 2, columns = 2, header = false, pageWidthMm = 210f)
        t.move(CellMove.NEXT)
        t.type("rechts")
        t.move(CellMove.PREVIOUS)
        t.type("links")
        t.join(right = true)
        val joined = s.table()
        val area = joined.geometry.merged.single()
        assertThat(area.colSpan).isEqualTo(2)
        assertThat(joined.textAt(0, 0)).isEqualTo("links\nrechts")
        assertThat(joined.textAt(0, 1)).isEmpty()
        // Tab skips the covered cell.
        t.move(CellMove.NEXT)
        assertThat(t.editing.value!!.cell).isEqualTo(CellKey(joined.rows[1].rowId, joined.columns[0].colId))
        // One undo brings back both cells and the structure.
        s.undo()
        val back = s.table()
        assertThat(back.geometry.merged).isEmpty()
        assertThat(back.textAt(0, 0)).isEqualTo("links")
        assertThat(back.textAt(0, 1)).isEqualTo("rechts")
    }

    @Test
    fun deletingACornerRowShrinksTheJoinedAreaAndSplitUndoesIt() {
        val (s, t) = editing()
        t.insert(20f, 30f, rows = 3, columns = 1, header = false, pageWidthMm = 210f)
        t.join(right = false)
        t.join(right = false)
        assertThat(s.table().geometry.merged.single().rowSpan).isEqualTo(3)
        t.split()
        assertThat(s.table().geometry.merged).isEmpty()
        t.join(right = false)
        t.move(CellMove.DOWN)
        // In the third row: delete it; the area loses nothing it still has.
        t.deleteRow()
        assertThat(s.table().rows).hasSize(2)
        assertThat(s.table().geometry.merged.single().rowSpan).isEqualTo(2)
    }

    @Test
    fun aWiderColumnMeasuresItsTextAnew() {
        val (s, t) = editing()
        // Fake layout: the narrower the cell, the more lines.
        t.measureText = { _, width -> 400f / width }
        t.insert(20f, 30f, rows = 1, columns = 2, header = false, pageWidthMm = 210f)
        t.type("Text")
        t.finish()
        val before = s.table()
        val first = before.columns.first()
        val wide = t.setColumnWidth(before, first.colId, first.widthMm * 2)
        val k = CellKey(wide.rows[0].rowId, first.colId)
        assertThat(wide.cell(k).heightMm).isLessThan(before.cell(k).heightMm)
    }
}
