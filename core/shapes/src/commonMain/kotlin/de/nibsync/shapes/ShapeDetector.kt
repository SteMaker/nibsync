package de.nibsync.shapes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Recognises lines, arrows, triangles, rectangles and ellipses in a hand-drawn stroke.
 *
 * All thresholds are relative to the size of the drawing, so recognition does not depend on
 * zoom or shape size. Returns null if the stroke is not clearly one of the supported shapes;
 * when in doubt the user's ink is kept.
 */
object ShapeDetector {
    /** Strokes smaller than this (bounding box diagonal, mm) are never replaced. */
    const val MIN_SIZE_MM = 4.0

    /** Rotations closer than this to horizontal/vertical are snapped. */
    private const val AXIS_SNAP_DEG = 7.0

    /** Rectangles/ellipses whose sides/radii differ less than this become squares/circles. */
    private const val EQUAL_SIDES_TOLERANCE = 0.1

    fun detect(input: List<Pt>): Shape? {
        if (input.size < 2) return null
        val size = Geometry.diagonal(input)
        if (size < MIN_SIZE_MM) return null
        val points = Geometry.resample(input, size / 100)
        val length = Geometry.pathLength(points)
        val loop = if (length > 2 * size) closedLoop(points, size) else null
        return if (loop != null) {
            detectClosed(loop, size)
        } else {
            detectLine(points, length) ?: detectArrow(points, size, length)
        }
    }

    /**
     * If the path returns to its beginning, returns the part that forms the loop, without a
     * small gap or an overshoot past the start. Otherwise null.
     */
    private fun closedLoop(points: List<Pt>, size: Double): List<Pt>? {
        val window = points.size / 5
        var best = Double.MAX_VALUE
        var bestStart = 0
        var bestEnd = points.lastIndex
        for (i in 0..window) {
            for (j in points.lastIndex - window..points.lastIndex) {
                val d = points[i].distanceTo(points[j])
                if (d < best) {
                    best = d
                    bestStart = i
                    bestEnd = j
                }
            }
        }
        if (best > 0.2 * size) return null
        val loop = points.subList(bestStart, bestEnd + 1)
        // The loop must be most of the stroke and closed relative to its own size (a spiral is not).
        if (Geometry.pathLength(loop) < 0.75 * Geometry.pathLength(points)) return null
        if (best > 0.15 * Geometry.diagonal(loop)) return null
        return loop
    }

    /** Recognises a stroke that is only an arrow head (to be joined with a shaft), or null. */
    fun detectArrowHead(input: List<Pt>): ArrowHead? {
        if (input.size < 2) return null
        val size = Geometry.diagonal(input)
        if (size < 2.0) return null
        val points = Geometry.resample(input, size / 100)
        val length = Geometry.pathLength(points)
        val loop = if (length > 2 * size) closedLoop(points, size) else null
        if (loop != null) {
            val corners = Geometry.corners(loop, epsilon = 0.08 * size, minTurnDeg = 35.0)
            return if (corners.size == 3) ArrowHead(corners, closed = true) else null
        }
        var v = Geometry.simplify(points, 0.08 * size)
        // Drop little hooks at the ends (pen landing or lifting).
        while (v.size > 3 && v[0].distanceTo(v[1]) < 0.25 * size) v = v.drop(1)
        while (v.size > 3 && v[v.lastIndex].distanceTo(v[v.lastIndex - 1]) < 0.25 * size) v = v.dropLast(1)
        if (v.size != 3) return null
        val (w1, apex, w2) = v
        val opening = 180 - Geometry.turnDegrees(w1, apex, w2)
        if (opening < 15 || opening > 130) return null
        if (min(apex.distanceTo(w1), apex.distanceTo(w2)) < 0.25 * size) return null
        return ArrowHead(v, closed = false)
    }

    /**
     * Joins [head] with a straight [shaft] stroke if the head sits on one end of it and points
     * along it. Returns the arrow, or null if they do not belong together.
     */
    fun joinArrow(head: ArrowHead, shaft: List<Pt>): Shape.Arrow? {
        val line = straightStroke(shaft) ?: return null
        val shaftLength = line.start.distanceTo(line.end)
        var best: Shape.Arrow? = null
        var bestDistance = Double.MAX_VALUE
        for ((end, other) in listOf(line.end to line.start, line.start to line.end)) {
            val back = (other - end) * (1.0 / shaftLength)
            val apexes = if (head.closed) head.vertices else listOf(head.vertices[1])
            for (apex in apexes) {
                val wings = head.vertices.filter { it != apex }
                val headLength = wings.map { it.distanceTo(apex) }.average()
                val distance = apex.distanceTo(end)
                if (distance > max(3.0, 0.6 * headLength)) continue
                if (headLength > 0.6 * shaftLength) continue
                // Both wings must point back along the shaft.
                if (wings.any { (it - apex).dot(back) < 0.2 * it.distanceTo(apex) }) continue
                if (distance < bestDistance) {
                    bestDistance = distance
                    // The tip is where the head's apex lies along the shaft direction.
                    val forward = back * -1.0
                    val tip = end + forward * (apex - end).dot(forward)
                    val (tail, snappedTip) = snapDirection(other, tip)
                    best = Shape.Arrow(tail, snappedTip, headLength.coerceAtMost(0.5 * shaftLength), head.closed)
                }
            }
        }
        return best
    }

