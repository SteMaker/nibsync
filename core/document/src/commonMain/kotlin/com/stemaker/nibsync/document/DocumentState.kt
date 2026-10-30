package com.stemaker.nibsync.document

import com.stemaker.nibsync.model.Anchor
import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.Flow
import com.stemaker.nibsync.model.FlowBlock
import com.stemaker.nibsync.model.FileElement
import com.stemaker.nibsync.model.LinkElement
import com.stemaker.nibsync.model.PageElement
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TodoItem
import com.stemaker.nibsync.model.TableElement
import com.stemaker.nibsync.model.CellKey
import com.stemaker.nibsync.model.TableCell

/**
 * Immutable state of a note document, built purely from ops.
 *
 * Merge semantics (a "two-phase set" CRDT over page elements, i.e. strokes and text boxes):
 * - an op that was already applied is ignored (idempotent),
 * - a removed stroke id stays removed for good (tombstone); a later or earlier add of the same
 *   id has no effect. Edits therefore always create strokes with fresh ids.
 *
 * With these rules the result does not depend on the order in which ops arrive, which is what
 * concurrent editing on several devices needs. The background is a last-writer-wins register:
 * the [DocumentOp.SetBackground] with the highest stamp counts, else the one in [meta]. Likewise
 * each to-do task's done state: the [DocumentOp.SetTodoDone] with the highest stamp counts, and
 * each table cell's content: the [DocumentOp.SetTableCells] with the highest stamp for that
 * cell counts, over the content the table was added with.
 *
 * Blocks (text, tables, pictures, file links, to-dos) belong to a [Flow]: where they are is not
 * stored but derived here, by stacking the blocks of a flow in their order from the flow's position.
 * That is a [DocumentOp.SetFlowPosition] register, else the stored position of the first block.
 */
