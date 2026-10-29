package com.stemaker.nibsync.document

import com.stemaker.nibsync.document.select.ClipContent
import com.stemaker.nibsync.model.CellKey
import com.stemaker.nibsync.model.CellMerge
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TableCell
import com.stemaker.nibsync.model.TableColumn
import com.stemaker.nibsync.model.TableDraft
import com.stemaker.nibsync.model.TableElement
import com.stemaker.nibsync.model.TableRow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableTest {
    private val written = mutableListOf<DocumentOp>()

    private fun TestScope.session() =
        DocumentSession(DocumentState.empty(META), DEV_A, { written += it }, backgroundScope, clock = { 1234L })

    private val r1 = StrokeId("r1")
    private val r2 = StrokeId("r2")
    private val c1 = StrokeId("c1")
    private val c2 = StrokeId("c2")

    private fun text(s: String, h: Float = 5f) = TableCell(RichText.plain(s), h)

    private fun draft() = TableDraft(
        10f, 20f,
        listOf(TableColumn(c1, 40f), TableColumn(c2, 60f)),
        listOf(TableRow(r1), TableRow(r2)),
        header = true,
        cells = mapOf(CellKey(r1, c1) to text("Name")),
        merges = emptyList(),
    )

    private fun table(s: DocumentSession) = s.state.value.tables.single()

    @Test
    fun cellsAreSetPerCellAndUndoneOneStepAtATime() = runTest {
        val s = session()
        s.replaceElements(emptySet(), listOf(draft()))
        val t = table(s)
        assertEquals(100f, t.widthMm, 0.01f)
        s.setTableCells(t.tableId, listOf(CellUpdate(CellKey(r2, c2), text("42"))))
        assertEquals("42", table(s).cell(CellKey(r2, c2)).text.plainText)
        assertEquals("Name", table(s).cell(CellKey(r1, c1)).text.plainText)
        // The same content again changes nothing (no extra undo step).
        val ops = written.size
        s.setTableCells(t.tableId, listOf(CellUpdate(CellKey(r2, c2), text("42"))))
        assertEquals(ops, written.size)
        s.undo()
        assertTrue(table(s).cell(CellKey(r2, c2)).isEmpty)
        s.redo()
        assertEquals("42", table(s).cell(CellKey(r2, c2)).text.plainText)
    }

    @Test
    fun cellRegisterDoesNotDependOnOpOrderAndSurvivesAStructureChange() = runTest {
        val s = session()
        s.replaceElements(emptySet(), listOf(draft()))
        val t = table(s)
        // Device B edits a cell while device A adds a row (remove + add, same table id).
        val edit = DocumentOp.SetTableCells(OpStamp(10, DEV_B), t.tableId, listOf(CellUpdate(CellKey(r1, c2), text("von B"))))
        val r3 = StrokeId("r3")
        val grown = t.copy(id = StrokeId("t2"), rows = t.rows + TableRow(r3))
        val structure = listOf(DocumentOp.RemoveStrokes(OpStamp(11, DEV_A), setOf(t.id)), DocumentOp.AddTables(OpStamp(12, DEV_A), listOf(grown)))
        val base = s.state.value
        val oneWay = base.applyAll(listOf(edit) + structure).tables.single()
        val otherWay = base.applyAll(structure + edit).tables.single()
        assertEquals(oneWay, otherWay)
        assertEquals(3, oneWay.rows.size)
        assertEquals("von B", oneWay.cell(CellKey(r1, c2)).text.plainText)
        // Two changes of the same cell: the later stamp wins in any order.
        val a = DocumentOp.SetTableCells(OpStamp(20, DEV_A), t.tableId, listOf(CellUpdate(CellKey(r2, c1), text("A"))))
        val b = DocumentOp.SetTableCells(OpStamp(21, DEV_B), t.tableId, listOf(CellUpdate(CellKey(r2, c1), text("B"))))
        assertEquals("B", base.applyAll(listOf(a, b)).tables.single().cell(CellKey(r2, c1)).text.plainText)
        assertEquals("B", base.applyAll(listOf(b, a)).tables.single().cell(CellKey(r2, c1)).text.plainText)
    }

    @Test
    fun cellsOfADeletedRowDoNotShow() = runTest {
        val s = session()
        s.replaceElements(emptySet(), listOf(draft()))
        val t = table(s)
        s.setTableCells(t.tableId, listOf(CellUpdate(CellKey(r2, c1), text("weg"))))
        val shrunk = s.replaceElements(setOf(t.id), listOf(table(s).copy(rows = listOf(TableRow(r1))).toDraftKeepingId())).single() as TableElement
        assertEquals(1, shrunk.rows.size)
        assertEquals(TableElement.MIN_ROW_MM.coerceAtLeast(5f + 2 * TableElement.CELL_PADDING_MM), table(s).heightMm, 0.01f)
        assertEquals(t.tableId, table(s).tableId)
    }

    @Test
    fun aCopyIsANewTableWithTheSameContent() = runTest {
        val s = session()
        s.replaceElements(emptySet(), listOf(draft()))
        val t = table(s)
        s.setTableCells(t.tableId, listOf(CellUpdate(CellKey(r2, c2), text("kopiert"))))
        val copy = s.replaceElements(emptySet(), ClipContent.of(listOf(table(s)))!!.placedAt(100f, 200f)).single() as TableElement
        assertNotEquals(t.tableId, copy.tableId)
        assertEquals("kopiert", copy.cell(CellKey(r2, c2)).text.plainText)
        // Editing the copy leaves the original alone.
        s.setTableCells(copy.tableId, listOf(CellUpdate(CellKey(r2, c2), text("anders"))))
        assertEquals("kopiert", s.state.value.tables.first { it.tableId == t.tableId }.cell(CellKey(r2, c2)).text.plainText)
    }

    @Test
    fun joinedCellsAreOneAreaThatGrowsWithInsertedRows() {
        val r3 = StrokeId("r3")
        val c3 = StrokeId("c3")
        fun table(rows: List<StrokeId>, merges: List<CellMerge>, cells: Map<CellKey, TableCell> = emptyMap()) = TableElement(
            StrokeId("t"), StrokeId("tt"), 0f, 0f,
            listOf(TableColumn(c1, 30f), TableColumn(c2, 30f), TableColumn(c3, 30f)),
            rows.map(::TableRow), false, cells, merges, 0, DEV_A,
        )
        // Rows 1-2 of columns 1-2 joined.
        val merge = CellMerge(r1, c1, r2, c2)
        val g = table(listOf(r1, r2), listOf(merge)).geometry
        assertEquals(listOf(0, 0, 1, 1), g.merged.single().let { listOf(it.row, it.col, it.lastRow, it.lastCol) })
        // Shown cells: the joined one, then column 3 of both rows.
        assertEquals(3, g.areas.size)
        assertEquals(g.merged.single(), g.areaAtPoint(45f, 12f))
        assertEquals(60f, g.rect(g.merged.single()).width, 0.01f)
        // A row inserted between the corners makes the area grow.
        val grown = table(listOf(r1, r3, r2), listOf(merge)).geometry
        assertEquals(3, grown.merged.single().rowSpan)
        // A corner row deleted: the area is gone.
        assertTrue(table(listOf(r1, r3), listOf(merge)).geometry.merged.isEmpty())
        // Tall content of a joined cell stretches its last row.
        val tall = table(listOf(r1, r2), listOf(merge), mapOf(CellKey(r1, c1) to TableCell(RichText.plain("x"), 30f))).geometry
        assertEquals(TableElement.MIN_ROW_MM, tall.rowHeights[0], 0.01f)
        assertEquals(30f + 2 * TableElement.CELL_PADDING_MM, tall.rowHeights.sum(), 0.01f)
        assertNull(tall.areaAtPoint(-1f, 5f))
    }

    private fun TableElement.toDraftKeepingId() = TableDraft(x, y, columns, rows, header, cells, merges, tableId)
}
