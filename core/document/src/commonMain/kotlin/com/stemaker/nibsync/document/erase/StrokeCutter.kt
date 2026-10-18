package com.stemaker.nibsync.document.erase

import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokePoint
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Erasing for vector strokes. The eraser is a circle of radius `r` swept along a segment; it
 * hits a stroke where the stroke's centre line comes within `r` plus half the stroke width,
 * i.e. wherever the eraser touches visible ink.
 */
object StrokeCutter {
    /** Pieces shorter than this (mm) are dropped instead of leaving specks. */
    const val MIN_PIECE_LENGTH_MM = 0.2f

    private const val BISECTION_STEPS = 14

    /** Distance at which the eraser touches [stroke]'s ink. */
    fun reach(stroke: Stroke, eraserRadiusMm: Float): Float = eraserRadiusMm + stroke.brush.sizeMm / 2

    /** True if the eraser swept from (ax, ay) to (bx, by) touches [points] with [reach]. */
    fun touches(points: List<StrokePoint>, reach: Float, ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        if (points.size == 1) return EraseGeometry.distanceToSegment(points[0].x, points[0].y, ax, ay, bx, by) <= reach
        for (i in 1 until points.size) {
            val p = points[i - 1]
            val q = points[i]
            if (EraseGeometry.segmentDistance(p.x, p.y, q.x, q.y, ax, ay, bx, by) <= reach) return true
        }
        return false
    }

    /**
     * Cuts away the part of [points] within [reach] of the eraser segment. Returns null if the
     * stroke is not touched, otherwise the remaining pieces (possibly none). Piece boundaries are
     * placed exactly on the eraser's edge; pressure, tilt and time are interpolated there.
     */
    fun cut(points: List<StrokePoint>, reach: Float, ax: Float, ay: Float, bx: Float, by: Float): List<List<StrokePoint>>? {
        if (!touches(points, reach, ax, ay, bx, by)) return null
        fun inside(p: StrokePoint) = EraseGeometry.distanceToSegment(p.x, p.y, ax, ay, bx, by) <= reach

        val pieces = ArrayList<List<StrokePoint>>()
        var current = ArrayList<StrokePoint>()
        val dense = densifyNear(points, reach, ax, ay, bx, by)
        var wasInside = inside(dense[0])
        if (!wasInside) current += dense[0]
        for (i in 1 until dense.size) {
            val prev = dense[i - 1]
            val p = dense[i]
            val isInside = inside(p)
            if (isInside != wasInside) {
                val edge = boundary(prev, p, wasInside, ::inside)
                if (wasInside) {
                    current = arrayListOf(edge) // leaving the eraser starts a piece
                } else {
                    current += edge // entering it ends one
                    pieces += current
                    current = ArrayList()
                }
            }
            if (!isInside) current += p
            wasInside = isInside
        }
        if (!wasInside) pieces += current
        return pieces.filter { pathLength(it) >= MIN_PIECE_LENGTH_MM }.map(::rebaseTime)
    }

    /**
     * Inserts interpolated points into segments near the eraser so that no segment is longer
     * than half the reach; otherwise an eraser smaller than the point spacing could pass
     * between two points without cutting.
     */
    private fun densifyNear(points: List<StrokePoint>, reach: Float, ax: Float, ay: Float, bx: Float, by: Float): List<StrokePoint> {
        val step = reach / 2
        val out = ArrayList<StrokePoint>(points.size)
        out += points[0]
        for (i in 1 until points.size) {
            val p = points[i - 1]
            val q = points[i]
            val len = hypot(q.x - p.x, q.y - p.y)
            if (len > step && EraseGeometry.segmentDistance(p.x, p.y, q.x, q.y, ax, ay, bx, by) <= reach + len) {
                val n = ceil(len / step).toInt()
                for (k in 1 until n) out += lerp(p, q, k.toFloat() / n)
            }
            out += q
        }
        return out
    }

    /** Point on p-q where the inside state changes, found by bisection. */
    private fun boundary(p: StrokePoint, q: StrokePoint, pInside: Boolean, inside: (StrokePoint) -> Boolean): StrokePoint {
        var lo = 0f
        var hi = 1f
        repeat(BISECTION_STEPS) {
            val mid = (lo + hi) / 2
            if (inside(lerp(p, q, mid)) == pInside) lo = mid else hi = mid
        }
        // Take the outside end of the final interval so the piece stays outside the eraser.
        return lerp(p, q, if (pInside) hi else lo)
    }

    private fun lerp(p: StrokePoint, q: StrokePoint, t: Float): StrokePoint {
        fun mix(a: Float, b: Float) = if (a < 0f || b < 0f) StrokePoint.NO_VALUE else a + (b - a) * t
        return StrokePoint(
            x = p.x + (q.x - p.x) * t,
            y = p.y + (q.y - p.y) * t,
            timeMs = p.timeMs + ((q.timeMs - p.timeMs) * t).toLong(),
            pressure = mix(p.pressure, q.pressure),
            tiltRad = mix(p.tiltRad, q.tiltRad),
            orientationRad = mix(p.orientationRad, q.orientationRad),
        )
    }

    private fun pathLength(points: List<StrokePoint>): Float {
        var sum = 0f
        for (i in 1 until points.size) sum += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        return sum
    }

    /** Pieces start at time 0 like any stroke. */
    private fun rebaseTime(points: List<StrokePoint>): List<StrokePoint> {
        val t0 = points.first().timeMs
        return if (t0 == 0L) points else points.map { it.copy(timeMs = it.timeMs - t0) }
    }
}
