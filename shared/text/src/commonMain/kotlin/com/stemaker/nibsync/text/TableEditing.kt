package com.stemaker.nibsync.text

import com.stemaker.nibsync.document.CellUpdate
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.model.CellKey
import com.stemaker.nibsync.model.CellMerge
import com.stemaker.nibsync.model.IdGenerator
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TableCell
import com.stemaker.nibsync.model.TableColumn
import com.stemaker.nibsync.model.TableDraft
import com.stemaker.nibsync.model.TableElement
import com.stemaker.nibsync.model.TableGeometry
import com.stemaker.nibsync.model.TableRow
import com.stemaker.nibsync.model.TextMark
import com.stemaker.nibsync.model.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The table cell being edited: the table (by [tableId]), the [cell] and its text in [controller]. */
data class TableEdit(
    val key: Long,
    val tableId: StrokeId,
    val cell: CellKey,
    val controller: TextEditController,
)

/** Where Tab, Shift+Tab and the arrow keys at the text's edge go. */
enum class CellMove { NEXT, PREVIOUS, UP, DOWN }

/** How the text of a table cell is shown: header cells bold. */
object TableText {
    fun shown(table: TableElement, area: TableGeometry.Area): RichText {
        val text = table.cell(table.geometry.key(area)).text
        return if (table.header && area.row == 0) bold(text) else text
    }

    private fun bold(text: RichText) = RichText(
        text.paragraphs.map { p ->
            if (p.text.isEmpty() || p.marks.any { it.style == MarkStyle.Bold && it.start == 0 && it.end == p.text.length }) p
            else p.copy(marks = p.marks.filter { it.style != MarkStyle.Bold } + TextMark(0, p.text.length, MarkStyle.Bold))
        },
    )

    /** Where a cell's text goes: inside its rectangle less the padding, relative to the table. */
    fun contentRect(table: TableElement, area: TableGeometry.Area): Rect {
        val r = table.geometry.rect(area)
        val p = TableElement.CELL_PADDING_MM
        return Rect(r.left + p, r.top + p, r.right - p, r.bottom - p)
    }
}

/**
 * Editing tables of a document, the same way on every platform: one cell at a time is open in
 * an editor; its text is stored as a cell change (a register per cell, so other devices' edits
 * of other cells are kept). Rows, columns, widths and joined cells change the table element
 * (same table id), each in one undo step.
 */
class TableEditing(private val session: DocumentSession, private val ids: IdGenerator = IdGenerator()) {
    private val _editing = MutableStateFlow<TableEdit?>(null)

    /** The cell being edited, or null. */
    val editing: StateFlow<TableEdit?> = _editing.asStateFlow()

    /** Height in mm of a text in a cell of the given width; the platform sets its text layout. */
    var measureText: (RichText, Float) -> Float = { text, _ -> text.paragraphs.size * RichText.DEFAULT_SIZE_PT * RichText.MM_PER_PT * 1.4f }

    private var key = 0L

    /** The edited table as it is now, or null. */
    fun table(): TableElement? = _editing.value?.let { e -> tableOf(e.tableId) }

    private fun tableOf(tableId: StrokeId) = session.state.value.tables.firstOrNull { it.tableId == tableId }

    /**
     * Adds a table of [rows] x [columns] with its top left at ([x], [y]) mm, as wide as
     * [TableElement.DEFAULT_WIDTH_MM] or the page allows, and opens its first cell.
     */
    fun insert(x: Float, y: Float, rows: Int, columns: Int, header: Boolean, pageWidthMm: Float): TableElement {
        finish()
        val width = minOf(TableElement.DEFAULT_WIDTH_MM, pageWidthMm - x - MARGIN_MM).coerceAtLeast(columns * TableElement.MIN_COLUMN_MM)
        val draft = TableDraft(
            x = maxOf(0f, minOf(x, pageWidthMm - width)),
            y = maxOf(0f, y),
            columns = List(columns) { TableColumn(ids.newStrokeId(), width / columns) },
            rows = List(rows) { TableRow(ids.newStrokeId()) },
            header = header,
            cells = emptyMap(),
            merges = emptyList(),
        )
        val table = session.replaceElements(emptySet(), listOf(draft)).single() as TableElement
        open(table, CellKey(table.rows.first().rowId, table.columns.first().colId))
        return table
    }

