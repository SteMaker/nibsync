package com.stemaker.nibsync.model

import com.stemaker.nibsync.model.geometry.Rect

/** A column of a table: a stable [colId] (cells refer to it) and its width. */
data class TableColumn(val colId: StrokeId, val widthMm: Float) {
    init {
        require(widthMm > 0f) { "A column needs a positive width" }
    }
}

/** A row of a table: a stable [rowId] (cells refer to it). Its height follows the content. */
data class TableRow(val rowId: StrokeId)

/** Where a cell is: its row and column ids, which survive inserting and deleting others. */
data class CellKey(val rowId: StrokeId, val colId: StrokeId)

/** What a cell holds: formatted text and its laid-out height (mm) for platform-neutral sizes. */
data class TableCell(val text: RichText, val heightMm: Float) {
    val isEmpty: Boolean get() = text.isBlank

    companion object {
        val EMPTY = TableCell(RichText.EMPTY, 0f)
    }
}

/**
 * Cells joined into one: the rectangle from the cell ([fromRowId], [fromColId]) to the cell
 * ([toRowId], [toColId]). Given by its corners, so a row or column inserted inside makes it
 * grow. Its content is the top left cell's; the cells it covers are not shown.
 */
data class CellMerge(val fromRowId: StrokeId, val fromColId: StrokeId, val toRowId: StrokeId, val toColId: StrokeId)

/**
 * A table on the page with its top left at ([x], [y]) mm.
 *
 * [tableId] stays the same when the table is moved or its structure changes (remove + add with
 * a fresh [id], like a to-do's task id), so cell edits from other devices (a register per
 * cell, see `DocumentOp.SetTableCells`) still find their cell; a copy is a new table. Rows grow
 * with their content: a row is as high as its highest cell ([TableCell.heightMm] plus padding).
 */
data class TableElement(
    override val id: StrokeId,
    val tableId: StrokeId,
    val x: Float,
    val y: Float,
    val columns: List<TableColumn>,
    val rows: List<TableRow>,
    /** The first row is a header row (bold, tinted). */
    val header: Boolean,
    val cells: Map<CellKey, TableCell>,
    val merges: List<CellMerge>,
    override val createdAtEpochMs: Long,
    override val author: DeviceId,
) : PageElement {
    init {
        require(columns.isNotEmpty() && rows.isNotEmpty()) { "A table needs at least one row and one column" }
    }

    override val layer: Layer get() = Layer.TEXT

    /** Positions of rows, columns and (joined) cells. */
    val geometry: TableGeometry by lazy { TableGeometry(this) }

    val widthMm: Float get() = geometry.widthMm
    val heightMm: Float get() = geometry.heightMm

    override val bounds: Rect get() = Rect(x, y, x + widthMm, y + heightMm)

    fun cell(key: CellKey): TableCell = cells[key] ?: TableCell.EMPTY

    companion object {
        /** Space between a cell's border and its text. */
        const val CELL_PADDING_MM = 1.5f

        /** A row with an empty cell: one line of normal text. */
        const val MIN_ROW_MM = 8f
        const val MIN_COLUMN_MM = 8f
        const val DEFAULT_WIDTH_MM = 160f
    }
}

/**
 * Layout of a [TableElement], relative to its top left corner (mm). Joined cells are one
 * rectangle; a joined cell taller than its rows makes its last row taller.
 */
class TableGeometry(private val table: TableElement) {
    /** A shown cell: the top left cell of a joined area or a single cell, with its span. */
    data class Area(val row: Int, val col: Int, val lastRow: Int, val lastCol: Int) {
        val rowSpan: Int get() = lastRow - row + 1
        val colSpan: Int get() = lastCol - col + 1
        fun contains(r: Int, c: Int) = r in row..lastRow && c in col..lastCol
    }

    private val rowIndex = table.rows.withIndex().associate { it.value.rowId to it.index }
    private val colIndex = table.columns.withIndex().associate { it.value.colId to it.index }