class DocumentState private constructor(
    val meta: DocumentMeta,
    private val entries: Map<StrokeId, Entry>,
    private val tombstones: Set<StrokeId>,
    private val applied: Set<OpStamp>,
    /** Highest Lamport counter seen so far. */
    val clock: Long,
    private val backgroundSetAt: OpStamp?,
    /** Paper pattern currently in effect. */
    val background: BackgroundSpec,
    /** Done state per to-do task, with the stamp that set it (last writer wins). */
    private val todoDone: Map<StrokeId, Pair<OpStamp, Boolean>>,
    /** Content per table (by table id) and cell, with the stamp that set it (last writer wins). */
    private val tableCells: Map<StrokeId, Map<CellKey, Pair<OpStamp, TableCell>>>,
    /** Position per flow, with the stamp that set it (last writer wins). */
    private val flowPositions: Map<StrokeId, Pair<OpStamp, Pair<Float, Float>>>,
) {
    private class Entry(val element: PageElement, val addedAt: OpStamp)

    /** The blocks of each flow in order, with their current cells and at their places on the page. */
    private val laidOut: Map<StrokeId, Flow> by lazy {
        entries.values.mapNotNull { resolved(it.element) as? FlowBlock }
            .groupBy { it.slot.flowId }
            .mapValues { (flowId, blocks) -> stacked(flowId, blocks) }
    }

    private val placed: Map<StrokeId, FlowBlock> by lazy {
        laidOut.values.flatMap { it.blocks }.associateBy { it.id }
    }

    private fun stacked(flowId: StrokeId, blocks: List<FlowBlock>): Flow {
        val ordered = blocks.sortedWith(compareBy<FlowBlock> { it.slot.order }.thenBy { it.id.value })
        val (x, top) = flowPositions[flowId]?.second ?: (ordered.first().x to ordered.first().y)
        var y = top
        return Flow(flowId, ordered.map { b -> b.placedAt(x, y).also { y += it.heightMm + Flow.GAP_MM } })
    }

    /**
     * All visible elements, back to front: by layer, then by the stamp of the op that added them.
     * Tables carry their current cells (the added content overlaid with [DocumentOp.SetTableCells]),
     * blocks their place in their flow.
     */
    val elements: List<PageElement> by lazy {
        entries.values
            .sortedWith(compareBy<Entry> { it.element.layer.zIndex }.thenBy { it.addedAt })
            .map { placed[it.element.id] ?: it.element }
    }

    /** Visible flows, in no particular order. */
    val flows: List<Flow> get() = laidOut.values.toList()

    fun flow(flowId: StrokeId): Flow? = laidOut[flowId]

    /** The flow the element [id] belongs to, or null if it is not a block. */
    fun flowOf(id: StrokeId): Flow? = placed[id]?.let { laidOut[it.slot.flowId] }

    /** A table with its cells as set by [DocumentOp.SetTableCells]; other elements unchanged. */
    private fun resolved(e: PageElement): PageElement {
        if (e !is TableElement) return e
        val set = tableCells[e.tableId] ?: return e
        return e.copy(cells = e.cells + set.mapValues { it.value.second })
    }

    /** Visible tables, back to front. */
    val tables: List<TableElement> by lazy { elements.filterIsInstance<TableElement>() }

    /** Visible strokes, back to front. */
    val strokes: List<Stroke> by lazy { elements.filterIsInstance<Stroke>() }

    /** Visible text boxes, back to front. */
    val textBoxes: List<TextBox> by lazy { elements.filterIsInstance<TextBox>() }

    /** Visible pictures and file links, back to front. */
    val files: List<FileElement> by lazy { elements.filterIsInstance<FileElement>() }

    /** Visible to-dos, back to front. */
    val todos: List<TodoItem> by lazy { elements.filterIsInstance<TodoItem>() }

    /** Visible anchors and links to notes, back to front. */
    val links: List<LinkElement> by lazy { elements.filterIsInstance<LinkElement>() }

    /** Visible anchors, back to front. */
    val anchors: List<Anchor> by lazy { elements.filterIsInstance<Anchor>() }

    /** The anchor [anchorId] (if it was moved, its current element). */
    fun anchor(anchorId: StrokeId): Anchor? = anchors.firstOrNull { it.anchorId == anchorId }

    /** Whether the task has been ticked off (open unless a [DocumentOp.SetTodoDone] says so). */
    fun isDone(taskId: StrokeId): Boolean = todoDone[taskId]?.second ?: false

    val strokeCount: Int get() = strokes.size

    /** Largest y coordinate covered by any element, 0 for an empty document. */
    val contentBottomMm: Float by lazy {
        elements.maxOfOrNull { it.bounds.bottom } ?: 0f
    }

    /** The stroke with this id, or null if there is none (or the id belongs to another kind). */
    operator fun get(id: StrokeId): Stroke? = entries[id]?.element as? Stroke

    fun element(id: StrokeId): PageElement? = placed[id] ?: entries[id]?.element

    operator fun contains(id: StrokeId): Boolean = id in entries

    fun isRemoved(id: StrokeId): Boolean = id in tombstones

    fun hasApplied(stamp: OpStamp): Boolean = stamp in applied

    fun apply(op: DocumentOp): DocumentState {
        if (op.stamp in applied) return this
        val newClock = maxOf(clock, op.stamp.counter)
        return when (op) {
            is DocumentOp.AddStrokes -> add(op.stamp, op.strokes, newClock)
            is DocumentOp.AddTextBoxes -> add(op.stamp, op.boxes, newClock)
            is DocumentOp.AddFiles -> add(op.stamp, op.elements, newClock)
            is DocumentOp.AddTodos -> add(op.stamp, op.items, newClock)
            is DocumentOp.AddLinks -> add(op.stamp, op.elements, newClock)
            is DocumentOp.AddTables -> add(op.stamp, op.tables, newClock)
            is DocumentOp.RemoveStrokes ->
                DocumentState(meta, entries - op.ids, tombstones + op.ids, applied + op.stamp, newClock, backgroundSetAt, background, todoDone, tableCells, flowPositions)
            is DocumentOp.SetBackground ->
                if (backgroundSetAt == null || op.stamp > backgroundSetAt) {
                    DocumentState(meta, entries, tombstones, applied + op.stamp, newClock, op.stamp, op.background, todoDone, tableCells, flowPositions)
                } else {
                    DocumentState(meta, entries, tombstones, applied + op.stamp, newClock, backgroundSetAt, background, todoDone, tableCells, flowPositions)
                }
            is DocumentOp.SetTodoDone -> {
                val current = todoDone[op.taskId]
                val done = if (current == null || op.stamp > current.first) todoDone + (op.taskId to (op.stamp to op.done)) else todoDone
                DocumentState(meta, entries, tombstones, applied + op.stamp, newClock, backgroundSetAt, background, done, tableCells, flowPositions)
            }
            is DocumentOp.SetTableCells -> {
                val cells = (tableCells[op.tableId] ?: emptyMap()).toMutableMap()
                for (u in op.cells) {
                    val current = cells[u.key]
                    if (current == null || op.stamp > current.first) cells[u.key] = op.stamp to u.cell
                }
                DocumentState(meta, entries, tombstones, applied + op.stamp, newClock, backgroundSetAt, background, todoDone, tableCells + (op.tableId to cells), flowPositions)
            }
            is DocumentOp.SetFlowPosition -> {
                val current = flowPositions[op.flowId]
                val positions = if (current == null || op.stamp > current.first) flowPositions + (op.flowId to (op.stamp to (op.x to op.y))) else flowPositions
                DocumentState(meta, entries, tombstones, applied + op.stamp, newClock, backgroundSetAt, background, todoDone, tableCells, positions)
            }
        }
    }

    private fun add(stamp: OpStamp, elements: List<PageElement>, newClock: Long): DocumentState {
        // A duplicate add of an existing id keeps the earlier one so ordering stays stable.
        val newEntries = entries.toMutableMap()
        for (e in elements) {
            if (e.id in tombstones) continue
            val existing = newEntries[e.id]
            if (existing == null || stamp < existing.addedAt) newEntries[e.id] = Entry(e, stamp)
        }
        return DocumentState(meta, newEntries, tombstones, applied + stamp, newClock, backgroundSetAt, background, todoDone, tableCells, flowPositions)
    }

    fun applyAll(ops: Iterable<DocumentOp>): DocumentState = ops.fold(this) { s, op -> s.apply(op) }

    /** Replaces [meta]; its background only counts as long as no [DocumentOp.SetBackground] was applied. */
    fun withMeta(meta: DocumentMeta): DocumentState =
        DocumentState(meta, entries, tombstones, applied, clock, backgroundSetAt, if (backgroundSetAt == null) meta.background else background, todoDone, tableCells, flowPositions)

    companion object {
        fun empty(meta: DocumentMeta): DocumentState =
            DocumentState(meta, emptyMap(), emptySet(), emptySet(), 0L, null, meta.background, emptyMap(), emptyMap(), emptyMap())
    }
}