    /** Starts editing [cell] of [table] (a covered cell opens its joined area's). */
    fun open(table: TableElement, cell: CellKey) {
        val current = _editing.value
        if (current != null && current.tableId == table.tableId && current.cell == cell) return
        save()
        val area = table.geometry.areaOf(cell) ?: return
        val anchor = table.geometry.key(area)
        _editing.value = TableEdit(++key, table.tableId, anchor, TextEditController.create(TableText.shown(table, area)))
    }

    /** Stores the edited cell if it changed. Called while typing, when leaving the cell and when done. */
    fun save() {
        val e = _editing.value ?: return
        val table = tableOf(e.tableId) ?: return
        val area = table.geometry.areaOf(e.cell) ?: return
        val text = e.controller.text
        val cell = TableCell(text, if (text.isBlank) 0f else measureText(text, TableText.contentRect(table, area).width))
        session.setTableCells(table.tableId, listOf(CellUpdate(e.cell, cell)))
    }

    /** Ends editing. Returns false if nothing was being edited. */
    fun finish(): Boolean {
        if (_editing.value == null) return false
        save()
        _editing.value = null
        return true
    }

    /** Goes to the next, previous, upper or lower cell; Tab in the last cell adds a row. */
    fun move(direction: CellMove): Boolean {
        val e = _editing.value ?: return false
        val table = tableOf(e.tableId) ?: return false
        val g = table.geometry
        val area = g.areaOf(e.cell) ?: return false
        val target = when (direction) {
            CellMove.NEXT -> g.areas.getOrNull(g.areas.indexOf(area) + 1)
            CellMove.PREVIOUS -> g.areas.getOrNull(g.areas.indexOf(area) - 1)
            CellMove.UP -> if (area.row > 0) g.areaAt(area.row - 1, area.col) else null
            CellMove.DOWN -> if (area.lastRow < table.rows.lastIndex) g.areaAt(area.lastRow + 1, area.col) else null
        }
        if (target == null) {
            if (direction != CellMove.NEXT) return false
            save()
            val grown = insertRow(below = true) ?: return false
            open(grown, CellKey(grown.rows.last().rowId, grown.columns.first().colId))
            return true
        }
        save()
        val fresh = tableOf(e.tableId) ?: return false
        open(fresh, g.key(target))
        return true
    }

    // ---- structure --------------------------------------------------------------------------

    /** Adds a row above or below the edited cell's (joined: its whole span). */
    fun insertRow(below: Boolean): TableElement? = change { t, a ->
        val at = if (below) a.lastRow + 1 else a.row
        t.copy(rows = t.rows.toMutableList().apply { add(at, TableRow(ids.newStrokeId())) })
    }

    /** Adds a column left or right of the edited cell's; the table keeps its width. */
    fun insertColumn(right: Boolean): TableElement? = change { t, a ->
        val at = if (right) a.lastCol + 1 else a.col
        val width = t.widthMm / (t.columns.size + 1)
        val scale = (t.widthMm - width) / t.widthMm
        val columns = t.columns.map { it.copy(widthMm = maxOf(TableElement.MIN_COLUMN_MM, it.widthMm * scale)) }.toMutableList()
        columns.add(at, TableColumn(ids.newStrokeId(), width))
        t.copy(columns = columns)
    }

    /** Deletes the edited cell's row(s); the last row deletes the table. */
    fun deleteRow(): TableElement? {
        val t = table() ?: return null
        val a = t.geometry.areaOf(_editing.value!!.cell) ?: return null
        if (t.rows.size == a.rowSpan) return deleteTable()
        return change(moveTo = { n -> CellKey(n.rows[minOf(a.row, n.rows.lastIndex)].rowId, n.columns[a.col].colId) }) { tb, _ ->
            tb.copy(rows = tb.rows.filterIndexed { i, _ -> i !in a.row..a.lastRow })
        }
    }

