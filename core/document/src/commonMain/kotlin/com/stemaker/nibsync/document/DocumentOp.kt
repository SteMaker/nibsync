package com.stemaker.nibsync.document

import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.FileElement
import com.stemaker.nibsync.model.LinkElement
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TodoItem
import com.stemaker.nibsync.model.TableElement
import com.stemaker.nibsync.model.CellKey
import com.stemaker.nibsync.model.TableCell

/**
 * Lamport timestamp of an operation. Unique per op (a device never reuses a counter value) and
 * totally ordered, so every device derives the same stroke z-order from the same set of ops.
 */
data class OpStamp(val counter: Long, val device: DeviceId) : Comparable<OpStamp> {
    override fun compareTo(other: OpStamp): Int =
        compareValuesBy(this, other, { it.counter }, { it.device.value })
}

/**
 * A change to a document. Documents are stored and synced as a set of ops; the visible state is
 * the result of applying all of them. Applying is idempotent and order-independent, see
 * [DocumentState.apply].
 */
sealed interface DocumentOp {
    val stamp: OpStamp

    data class AddStrokes(override val stamp: OpStamp, val strokes: List<Stroke>) : DocumentOp

    data class AddTextBoxes(override val stamp: OpStamp, val boxes: List<TextBox>) : DocumentOp

    /** Adds pictures and file links. Their files must be in the document's assets. */
    data class AddFiles(override val stamp: OpStamp, val elements: List<FileElement>) : DocumentOp

    data class AddTodos(override val stamp: OpStamp, val items: List<TodoItem>) : DocumentOp

    /** Adds anchors and links to notes. */
    data class AddLinks(override val stamp: OpStamp, val elements: List<LinkElement>) : DocumentOp

    /**
     * Ticks a to-do off ([done]) or reopens it, by [TodoItem.taskId]. Concurrent changes of the
     * same task: the one with the highest stamp wins.
     */
    data class SetTodoDone(override val stamp: OpStamp, val taskId: StrokeId, val done: Boolean) : DocumentOp

    /** Adds tables (structure and the cells' content at that time). */
    data class AddTables(override val stamp: OpStamp, val tables: List<TableElement>) : DocumentOp

    /**
     * Sets the content of cells of the table [tableId] (by [TableElement.tableId], so it applies
     * across moves and structure changes). Each cell is a register: for a cell, the change with
     * the highest stamp wins, so two devices editing different cells never conflict.
     */
    data class SetTableCells(override val stamp: OpStamp, val tableId: StrokeId, val cells: List<CellUpdate>) : DocumentOp

    /** Removes elements of any kind (strokes, text boxes) by id. */
    data class RemoveStrokes(override val stamp: OpStamp, val ids: Set<StrokeId>) : DocumentOp

    /** Changes the paper pattern. Concurrent changes: the one with the highest stamp wins. */
    data class SetBackground(override val stamp: OpStamp, val background: BackgroundSpec) : DocumentOp
}

/** New content of one table cell. */
data class CellUpdate(val key: CellKey, val cell: TableCell)
