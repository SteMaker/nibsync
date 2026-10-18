package com.stemaker.nibsync.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokePoint
import androidx.ink.strokes.Stroke as InkStroke

/** Converts between domain strokes (millimetres) and Ink strokes (also in millimetres). */
object StrokeConverter {
    /** One stroke unit is one millimetre. */
    const val STROKE_UNIT_LENGTH_CM: Float = 0.1f

    fun toInk(stroke: Stroke): InkStroke = InkStroke(BrushMapper.toInk(stroke.brush), toInputBatch(stroke.tool, stroke.points))

    /**
     * Builds an Ink input batch. Ink rejects some inputs (e.g. duplicate timestamps with
     * different positions); such points are dropped rather than failing the whole stroke.
     */
    fun toInputBatch(tool: InputTool, points: List<StrokePoint>): StrokeInputBatch {
        val batch = MutableStrokeInputBatch()
        val type = tool.toInk()
        for (p in points) {
            try {
                batch.add(
                    type = type,
                    x = p.x,
                    y = p.y,
                    elapsedTimeMillis = p.timeMs,
                    strokeUnitLengthCm = STROKE_UNIT_LENGTH_CM,
                    pressure = if (p.hasPressure) p.pressure.coerceIn(0f, 1f) else StrokeInput.NO_PRESSURE,
                    tiltRadians = if (p.hasTilt) p.tiltRad.coerceAtMost(HALF_PI) else StrokeInput.NO_TILT,
                    orientationRadians = if (p.hasOrientation) p.orientationRad.coerceAtMost(TWO_PI) else StrokeInput.NO_ORIENTATION,
                )
            } catch (_: IllegalArgumentException) {
                // Skip an input Ink considers invalid (non-monotonic time, duplicate point, ...).
            }
        }
        return batch
    }

    /** Extracts the raw input of a stroke finished in Ink's authoring view. */
    fun toDraft(ink: InkStroke, brush: BrushSpec): StrokeDraft {
        val inputs = ink.inputs
        val scratch = StrokeInput()
        val points = List(inputs.size) { i ->
            inputs.populate(i, scratch)
            StrokePoint(
                x = scratch.x,
                y = scratch.y,
                timeMs = scratch.elapsedTimeMillis,
                pressure = if (scratch.hasPressure) scratch.pressure else StrokePoint.NO_VALUE,
                tiltRad = if (scratch.hasTilt) scratch.tiltRadians else StrokePoint.NO_VALUE,
                orientationRad = if (scratch.hasOrientation) scratch.orientationRadians else StrokePoint.NO_VALUE,
            )
        }
        return StrokeDraft(brush, inputs.getToolType().toDomain(), points)
    }

    fun InputTool.toInk(): InputToolType = when (this) {
        InputTool.STYLUS -> InputToolType.STYLUS
        InputTool.TOUCH -> InputToolType.TOUCH
        InputTool.MOUSE -> InputToolType.MOUSE
        InputTool.UNKNOWN -> InputToolType.UNKNOWN
    }

    fun InputToolType.toDomain(): InputTool = when (this) {
        InputToolType.STYLUS -> InputTool.STYLUS
        InputToolType.TOUCH -> InputTool.TOUCH
        InputToolType.MOUSE -> InputTool.MOUSE
        else -> InputTool.UNKNOWN
    }

    private const val HALF_PI = (Math.PI / 2).toFloat()
    private const val TWO_PI = (Math.PI * 2).toFloat()
}
