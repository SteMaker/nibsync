package com.stemaker.nibsync.shapes

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A 2D point in document millimetres. */
data class Pt(val x: Double, val y: Double) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(f: Double) = Pt(x * f, y * f)
    fun dot(o: Pt) = x * o.x + y * o.y
    fun cross(o: Pt) = x * o.y - y * o.x
    fun length() = hypot(x, y)
    fun distanceTo(o: Pt) = hypot(x - o.x, y - o.y)
}

internal object Geometry {

    fun pathLength(points: List<Pt>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += points[i - 1].distanceTo(points[i])
        return sum
    }

    /** Diagonal of the axis-aligned bounding box. */
    fun diagonal(points: List<Pt>): Double {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        return hypot(maxX - minX, maxY - minY)
    }

    /** Points at equal arc-length spacing [step] along the path (first and last point kept). */
    fun resample(points: List<Pt>, step: Double): List<Pt> {
        if (points.size < 2) return points
        val out = ArrayList<Pt>()
        out += points[0]
        var carry = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val seg = a.distanceTo(b)
            if (seg == 0.0) continue
            var t = step - carry
            while (t <= seg) {
                out += a + (b - a) * (t / seg)
                t += step
            }
            carry = seg - (t - step)
        }
        if (out.last() != points.last()) out += points.last()
        return out
    }

    fun distanceToSegment(p: Pt, a: Pt, b: Pt): Double {
        val ab = b - a
        val len2 = ab.dot(ab)
        if (len2 == 0.0) return p.distanceTo(a)
        val t = ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
        return p.distanceTo(a + ab * t)
    }

    /** Ramer-Douglas-Peucker simplification of an open polyline. */
    fun simplify(points: List<Pt>, epsilon: Double): List<Pt> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            var maxDist = 0.0
            var index = -1
            for (i in start + 1 until end) {
                val d = distanceToSegment(points[i], points[start], points[end])
                if (d > maxDist) {
                    maxDist = d
                    index = i
                }
            }
            if (index >= 0 && maxDist > epsilon) {
                keep[index] = true
                stack.addLast(start to index)
                stack.addLast(index to end)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * Corner points of a closed path. The path is split at the point farthest from its start so
     * the arbitrary start point does not become a corner, then simplified and cleaned of nearly
     * straight vertices.
     */
    fun corners(closed: List<Pt>, epsilon: Double, minTurnDeg: Double): List<Pt> {
        val start = closed.first()
        val far = closed.indices.maxBy { closed[it].distanceTo(start) }
        val first = simplify(closed.subList(0, far + 1), epsilon)
        val second = simplify(closed.subList(far, closed.size) + start, epsilon)
        var ring = (first + second.drop(1)).dropLast(1) // last == start
        // Remove vertices that are not real corners, repeatedly, since removing one changes its neighbours.
        var changed = true
        while (changed && ring.size > 3) {
            changed = false
            for (i in ring.indices) {
                val prev = ring[(i - 1 + ring.size) % ring.size]
                val next = ring[(i + 1) % ring.size]
                if (turnDegrees(prev, ring[i], next) < minTurnDeg || prev.distanceTo(ring[i]) < epsilon) {
                    ring = ring.filterIndexed { j, _ -> j != i }
                    changed = true
                    break
                }
            }
        }
        return ring
    }

    /** How much the direction changes at [b] when going a -> b -> c, in degrees (0 = straight). */
    fun turnDegrees(a: Pt, b: Pt, c: Pt): Double {
        val u = b - a
        val v = c - b
        val lu = u.length()
        val lv = v.length()
        if (lu == 0.0 || lv == 0.0) return 0.0
        val cos = (u.dot(v) / (lu * lv)).coerceIn(-1.0, 1.0)
        return kotlin.math.acos(cos) * 180.0 / kotlin.math.PI
    }

    /** Mean distance of [points] to the closed polygon [vertices]. */
    fun meanDistanceToPolygon(points: List<Pt>, vertices: List<Pt>): Double =
        points.sumOf { p ->
            vertices.indices.minOf { i -> distanceToSegment(p, vertices[i], vertices[(i + 1) % vertices.size]) }
        } / points.size

    /** Eigen-decomposition of the covariance of [points]: centre and angle of the major axis. */
    fun principalAxes(points: List<Pt>): Pair<Pt, Double> {
        val cx = points.sumOf { it.x } / points.size
        val cy = points.sumOf { it.y } / points.size
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (p in points) {
            val dx = p.x - cx
            val dy = p.y - cy
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val angle = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
        return Pt(cx, cy) to angle
    }

    fun rotate(p: Pt, angle: Double): Pt {
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        return Pt(p.x * c - p.y * s, p.x * s + p.y * c)
    }

    fun relativeDifference(a: Double, b: Double) = abs(a - b) / max(abs(a), abs(b))

    fun sqr(x: Double) = x * x

    fun sqrtSafe(x: Double) = sqrt(max(0.0, x))
}