    /**
     * Joined areas in index form: corners that no longer exist are dropped; one overlapping an
     * earlier area is ignored; a single cell is no area.
     */
    val merged: List<Area> = buildList {
        for (m in table.merges) {
            val r0 = rowIndex[m.fromRowId] ?: continue
            val r1 = rowIndex[m.toRowId] ?: continue
            val c0 = colIndex[m.fromColId] ?: continue
            val c1 = colIndex[m.toColId] ?: continue
            val a = Area(minOf(r0, r1), minOf(c0, c1), maxOf(r0, r1), maxOf(c0, c1))
            if (a.rowSpan * a.colSpan < 2) continue
            if (any { o -> a.row <= o.lastRow && o.row <= a.lastRow && a.col <= o.lastCol && o.col <= a.lastCol }) continue
            add(a)
        }
    }

    /** The shown cell that covers row [r], column [c]. */
    fun areaAt(r: Int, c: Int): Area = merged.firstOrNull { it.contains(r, c) } ?: Area(r, c, r, c)

    /** Every shown cell, row by row, left to right (the order of Tab). */
    val areas: List<Area> = buildList {
        for (r in table.rows.indices) for (c in table.columns.indices) {
            val a = areaAt(r, c)
            if (a.row == r && a.col == c) add(a)
        }
    }

    fun key(a: Area) = CellKey(table.rows[a.row].rowId, table.columns[a.col].colId)

    /** Left edge of each column and the right edge of the last one (mm). */
    val columnX: List<Float> = table.columns.runningFold(0f) { x, c -> x + c.widthMm }

    val widthMm: Float get() = columnX.last()

    private fun contentHeight(a: Area) = table.cell(key(a)).heightMm + 2 * TableElement.CELL_PADDING_MM

    /** Height of each row (mm). */
    val rowHeights: List<Float> = run {
        val h = FloatArray(table.rows.size) { TableElement.MIN_ROW_MM }
        // Single-row cells set their row's height, then taller joined cells stretch their last row.
        for (a in areas) if (a.rowSpan == 1) h[a.row] = maxOf(h[a.row], contentHeight(a))
        for (a in areas) if (a.rowSpan > 1) {
            val have = (a.row..a.lastRow).sumOf { h[it].toDouble() }.toFloat()
            val need = contentHeight(a)
            if (need > have) h[a.lastRow] += need - have
        }
        h.toList()
    }

    /** Top edge of each row and the bottom edge of the last one (mm). */
    val rowY: List<Float> = rowHeights.runningFold(0f) { y, h -> y + h }

    val heightMm: Float get() = rowY.last()

    /** Where [a] is, relative to the table's top left (mm). */
    fun rect(a: Area): Rect = Rect(columnX[a.col], rowY[a.row], columnX[a.lastCol + 1], rowY[a.lastRow + 1])

    /** The shown cell at ([x], [y]) mm relative to the table's top left, or null outside. */
    fun areaAtPoint(x: Float, y: Float): Area? {
        if (x < 0f || y < 0f || x > widthMm || y > heightMm) return null
        val c = (columnX.indexOfLast { it <= x }).coerceIn(0, table.columns.lastIndex)
        val r = (rowY.indexOfLast { it <= y }).coerceIn(0, table.rows.lastIndex)
        return areaAt(r, c)
    }

    /** The shown cell whose content is at [key] (the top left of a joined area), or null. */
    fun areaOf(key: CellKey): Area? {
        val r = rowIndex[key.rowId] ?: return null
        val c = colIndex[key.colId] ?: return null
        return areaAt(r, c)
    }
}

/** A table before it gets an id; [tableId] is kept when an existing table changes, null for a new one. */
data class TableDraft(
    val x: Float,
    val y: Float,
    val columns: List<TableColumn>,
    val rows: List<TableRow>,
    val header: Boolean,
    val cells: Map<CellKey, TableCell>,
    val merges: List<CellMerge>,
    val tableId: StrokeId? = null,
) : ElementDraft {
    override fun translated(dx: Float, dy: Float): TableDraft = copy(x = x + dx, y = y + dy)
}
