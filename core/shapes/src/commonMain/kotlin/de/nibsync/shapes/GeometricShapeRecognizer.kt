package de.nibsync.shapes

import de.nibsync.model.Recognition
import de.nibsync.model.ShapeRecognizer
import de.nibsync.model.Stroke
import de.nibsync.model.StrokeDraft
import de.nibsync.model.StrokePoint

/**
 * [ShapeRecognizer] that replaces recognised geometry by a clean shape drawn with the same pen.
 * The replacement uses the stroke's typical pressure and tilt, so a pressure-sensitive pen
 * draws it with an even width.
 *
 * An arrow head drawn as a separate stroke onto the end of a recent straight stroke turns both
 * into one arrow.
 */
class GeometricShapeRecognizer(private val maxShaftCandidates: Int = 5) : ShapeRecognizer {

    override fun recognize(draft: StrokeDraft, recent: List<Stroke>): Recognition? {
        val points = draft.points.toPts()
        ShapeDetector.detectArrowHead(points)?.let { head ->
            for (shaft in recent.asReversed().take(maxShaftCandidates)) {
                if (shaft.layer != draft.layer) continue
                val arrow = ShapeDetector.joinArrow(head, shaft.points.toPts()) ?: continue
                return Recognition(draw(arrow, draft), replaces = setOf(shaft.id))
            }
        }
        val shape = ShapeDetector.detect(points) ?: return null
        return Recognition(draw(shape, draft))
    }

    private fun List<StrokePoint>.toPts() = map { Pt(it.x.toDouble(), it.y.toDouble()) }

    private fun draw(shape: Shape, draft: StrokeDraft): StrokeDraft {
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
