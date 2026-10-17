package de.nibsync.shapes

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Turns a [Shape] into the polyline a pen would follow to draw it. */
object ShapeTracer {
    /** Spacing of generated points in millimetres. */
    const val STEP_MM = 0.5

    fun trace(shape: Shape): List<Pt> = when (shape) {
        is Shape.Line -> densify(listOf(shape.start, shape.end))
        is Shape.Arrow -> {
            val back = (shape.tail - shape.tip) * (1.0 / shape.tail.distanceTo(shape.tip))
            val wing1 = shape.tip + Geometry.rotate(back, HEAD_ANGLE) * shape.headLength
            val wing2 = shape.tip + Geometry.rotate(back, -HEAD_ANGLE) * shape.headLength
            densify(listOf(shape.tail, shape.tip, wing1, shape.tip, wing2))
        }
        is Shape.Triangle -> densify(listOf(shape.a, shape.b, shape.c, shape.a))
        is Shape.Rectangle -> shape.corners.let { densify(it + it.first()) }
        is Shape.Ellipse -> {
            val circumference = 2 * PI * max(shape.rx, shape.ry)
            val n = max(36, ceil(circumference / STEP_MM).toInt())
            List(n + 1) { i ->
                val t = 2 * PI * i / n
                shape.center + Geometry.rotate(Pt(shape.rx * cos(t), shape.ry * sin(t)), shape.rotationRad)
            }
        }
    }

    private fun densify(vertices: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>()
        for (i in 1 until vertices.size) {
            val a = vertices[i - 1]
            val b = vertices[i]
            val n = max(1, ceil(a.distanceTo(b) / STEP_MM).toInt())
            for (k in 0 until n) out += a + (b - a) * (k.toDouble() / n)
        }
        out += vertices.last()
        return out
    }

    private const val HEAD_ANGLE = PI / 6
}
