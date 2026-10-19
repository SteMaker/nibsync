package com.stemaker.nibsync.document

import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.FileElement
import com.stemaker.nibsync.model.PageElement
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TodoItem

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
 * each to-do task's done state: the [DocumentOp.SetTodoDone] with the highest stamp counts.
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
) {
    private class Entry(val element: PageElement, val addedAt: OpStamp)

    /** All visible elements, back to front: by layer, then by the stamp of the op that added them. */
    val elements: List<PageElement> by lazy {
        entries.values
            .sortedWith(compareBy<Entry> { it.element.layer.zIndex }.thenBy { it.addedAt })
            .map { it.element }
    }

    /** Visible strokes, back to front. */
    val strokes: List<Stroke> by lazy { elements.filterIsInstance<Stroke>() }

    /** Visible text boxes, back to front. */
    val textBoxes: List<TextBox> by lazy { elements.filterIsInstance<TextBox>() }

    /** Visible pictures and file links, back to front. */
    val files: List<FileElement> by lazy { elements.filterIsInstance<FileElement>() }

    /** Visible to-dos, back to front. */
    val todos: List<TodoItem> by lazy { elements.filterIsInstance<TodoItem>() }

    /** Whether the task has been ticked off (open unless a [DocumentOp.SetTodoDone] says so). */
    fun isDone(taskId: StrokeId): Boolean = todoDone[taskId]?.second ?: false

    val strokeCount: Int get() = strokes.size

    /** Largest y coordinate covered by any element, 0 for an empty document. */
    val contentBottomMm: Float by lazy {
        entries.values.maxOfOrNull { it.element.bounds.bottom } ?: 0f
    }

    /** The stroke with this id, or null if there is none (or the id belongs to another kind). */
    operator fun get(id: StrokeId): Stroke? = entries[id]?.element as? Stroke

    fun element(id: StrokeId): PageElement? = entries[id]?.element

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
            is DocumentOp.RemoveStrokes ->
                DocumentState(meta, entries - op.ids, tombstones + op.ids, applied + op.stamp, newClock, backgroundSetAt, background, todoDone)
            is DocumentOp.SetBackground ->
                if (backgroundSetAt == null || op.stamp > backgroundSetAt) {
                    DocumentState(meta, entries, tombstones, applied + op.stamp, newClock, op.stamp, op.background, todoDone)
                } else {
                    DocumentState(meta, entries, tombstones, applied + op.stamp, newClock, backgroundSetAt, background, todoDone)
                }
            is DocumentOp.SetTodoDone -> {
                val current = todoDone[op.taskId]
                val done = if (current == null || op.stamp > current.first) todoDone + (op.taskId to (op.stamp to op.done)) else todoDone
                DocumentState(meta, entries, tombstones, applied + op.stamp, newClock, backgroundSetAt, background, done)
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
        return DocumentState(meta, newEntries, tombstones, applied + stamp, newClock, backgroundSetAt, background, todoDone)
    }

    fun applyAll(ops: Iterable<DocumentOp>): DocumentState = ops.fold(this) { s, op -> s.apply(op) }

    /** Replaces [meta]; its background only counts as long as no [DocumentOp.SetBackground] was applied. */
    fun withMeta(meta: DocumentMeta): DocumentState =
        DocumentState(meta, entries, tombstones, applied, clock, backgroundSetAt, if (backgroundSetAt == null) meta.background else background, todoDone)

    companion object {
        fun empty(meta: DocumentMeta): DocumentState =
            DocumentState(meta, emptyMap(), emptySet(), emptySet(), 0L, null, meta.background, emptyMap())
    }
}
