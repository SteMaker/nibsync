package com.stemaker.nibsync.model

import com.stemaker.nibsync.model.geometry.Rect

/**
 * Where a block belongs: to the flow [flowId], at [order] among its blocks ([FractionalIndex]
 * keys, equal keys of concurrent inserts go by element id).
 */
data class FlowSlot(val flowId: StrokeId, val order: String) {
    companion object {
        /** Order of the only block of a flow. */
        val FIRST: String = FractionalIndex.between(null, null)
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
 * they keep their order and distance whatever gets taller or shorter.
 */
data class Flow(val id: StrokeId, val blocks: List<FlowBlock>) {
    init {
        require(blocks.isNotEmpty()) { "A flow needs at least one block" }
    }

    val x: Float get() = blocks.first().x
    val y: Float get() = blocks.first().y

    val bounds: Rect
        get() = Rect(x, y, blocks.maxOf { it.x + it.widthMm }, blocks.last().let { it.y + it.heightMm })

    companion object {
        /** Space between two blocks of a flow (mm). */
        const val GAP_MM = 3f
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