    /**
     * The straight segment a stroke forms, ignoring little hooks where the pen landed or
     * lifted, or null if the stroke is not straight.
     */
    private fun straightStroke(input: List<Pt>): Shape.Line? {
        if (input.size < 2) return null
        val size = Geometry.diagonal(input)
        if (size < MIN_SIZE_MM) return null
        val points = Geometry.resample(input, size / 100)
        var v = Geometry.simplify(points, 0.04 * size)
        while (v.size > 2 && v[0].distanceTo(v[1]) < 0.12 * size) v = v.drop(1)
        while (v.size > 2 && v[v.lastIndex].distanceTo(v[v.lastIndex - 1]) < 0.12 * size) v = v.dropLast(1)
        if (v.size != 2) return null
        val (a, b) = v
        val from = points.indexOf(a)
        val to = points.indexOf(b)
        if (points.subList(from, to + 1).any { Geometry.distanceToSegment(it, a, b) > 0.06 * a.distanceTo(b) }) return null
        return Shape.Line(a, b)
    }

    private fun detectLine(points: List<Pt>, length: Double): Shape.Line? {
        val a = points.first()
        val b = points.last()
        val chord = a.distanceTo(b)
        if (chord < 0.85 * length) return null
        val maxDeviation = points.maxOf { Geometry.distanceToSegment(it, a, b) }
        if (maxDeviation > 0.06 * chord) return null
        val (start, end) = snapDirection(a, b)
        return Shape.Line(start, end)
    }

    /** One-stroke arrow: shaft first, then the head drawn back and forth at the tip. */
    private fun detectArrow(points: List<Pt>, size: Double, length: Double): Shape.Arrow? {
        val v = Geometry.simplify(points, 0.05 * size)
        if (v.size < 3) return null
        val tail = v[0]
        val tip = v[1]
        val shaft = tail.distanceTo(tip)
        if (shaft < 0.4 * length) return null
        // The shaft itself must be straight.
        val shaftEnd = points.indexOfFirst { it == tip }.takeIf { it > 0 } ?: return null
        if (points.subList(0, shaftEnd).any { Geometry.distanceToSegment(it, tail, tip) > 0.06 * shaft }) return null

        val head = v.drop(2)
        if (head.any { it.distanceTo(tip) > 0.5 * shaft }) return null
        val back = (tail - tip) * (1.0 / shaft)
        val wings = head.filter { it.distanceTo(tip) > 0.08 * shaft }
        if (wings.isEmpty()) return null
        // Wings point backwards along the shaft, at an angle that looks like an arrow head.
        for (w in wings) {
            val d = w - tip
            val angle = Geometry.turnDegrees(tip - back, tip, tip + d) // angle between back and wing
            if (d.dot(back) <= 0 || angle > 75) return null
        }
        val headLength = wings.map { it.distanceTo(tip) }.average().coerceIn(0.1 * shaft, 0.35 * shaft)
        val (start, end) = snapDirection(tail, tip)
        return Shape.Arrow(start, end, headLength)
    }

    private fun detectClosed(points: List<Pt>, size: Double): Shape? {
        val ellipse = fitEllipse(points)
        val polygon = fitPolygon(points, size)
        return when {
            polygon != null && (ellipse == null || polygon.second < ellipse.second) -> polygon.first
            ellipse != null -> ellipse.first
            else -> null
        }
    }

