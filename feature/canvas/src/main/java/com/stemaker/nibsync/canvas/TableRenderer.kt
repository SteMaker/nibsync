package com.stemaker.nibsync.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.stemaker.nibsync.model.CellKey
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TableElement
import com.stemaker.nibsync.text.TableText
import com.stemaker.nibsync.text.TextBoxRenderer

/**
 * Draws tables in document coordinates (mm) like the PC's PagePainter: on white, a tinted
 * header row, the cells' texts and the grid.
 */
class TableRenderer(private val text: TextBoxRenderer) {
    private val paper = Paint().apply { color = Color.WHITE }
    private val header = Paint().apply { color = HEADER }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LINE
        style = Paint.Style.STROKE
        strokeWidth = LINE_MM
    }

    /** Draws [table]; the text of [hidden] is left out (it is being typed in an editor). */
    fun draw(canvas: Canvas, table: TableElement, hidden: CellKey?) {
        val g = table.geometry
        canvas.drawRect(table.x, table.y, table.x + g.widthMm, table.y + g.heightMm, paper)
        if (table.header) canvas.drawRect(table.x, table.y, table.x + g.widthMm, table.y + g.rowHeights[0], header)
        for (area in g.areas) {
            val key = g.key(area)
            if (key == hidden) continue
            val shown = TableText.shown(table, area)
            if (shown.isBlank) continue
            val r = TableText.contentRect(table, area)
            text.drawText(canvas, cellId(table.tableId, key), shown, table.x + r.left, table.y + r.top, r.width)
        }
        for (area in g.areas) {
            val r = g.rect(area)
            canvas.drawRect(table.x + r.left, table.y + r.top, table.x + r.right, table.y + r.bottom, line)
        }
    }

    private fun cellId(tableId: StrokeId, key: CellKey) = StrokeId("${tableId.value}/${key.rowId.value}/${key.colId.value}")

    private companion object {
        const val LINE = 0xff8a9099.toInt()
        const val HEADER = 0xffe9edf1.toInt()
        const val LINE_MM = 0.25f
    }
}
