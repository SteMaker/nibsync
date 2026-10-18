package com.stemaker.nibsync.shapes

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates strokes that look hand-drawn: a polyline sampled like a pen (~0.3 mm), with slow
 * wobble perpendicular to the path and small jitter. Deterministic per [seed].
 */
class HandDrawn(seed: Int, private val wobble: Double = 0.012, private val jitter: Double = 0.08) {
    private val random = Random(seed)
    private val phase1 = random.nextDouble(0.0, 2 * PI)
    private val phase2 = random.nextDouble(0.0, 2 * PI)

    fun path(vararg vertices: Pt): List<Pt> = path(vertices.toList())

    fun path(vertices: List<Pt>): List<Pt> {
        val dense = Geometry.resample(vertices, 0.3)
        val size = Geometry.diagonal(vertices)
        val total = Geometry.pathLength(dense)
        var walked = 0.0
        return dense.mapIndexed { i, p ->
            if (i > 0) walked += p.distanceTo(dense[i - 1])
            val dir = if (i < dense.lastIndex) dense[i + 1] - p else p - dense[i - 1]
            val n = dir.length().takeIf { it > 0 }?.let { Pt(-dir.y / it, dir.x / it) } ?: Pt(0.0, 0.0)
            val t = walked / total
            val w = size * wobble * (sin(2 * PI * 1.3 * t + phase1) + 0.5 * sin(2 * PI * 3.1 * t + phase2))
            p + n * w + Pt(random.nextDouble(-jitter, jitter), random.nextDouble(-jitter, jitter))
        }
    }

    fun ellipse(center: Pt, rx: Double, ry: Double, rotation: Double = 0.0, startAngle: Double = 0.3, sweep: Double = 2 * PI + 0.15): List<Pt> {
        val n = 120
        return path(List(n + 1) { i ->
            val t = startAngle + sweep * i / n
            center + Geometry.rotate(Pt(rx * cos(t), ry * sin(t)), rotation)
        })
    }
}

fun rotated(points: List<Pt>, center: Pt, angle: Double) = points.map { center + Geometry.rotate(it - center, angle) }

fun deg(d: Double) = d * PI / 180
