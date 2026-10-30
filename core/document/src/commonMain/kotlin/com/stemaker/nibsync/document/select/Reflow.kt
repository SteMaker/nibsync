package com.stemaker.nibsync.document.select

import com.stemaker.nibsync.model.PageElement
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.geometry.Rect

/**
 * Text flow on the free page: when a block (text box, table, to-do) gets taller or shorter,
 * what lies below it in its column moves by the same amount, so nothing is covered and the gaps
 * stay as they were. "Below in its column": its top is at or below the block's old bottom and it
 * overlaps the block horizontally, or overlaps something else that moves (a wide picture takes
 * what is under it along). Handwriting beside the block, e.g. in the margin, stays.
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
            .filter { it.id !in source && it.bounds.top >= oldBottom - TOLERANCE_MM }
            .sortedBy { it.bounds.top }
        val columns = mutableListOf(left to right)
        val moving = ArrayList<PageElement>()
        for (e in candidates) {
            val b = e.bounds
            if (columns.any { (l, r) -> b.left < r && b.right > l }) {
                moving += e
                columns += b.left to b.right
            }
        }
        return moving
    }

    /**
     * What moves, and by how much (mm), after the block [source] (old and new ids) went from
     * [before] to [after] (null: did not exist / no longer exists):
     * - taller or shorter in place: what is below in its column, by the difference;
     * - new: what it would cover, down just far enough to keep [GAP_MM] below it;
     * - gone: what was below, up by its height;
     * - moved elsewhere (its top changed): nothing.
     */
    fun plan(elements: List<PageElement>, source: Set<StrokeId>, before: Rect?, after: Rect?): Pair<List<PageElement>, Float> {
        val none = emptyList<PageElement>() to 0f
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
