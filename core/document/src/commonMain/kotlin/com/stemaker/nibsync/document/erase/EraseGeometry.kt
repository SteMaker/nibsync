package com.stemaker.nibsync.document.erase

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Small float geometry helpers for erasing, in document millimetres. */
internal object EraseGeometry {

    fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Shortest distance between segments p1-p2 and q1-q2 (0 if they cross). */
    fun segmentDistance(
        p1x: Float, p1y: Float, p2x: Float, p2y: Float,
        q1x: Float, q1y: Float, q2x: Float, q2y: Float,
    ): Float {
        if (intersects(p1x, p1y, p2x, p2y, q1x, q1y, q2x, q2y)) return 0f
        return min(
            min(distanceToSegment(p1x, p1y, q1x, q1y, q2x, q2y), distanceToSegment(p2x, p2y, q1x, q1y, q2x, q2y)),
            min(distanceToSegment(q1x, q1y, p1x, p1y, p2x, p2y), distanceToSegment(q2x, q2y, p1x, p1y, p2x, p2y)),
        )
    }

    private fun intersects(
        p1x: Float, p1y: Float, p2x: Float, p2y: Float,
        q1x: Float, q1y: Float, q2x: Float, q2y: Float,
    ): Boolean {
        fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        val d1 = cross(q1x, q1y, q2x, q2y, p1x, p1y)
        val d2 = cross(q1x, q1y, q2x, q2y, p2x, p2y)
        val d3 = cross(p1x, p1y, p2x, p2y, q1x, q1y)
        val d4 = cross(p1x, p1y, p2x, p2y, q2x, q2y)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    /** True if the axis-aligned boxes, the first grown by [margin] on every side, overlap. */
    fun boxesOverlap(
        l1: Float, t1: Float, r1: Float, b1: Float, margin: Float,
        l2: Float, t2: Float, r2: Float, b2: Float,
    ): Boolean = l1 - margin <= r2 && l2 <= r1 + margin && t1 - margin <= b2 && t2 <= b1 + margin

    fun segmentBox(ax: Float, ay: Float, bx: Float, by: Float) = floatArrayOf(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by))
}