    /** Deletes the edited cell's column(s); the last column deletes the table. */
    fun deleteColumn(): TableElement? {
        val t = table() ?: return null
        val a = t.geometry.areaOf(_editing.value!!.cell) ?: return null
        if (t.columns.size == a.colSpan) return deleteTable()
        return change(moveTo = { n -> CellKey(n.rows[a.row].rowId, n.columns[minOf(a.col, n.columns.lastIndex)].colId) }) { tb, _ ->
            tb.copy(columns = tb.columns.filterIndexed { i, _ -> i !in a.col..a.lastCol })
        }
    }

    /** Switches the header row on or off. */
    fun toggleHeader(): TableElement? = change { t, _ -> t.copy(header = !t.header) }

    /**
     * Joins the edited cell with the cell to its right ([right]) or below. Only works when the
     * result is a rectangle; the other cell's text is added to this one's.
     */
    fun join(right: Boolean): TableElement? {
        val e = _editing.value ?: return null
        val t = table() ?: return null
        val g = t.geometry
        val a = g.areaOf(e.cell) ?: return null
        val other = if (right) {
            if (a.lastCol == t.columns.lastIndex) return null
            g.areaAt(a.row, a.lastCol + 1).takeIf { it.row == a.row && it.lastRow == a.lastRow }
        } else {
            if (a.lastRow == t.rows.lastIndex) return null
            g.areaAt(a.lastRow + 1, a.col).takeIf { it.col == a.col && it.lastCol == a.lastCol }
        } ?: return null
        save()
        val now = tableOf(e.tableId) ?: return null
        val otherKey = g.key(other)
        val joined = CellMerge(
            t.rows[a.row].rowId, t.columns[a.col].colId,
            t.rows[maxOf(a.lastRow, other.lastRow)].rowId, t.columns[maxOf(a.lastCol, other.lastCol)].colId,
        )
        val merges = now.merges.filter { m -> !overlaps(now, m, a) && !overlaps(now, m, other) } + joined
        // The other cell's text goes into this one; the other cell is emptied.
        val mine = now.cell(e.cell).text
        val theirs = now.cell(otherKey).text
        val text = if (theirs.isBlank) mine else if (mine.isBlank) theirs else RichText(mine.paragraphs + theirs.paragraphs)
        val structure = now.copy(merges = merges)
        val area = structure.geometry.areaOf(e.cell) ?: return null
        val cells = listOf(
            CellUpdate(e.cell, TableCell(text, if (text.isBlank) 0f else measureText(text, TableText.contentRect(structure, area).width))),
            CellUpdate(otherKey, TableCell.EMPTY),
        )
        val result = session.replaceTable(now.id, structure.toDraft(), cells)
        reopen(result, e.cell)
        return result
    }

    /** Splits the joined area of the edited cell into single cells again. */
    fun split(): TableElement? = change { t, a ->
        t.copy(merges = t.merges.filter { m -> !overlaps(t, m, a) })
    }

    /** Sets the width of column [colId]; texts of its cells wrap anew. */
    fun setColumnWidth(table: TableElement, colId: StrokeId, widthMm: Float): TableElement {
        val w = maxOf(TableElement.MIN_COLUMN_MM, widthMm)
        val changed = table.copy(columns = table.columns.map { if (it.colId == colId) it.copy(widthMm = w) else it })
        return replace(table, changed)
    }

    /** Scales the table to [widthMm] (all columns in proportion); texts wrap anew. */
    fun resize(table: TableElement, widthMm: Float): TableElement {
        val minWidth = table.columns.size * TableElement.MIN_COLUMN_MM
        val scale = maxOf(minWidth, widthMm) / table.widthMm
        return replace(table, table.copy(columns = table.columns.map { it.copy(widthMm = maxOf(TableElement.MIN_COLUMN_MM, it.widthMm * scale)) }))
    }

    /** Deletes the edited table. */
    fun deleteTable(): TableElement? {
        val t = table() ?: return null
        _editing.value = null
        session.removeStrokes(listOf(t.id))
        return null
    }

