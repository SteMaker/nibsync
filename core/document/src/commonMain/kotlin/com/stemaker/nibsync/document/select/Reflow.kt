package com.stemaker.nibsync.document.select

import com.stemaker.nibsync.model.FlowBlock
import com.stemaker.nibsync.model.PageElement
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.geometry.Rect

/**
 * Between flows on the free page: when a flow gets taller or shorter, what lies below it in its
 * column moves by the same amount, so nothing is covered and the gaps stay as they were. "Below in
 * its column": its top is at or below the flow's old bottom and it overlaps the flow horizontally,
 * or overlaps something else that moves (a wide picture takes what is under it along). Handwriting
 * beside the flow, e.g. in the margin, stays. A flow moves as a whole: what counts is its own
 * extent, and a block of it is only ever moved with all the others. Floating blocks
 * ([com.stemaker.nibsync.model.FlowSlot.floating]) are left out both ways.
 */
object Reflow {
    /** Height changes smaller than this (mm) move nothing. */
    const val MIN_SHIFT_MM = 0.3f

    /** How far (mm) an element may reach above the old bottom and still count as below. */
    const val TOLERANCE_MM = 0.5f

    /** Space kept between a newly placed block and what it pushed down (mm). */
    const val GAP_MM = 3f

    /**
     * The elements to move by `newBottom - oldBottom` when the block [source] (its ids, old and
     * new) spanning [left]..[right] mm changes its bottom from [oldBottom] to [newBottom] mm.
     */
    fun shifts(
        elements: List<PageElement>,
        source: Set<StrokeId>,
        left: Float,
        right: Float,
        oldBottom: Float,
        newBottom: Float,
    ): List<PageElement> {
        if (kotlin.math.abs(newBottom - oldBottom) < MIN_SHIFT_MM) return emptyList()
        val candidates = elements
            .groupBy { (it as? FlowBlock)?.slot?.flowId ?: it.id }.values
            .filter { unit -> unit.none { it.id in source || (it as? FlowBlock)?.slot?.floating == true } && unit.minOf { it.bounds.top } >= oldBottom - TOLERANCE_MM }
            .sortedBy { unit -> unit.minOf { it.bounds.top } }
        val columns = mutableListOf(left to right)
        val moving = ArrayList<PageElement>()
        for (unit in candidates) {
            val l = unit.minOf { it.bounds.left }
            val r = unit.maxOf { it.bounds.right }
            if (columns.any { (cl, cr) -> l < cr && r > cl }) {
                moving += unit
                columns += l to r
            }
        }
        return moving
    }

    /**
     * What moves, and by how much (mm), after the flow [source] (its blocks' ids) went from
     * [before] to [after] (null: did not exist / no longer exists):
     * - taller or shorter in place: what is below in its column, by the difference;
     * - new: what it would cover, down just far enough to keep [GAP_MM] below it;
     * - gone: what was below, up by its height;
     * - moved elsewhere (its top changed): nothing.
     */
    fun plan(elements: List<PageElement>, source: Set<StrokeId>, before: Rect?, after: Rect?): Pair<List<PageElement>, Float> {
        val none = emptyList<PageElement>() to 0f
        // A floating block lies over the rest: it pushes nothing.
        if (elements.any { it.id in source && (it as? FlowBlock)?.slot?.floating == true }) return none
        return when {
            before == null && after != null -> {
                val below = shifts(elements, source, after.left, after.right, after.top, after.top + MIN_SHIFT_MM * 2)
                val firstTop = below.minOfOrNull { it.bounds.top } ?: return none
                val dy = after.bottom + GAP_MM - firstTop
                if (dy > 0f) below to dy else none
            }
            before != null && after == null ->
                shifts(elements, source, before.left, before.right, before.bottom, before.top) to (before.top - before.bottom)
            before != null && after != null && kotlin.math.abs(before.top - after.top) < 0.01f ->
                shifts(elements, source, minOf(before.left, after.left), maxOf(before.right, after.right), before.bottom, after.bottom) to
                    (after.bottom - before.bottom)
            else -> none
        }
    }
}
