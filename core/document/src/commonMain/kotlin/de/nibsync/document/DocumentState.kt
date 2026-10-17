package de.nibsync.document

import de.nibsync.model.DocumentMeta
import de.nibsync.model.Stroke
import de.nibsync.model.StrokeId

/**
 * Immutable state of a note document, built purely from ops.
 *
 * Merge semantics (a "two-phase set" CRDT):
 * - an op that was already applied is ignored (idempotent),
 * - a removed stroke id stays removed for good (tombstone); a later or earlier add of the same
 *   id has no effect. Edits therefore always create strokes with fresh ids.
 *
 * With these rules the result does not depend on the order in which ops arrive, which is what
 * concurrent editing on several devices needs.
 */
class DocumentState private constructor(
    val meta: DocumentMeta,
    private val entries: Map<StrokeId, Entry>,
    private val tombstones: Set<StrokeId>,
    private val applied: Set<OpStamp>,
    /** Highest Lamport counter seen so far. */
    val clock: Long,
) {
    private class Entry(val stroke: Stroke, val addedAt: OpStamp)

    val strokeCount: Int get() = entries.size

    /** All visible strokes, back to front: by layer, then by the stamp of the op that added them. */
    val strokes: List<Stroke> by lazy {
        entries.values
            .sortedWith(compareBy<Entry> { it.stroke.layer.zIndex }.thenBy { it.addedAt })
            .map { it.stroke }
    }

    /** Largest y coordinate covered by any stroke, 0 for an empty document. */
    val contentBottomMm: Float by lazy {
        entries.values.maxOfOrNull { it.stroke.bounds.bottom } ?: 0f
    }

    operator fun get(id: StrokeId): Stroke? = entries[id]?.stroke

    operator fun contains(id: StrokeId): Boolean = id in entries

    fun isRemoved(id: StrokeId): Boolean = id in tombstones

    fun hasApplied(stamp: OpStamp): Boolean = stamp in applied

    fun apply(op: DocumentOp): DocumentState {
        if (op.stamp in applied) return this
        val newClock = maxOf(clock, op.stamp.counter)
        return when (op) {
            is DocumentOp.AddStrokes -> {
                val added = op.strokes.filter { it.id !in tombstones }
                // A duplicate add of an existing id keeps the earlier one so ordering stays stable.
                val newEntries = entries.toMutableMap()
                for (s in added) {
                    val existing = newEntries[s.id]
                    if (existing == null || op.stamp < existing.addedAt) newEntries[s.id] = Entry(s, op.stamp)
                }
                DocumentState(meta, newEntries, tombstones, applied + op.stamp, newClock)
            }
            is DocumentOp.RemoveStrokes ->
                DocumentState(meta, entries - op.ids, tombstones + op.ids, applied + op.stamp, newClock)
        }
    }

    fun applyAll(ops: Iterable<DocumentOp>): DocumentState = ops.fold(this) { s, op -> s.apply(op) }

    fun withMeta(meta: DocumentMeta): DocumentState = DocumentState(meta, entries, tombstones, applied, clock)

    companion object {
        fun empty(meta: DocumentMeta): DocumentState = DocumentState(meta, emptyMap(), emptySet(), emptySet(), 0L)
    }
}
