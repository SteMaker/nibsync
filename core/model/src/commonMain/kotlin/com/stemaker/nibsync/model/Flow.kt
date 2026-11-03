package com.stemaker.nibsync.model

import com.stemaker.nibsync.model.geometry.Rect

/**
 * Where a block belongs: to the flow [flowId], at [order] among its blocks ([FractionalIndex]
 * keys, equal keys of concurrent inserts go by element id), indented by [indent] steps of
 * [Flow.INDENT_MM] (like list levels, so a picture can belong to a list item). Pictures with the
 * same [order] and a [beside] key stand in one row, ordered by it (see [Flow.rowsOf]).
 */
data class FlowSlot(val flowId: StrokeId, val order: String, val indent: Int = 0, val beside: String? = null) {
    init {
        require(indent in 0..MAX_INDENT) { "indent $indent out of range" }
    }

    /** How far (mm) the block sits right of its flow's left edge. */
    val offsetMm: Float get() = indent * Flow.INDENT_MM

    companion object {
        /** Order of the only block of a flow. */
        val FIRST: String = FractionalIndex.between(null, null)

        const val MAX_INDENT = 7
    }
}

/**
 * Something that lives in a [Flow]: text, a table, a picture, a file link or a to-do. Its [x], [y]
 * as seen in a document state are derived from the flow; moving a block on its own is not possible,
 * only a whole flow moves.
 */
sealed interface FlowBlock : PageElement {
    val slot: FlowSlot
    val x: Float
    val y: Float
    val widthMm: Float
    val heightMm: Float

    /** The same block with its top left corner at ([x], [y]) mm. */
    fun placedAt(x: Float, y: Float): FlowBlock
}

/** A block before it gets an id. A null [slot] starts a new flow at the draft's position. */
sealed interface BlockDraft : ElementDraft {
    val slot: FlowSlot?
    val x: Float
    val y: Float
    val widthMm: Float

    fun inSlot(slot: FlowSlot?): BlockDraft
    override fun translated(dx: Float, dy: Float): BlockDraft
}

/**
 * What the user sees as one box: [blocks] stacked from top to bottom, [GAP_MM] apart, so that
 * they keep their order and distance whatever gets taller or shorter. ([x], [y]) is its top
 * left corner; an indented block starts further right.
 */
data class Flow(val id: StrokeId, val blocks: List<FlowBlock>, val x: Float, val y: Float) {
    init {
        require(blocks.isNotEmpty()) { "A flow needs at least one block" }
    }

    val bounds: Rect
        get() = Rect(x, y, blocks.maxOf { it.x + it.widthMm }, blocks.last().let { it.y + it.heightMm })

    companion object {
        /** Space between two blocks of a flow (mm). */
        const val GAP_MM = 3f

        /** One indent step (mm): a list level of the text (20 pt). */
        const val INDENT_MM = 20f * 25.4f / 72f

        /**
         * [ordered] blocks (by order) grouped into what stands on one line: a single block, or
         * pictures of a row (same order, a [FlowSlot.beside] key, at least two), left to right.
         */
        fun rowsOf(ordered: List<FlowBlock>): List<List<FlowBlock>> {
            val out = ArrayList<List<FlowBlock>>()
            var i = 0
            while (i < ordered.size) {
                val b = ordered[i]
                var j = i + 1
                if (b is ImageElement && b.slot.beside != null) {
                    while (j < ordered.size && ordered[j].let { it is ImageElement && it.slot.beside != null && it.slot.order == b.slot.order }) j++
                }
                val group = ordered.subList(i, j)
                if (group.size > 1) out += group.sortedWith(compareBy<FlowBlock> { it.slot.beside }.thenBy { it.id.value }) else out += listOf(b)
                i = j
            }
            return out
        }
    }
}

/**
 * Sort keys that always leave room between two neighbours, so inserting a block touches no other
 * block. Keys are strings over a..z compared as usual; none ends in 'a', the smallest digit.
 */
object FractionalIndex {
    private const val BASE = 26

    /** A key above [low] and below [high]; null means no limit on that side. Needs [low] < [high]. */
    fun between(low: String?, high: String?): String {
        require(low == null || high == null || low < high) { "$low must be below $high" }
        val lo = low.orEmpty()
        var hi = high
        val key = StringBuilder()
        var i = 0
        while (true) {
            val l = if (i < lo.length) lo[i] - 'a' else 0
            val h = hi?.let { if (i < it.length) it[i] - 'a' else null } ?: BASE
            when {
                h == l -> key.append('a' + l)
                h - l >= 2 -> return key.append('a' + (l + h) / 2).toString()
                else -> {
                    // No digit fits: stay at the lower one, and everything after it is free.
                    key.append('a' + l)
                    hi = null
                }
            }
            i++
        }
    }
}