    /** Returns the ellipse and its relative fit error, or null if the stroke is not elliptic. */
    private fun fitEllipse(points: List<Pt>): Pair<Shape.Ellipse, Double>? {
        val (center, angle) = Geometry.principalAxes(points)
        val local = points.map { Geometry.rotate(it - center, -angle) }
        val rx = (local.maxOf { it.x } - local.minOf { it.x }) / 2
        val ry = (local.maxOf { it.y } - local.minOf { it.y }) / 2
        if (rx <= 0 || ry <= 0 || min(rx, ry) / max(rx, ry) < 0.1) return null
        // Centre of the bounding box in the rotated frame is more robust than the mean.
        val offset = Pt((local.maxOf { it.x } + local.minOf { it.x }) / 2, (local.maxOf { it.y } + local.minOf { it.y }) / 2)
        val error = local.map { p ->
            val q = p - offset
            abs(sqrt(Geometry.sqr(q.x / rx) + Geometry.sqr(q.y / ry)) - 1)
        }.average()
        if (error > 0.07) return null
        // Must go around once: covering the full angle range.
        val angles = local.map { atan2((it.y - offset.y) / ry, (it.x - offset.x) / rx) }
        if (!coversFullCircle(angles)) return null

        val c = center + Geometry.rotate(offset, angle)
        val ellipse = if (Geometry.relativeDifference(rx, ry) < EQUAL_SIDES_TOLERANCE) {
            val r = (rx + ry) / 2
            Shape.Ellipse(c, r, r, 0.0)
        } else {
            Shape.Ellipse(c, rx, ry, snapAngle(angle, PI / 2))
        }
        return ellipse to error
    }

    private fun coversFullCircle(angles: List<Double>): Boolean {
        val buckets = BooleanArray(12)
        for (a in angles) buckets[(((a + PI) / (2 * PI) * 12).toInt()).coerceIn(0, 11)] = true
        return buckets.all { it }
    }

    /** Returns a triangle or rectangle and its relative fit error, or null. */
    private fun fitPolygon(points: List<Pt>, size: Double): Pair<Shape, Double>? {
        val corners = Geometry.corners(points, epsilon = 0.06 * size, minTurnDeg = 35.0)
        val shape: Shape = when (corners.size) {
            3 -> Shape.Triangle(corners[0], corners[1], corners[2])
            4 -> fitRectangle(corners) ?: return null
            else -> return null
        }
        val vertices = when (shape) {
            is Shape.Triangle -> listOf(shape.a, shape.b, shape.c)
            is Shape.Rectangle -> shape.corners
            else -> return null
        }
        val error = Geometry.meanDistanceToPolygon(points, vertices) / size
        return if (error <= 0.035) shape to error * 2 else null // *2: comparable to ellipse error scale
    }

    private fun fitRectangle(corners: List<Pt>): Shape.Rectangle? {
        // Every interior angle must be roughly 90 degrees.
        for (i in corners.indices) {
            val turn = Geometry.turnDegrees(corners[(i + 3) % 4], corners[i], corners[(i + 1) % 4])
            if (abs(turn - 90) > 25) return null
        }
        // Dominant edge direction, folded into one quadrant (edges are 90 degrees apart).
        var sx = 0.0
        var sy = 0.0
        for (i in corners.indices) {
            val e = corners[(i + 1) % 4] - corners[i]
            val a = atan2(e.y, e.x)
            sx += e.length() * cos(4 * a)
            sy += e.length() * sin(4 * a)
        }
        val angle = snapAngle(atan2(sy, sx) / 4, PI / 2)
        val local = corners.map { Geometry.rotate(it, -angle) }
        val xs = local.map { it.x }.sorted()
        val ys = local.map { it.y }.sorted()
        val left = (xs[0] + xs[1]) / 2
        val right = (xs[2] + xs[3]) / 2
        val top = (ys[0] + ys[1]) / 2
        val bottom = (ys[2] + ys[3]) / 2
        var w = right - left
        var h = bottom - top
        if (Geometry.relativeDifference(w, h) < EQUAL_SIDES_TOLERANCE) {
            w = (w + h) / 2
            h = w
        }
        val center = Geometry.rotate(Pt((left + right) / 2, (top + bottom) / 2), angle)
        return Shape.Rectangle(center, w, h, angle)
    }

    /** Snaps an undirected angle to the nearest multiple of [period] if it is close enough. */
    private fun snapAngle(angle: Double, period: Double): Double {
        val nearest = kotlin.math.round(angle / period) * period
        return if (abs(angle - nearest) < AXIS_SNAP_DEG * PI / 180) nearest else angle
    }

    /** Makes nearly horizontal/vertical segments exactly so, keeping their midpoint and length. */
    private fun snapDirection(a: Pt, b: Pt): Pair<Pt, Pt> {
        val d = b - a
        val angle = atan2(d.y, d.x)
        val snapped = snapAngle(angle, PI / 4)
        if (snapped == angle) return a to b
        val mid = (a + b) * 0.5
        val half = Pt(cos(snapped), sin(snapped)) * (d.length() / 2)
        return (mid - half) to (mid + half)
    }
}