    /**
     * Applies a structure change of the edited table in one undo step: the text being typed is
     * stored first, heights of cells whose width changed are measured anew, and editing goes on
     * in the same cell (or the one [moveTo] picks if it is gone).
     */
    private fun change(moveTo: ((TableElement) -> CellKey)? = null, transform: (TableElement, TableGeometry.Area) -> TableElement): TableElement? {
        val e = _editing.value ?: return null
        save()
        val t = tableOf(e.tableId) ?: return null
        val a = t.geometry.areaOf(e.cell) ?: return null
        val changed = transform(t, a).let { it.copy(merges = keptMerges(t, it)) }
        val result = replace(t, changed)
        reopen(result, if (result.geometry.areaOf(e.cell) != null) e.cell else moveTo?.invoke(result) ?: return result)
        return result
    }

    private fun replace(old: TableElement, changed: TableElement): TableElement =
        session.replaceTable(old.id, changed.toDraft(), remeasured(changed))

    /** Opens [cell] in the changed table with a fresh editor (the text may have moved or grown). */
    private fun reopen(table: TableElement, cell: CellKey) {
        val area = table.geometry.areaOf(cell) ?: return
        val anchor = table.geometry.key(area)
        _editing.value = TableEdit(++key, table.tableId, anchor, TextEditController.create(TableText.shown(table, area)))
    }

    /** Cells whose height changes in [table] (other width or header style), measured anew. */
    private fun remeasured(table: TableElement): List<CellUpdate> = table.geometry.areas.mapNotNull { area ->
        val k = table.geometry.key(area)
        val cell = table.cells[k] ?: return@mapNotNull null
        if (cell.isEmpty) return@mapNotNull null
        val h = measureText(TableText.shown(table, area), TableText.contentRect(table, area).width)
        if (kotlin.math.abs(h - cell.heightMm) < 0.05f) null else CellUpdate(k, cell.copy(heightMm = h))
    }

    /**
     * Joined areas after rows or columns of [before] went away in [after]: an area whose corner
     * is gone shrinks to the rows and columns that are left; one cell left is no area.
     */
    private fun keptMerges(before: TableElement, after: TableElement): List<CellMerge> {
        val rows = after.rows.map { it.rowId }.toSet()
        val cols = after.columns.map { it.colId }.toSet()
        return after.merges.mapNotNull { m ->
            val r = span(before.rows.map { it.rowId }, m.fromRowId, m.toRowId).filter { it in rows }
            val c = span(before.columns.map { it.colId }, m.fromColId, m.toColId).filter { it in cols }
            if (r.isEmpty() || c.isEmpty() || r.size * c.size < 2) null else CellMerge(r.first(), c.first(), r.last(), c.last())
        }
    }

    private fun span(order: List<StrokeId>, from: StrokeId, to: StrokeId): List<StrokeId> {
        val i = order.indexOf(from)
        val j = order.indexOf(to)
        if (i < 0 || j < 0) return listOfNotNull(from.takeIf { i >= 0 }, to.takeIf { j >= 0 })
        return order.subList(minOf(i, j), maxOf(i, j) + 1)
    }

    private fun overlaps(t: TableElement, m: CellMerge, a: TableGeometry.Area): Boolean {
        val g = t.geometry
        val r0 = t.rows.indexOfFirst { it.rowId == m.fromRowId }
        val r1 = t.rows.indexOfFirst { it.rowId == m.toRowId }
        val c0 = t.columns.indexOfFirst { it.colId == m.fromColId }
        val c1 = t.columns.indexOfFirst { it.colId == m.toColId }
        if (r0 < 0 || r1 < 0 || c0 < 0 || c1 < 0) return false
        val area = TableGeometry.Area(minOf(r0, r1), minOf(c0, c1), maxOf(r0, r1), maxOf(c0, c1))
        return g.merged.contains(area) && area.row <= a.lastRow && a.row <= area.lastRow && area.col <= a.lastCol && a.col <= area.lastCol
    }

    private fun TableElement.toDraft() = TableDraft(x, y, columns, rows, header, cells, merges, tableId)

    private companion object {
        const val MARGIN_MM = 5f
    }
}
