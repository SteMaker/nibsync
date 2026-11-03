package com.stemaker.nibsync.text

import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.model.BlockDraft
import com.stemaker.nibsync.model.FileElement
import com.stemaker.nibsync.model.Flow
import com.stemaker.nibsync.model.FlowBlock
import com.stemaker.nibsync.model.FlowSlot
import com.stemaker.nibsync.model.FractionalIndex
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TableElement
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TextBoxDraft
import com.stemaker.nibsync.model.toDraft

/** A key on a selected table, picture, file or to-do in a box. */
enum class BlockKey { UP, DOWN, LEFT, RIGHT, ENTER, BACKSPACE, DELETE }

/**
 * A box as one thing to edit, the same on every platform: its text, tables, pictures, files and
 * to-dos are paragraphs one below the other, and the cursor goes through all of them. Text is
 * typed in [text], a table in [tables]; a picture, file or to-do (or a whole table) is selected
 * as a block: the platform shows it as its selection, and calls [fromBlock] for keys on it.
 */
class BoxEditing(
    private val session: DocumentSession,
    private val text: TextBoxEditing,
    private val tables: TableEditing,
    private val flows: FlowEditing,
) {
    /** The flow being edited: of the open text or table cell, else null. */
    fun openFlow(): Flow? {
        val state = session.state.value
        text.editing.value?.let { e -> return (e.boxId?.let(state::flowOf)) ?: e.slot?.let { state.flow(it.flowId) } }
        return tables.table()?.let { state.flowOf(it.id) }
    }

    /**
     * A key at the edge of the open text. Returns the block that got selected (a picture, file,
     * to-do or a whole table) as a set of one id, an empty set if the cursor went into other text
     * or a table, null if the key was not handled (the editor does its own thing).
     */
    fun fromText(boundary: Boundary): Set<StrokeId>? {
        val e = text.editing.value ?: return null
        val box = e.boxId?.let { session.state.value.element(it) } as? TextBox ?: return null
        return when (boundary) {
            Boundary.UP, Boundary.LEFT -> enter(neighbour(box, -1), fromBelow = true)
            Boundary.DOWN, Boundary.RIGHT -> enter(neighbour(box, 1), fromBelow = false)
            Boundary.BACKSPACE -> selectBlock(neighbour(box, -1))
            Boundary.DELETE -> selectBlock(neighbour(box, 1))
        }
    }

    /** A key at the edge of the open table cell; see [fromText]. Inside the table it goes from cell to cell. */
    fun fromCell(boundary: Boundary): Set<StrokeId>? {
        val e = tables.editing.value ?: return null
        val table = tables.table() ?: return null
        val g = table.geometry
        val area = g.areaOf(e.cell) ?: return null
        val index = g.areas.indexOf(area)
        return when (boundary) {
            Boundary.UP -> if (tables.move(CellMove.UP)) emptySet() else enter(neighbour(table, -1), fromBelow = true)
            Boundary.DOWN -> if (tables.move(CellMove.DOWN)) emptySet() else enter(neighbour(table, 1), fromBelow = false) ?: afterTable(table)
            Boundary.LEFT -> if (index > 0 && tables.move(CellMove.PREVIOUS)) emptySet() else enter(neighbour(table, -1), fromBelow = true)
            Boundary.RIGHT -> if (index < g.areas.lastIndex && tables.move(CellMove.NEXT)) emptySet() else enter(neighbour(table, 1), fromBelow = false) ?: afterTable(table)
            Boundary.BACKSPACE, Boundary.DELETE -> null
        }
    }

    /**
     * A key on the selected block [id] of a box: arrows go to the paragraph before or after it,
     * Enter starts text below it, Backspace and Delete remove it (the text around it is joined
     * and the cursor goes there). Returns the new selection as in [fromText].
     */
    fun fromBlock(id: StrokeId, key: BlockKey): Set<StrokeId>? {
        val block = session.state.value.element(id) as? FlowBlock ?: return null
        return when (key) {
            BlockKey.UP, BlockKey.LEFT -> enter(neighbour(block, -1), fromBelow = true)
            BlockKey.DOWN, BlockKey.RIGHT -> enter(neighbour(block, 1), fromBelow = false)
            BlockKey.ENTER -> {
                val next = neighbour(block, 1)
                if (next is TextBox) text.open(next) { it.placeCursorAtStart() } else startTextAfter(block)
                emptySet()
            }
            BlockKey.BACKSPACE, BlockKey.DELETE -> remove(block)
        }
    }

    /** Out of a table at the end of its box: text goes on below it. */
    private fun afterTable(table: TableElement): Set<StrokeId> {
        startTextAfter(table)
        return emptySet()
    }

    /** A new, empty paragraph of text right below [block] (and its row). */
    private fun startTextAfter(block: FlowBlock) {
        val flow = session.state.value.flowOf(block.id) ?: return
        val line = Flow.rowsOf(flow.blocks).first { l -> l.any { it.id == block.id } }
        val next = flow.blocks.firstOrNull { it.slot.order > block.slot.order }
        val slot = FlowSlot(flow.id, FractionalIndex.between(block.slot.order, next?.slot?.order))
        tables.finish()
        text.startNew(flow.x, line.maxOf { it.bounds.bottom } + Flow.GAP_MM, widthOf(flow), slot)
    }

    private fun remove(block: FlowBlock): Set<StrokeId> {
        val before = neighbour(block, -1) as? TextBox
        val after = neighbour(block, 1) as? TextBox
        val flowId = block.slot.flowId
        flows.remove(listOf(block.id))
        val flow = session.state.value.flow(flowId) ?: return emptySet()
        when {
            // The text before and after are one now: the cursor where they were joined.
            before != null -> flow.blocks.filterIsInstance<TextBox>().firstOrNull { b -> b.text.paragraphs.take(before.text.paragraphs.size) == before.text.paragraphs }?.let { joined ->
                val last = before.text.paragraphs.lastIndex
                text.open(joined) { it.placeCursor(last, before.text.paragraphs[last].text.length) }
            }
            after != null -> flow.blocks.filterIsInstance<TextBox>().firstOrNull { it.text == after.text }?.let { text.open(it) { c -> c.placeCursorAtStart() } }
        }
        return emptySet()
    }

    /** The block before (-1) or after (1) [block] in its flow. */
    private fun neighbour(block: FlowBlock, step: Int): FlowBlock? {
        val blocks = session.state.value.flowOf(block.id)?.blocks ?: return null
        return blocks.getOrNull(blocks.indexOfFirst { it.id == block.id } + step)
    }

    /** Goes into [block] from above or [fromBelow]; null if there is none (the key is not handled). */
    private fun enter(block: FlowBlock?, fromBelow: Boolean): Set<StrokeId>? = when (block) {
        null -> null
        is TextBox -> {
            tables.finish()
            text.open(block) { if (fromBelow) it.placeCursorAtEnd() else it.placeCursorAtStart() }
            emptySet()
        }
        is TableElement -> {
            text.finish()
            val g = block.geometry
            val area = if (fromBelow) g.areas.last() else g.areas.first()
            tables.open(block, g.key(area))
            tables.editing.value?.controller?.let { if (fromBelow) it.placeCursorAtEnd() else it.placeCursorAtStart() }
            emptySet()
        }
        else -> selectBlock(block)
    }

    private fun selectBlock(block: FlowBlock?): Set<StrokeId>? {
        if (block == null || block is TextBox) return null
        text.finish()
        tables.finish()
        return setOf(block.id)
    }

    /** The width of a flow's text, or of the flow if it has none. */
    fun widthOf(flow: Flow): Float = flow.blocks.filterIsInstance<TextBox>().maxOfOrNull { it.widthMm } ?: flow.bounds.width

    /**
     * Sets the width of the box [flowId] in one undo step: all its text gets it, tables, pictures and
     * files wider than it (less their indent) are scaled down to it. The open text stays open, with
     * its cursor.
     */
    fun setWidth(flowId: StrokeId, widthMm: Float) {
        val width = maxOf(TextBox.MIN_WIDTH_MM, widthMm)
        val open = text.editing.value
        if (open != null) text.save()
        val state = session.state.value
        val flow = state.flow(flowId) ?: return
        val openIndex = open?.boxId?.let { id -> flow.blocks.indexOfFirst { it.id == id } }
        val selection = open?.controller?.state?.selection
        session.edit {
            flowing(flowId) {
                for (b in flow.blocks) {
                    val room = width - b.slot.offsetMm
                    when {
                        b is TextBox -> if (b.widthMm != width) {
                            val t = b.text.withAnchorsPlaced(width, text.measureText)
                            replaceElements(listOf(b.id), listOf(TextBoxDraft(b.x, b.y, width, text.measureText(t, width), t, b.slot)))
                        }
                        b is TableElement && b.widthMm > room -> {
                            val (draft, cells) = tables.scaled(b, room)
                            val added = replaceElements(listOf(b.id), listOf(draft)).single() as TableElement
                            setTableCells(added.tableId, cells)
                        }
                        // Pictures of a row: the row scales them itself.
                        b is FileElement && b.slot.beside == null && b.widthMm > room ->
                            replaceElements(listOf(b.id), listOf((b.toDraft() as BlockDraft).fittedTo(room)))
                    }
                }
            }
        }
        if (open != null && openIndex != null && openIndex >= 0) {
            (session.state.value.flow(flowId)?.blocks?.getOrNull(openIndex) as? TextBox)?.let { box ->
                text.discard()
                text.open(box) { c -> selection?.let { c.state.selection = it } }
            }
        }
    }
}

