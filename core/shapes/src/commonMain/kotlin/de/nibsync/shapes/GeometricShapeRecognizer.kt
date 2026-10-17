package de.nibsync.shapes

import de.nibsync.model.ShapeRecognizer
import de.nibsync.model.StrokeDraft
import de.nibsync.model.StrokePoint

/**
 * [ShapeRecognizer] that replaces recognised geometry by a clean shape drawn with the same pen.
 * The replacement uses the stroke's typical pressure and tilt, so a pressure-sensitive pen
 * draws it with an even width.
 */
class GeometricShapeRecognizer : ShapeRecognizer {

    override fun recognize(draft: StrokeDraft): StrokeDraft? {
        val shape = ShapeDetector.detect(draft.points.map { Pt(it.x.toDouble(), it.y.toDouble()) }) ?: return null
        val outline = ShapeTracer.trace(shape)
        val pressure = median(draft.points.filter { it.hasPressure }.map { it.pressure })
        val tilt = median(draft.points.filter { it.hasTilt }.map { it.tiltRad })
        val orientation = median(draft.points.filter { it.hasOrientation }.map { it.orientationRad })
        var time = 0.0
        val points = outline.mapIndexed { i, p ->
            if (i > 0) time += p.distanceTo(outline[i - 1]) / SPEED_MM_PER_MS
            StrokePoint(p.x.toFloat(), p.y.toFloat(), time.toLong(), pressure, tilt, orientation)
        }
        return draft.copy(points = points)
    }

    private fun median(values: List<Float>): Float =
        if (values.isEmpty()) StrokePoint.NO_VALUE else values.sorted()[values.size / 2]

    private companion object {
        /**
         * Slow, constant synthetic pen speed. Brush engines smooth input over time; drawing the
         * shape slowly keeps that smoothing from rounding off corners.
         */
        const val SPEED_MM_PER_MS = 0.02
    }
}
