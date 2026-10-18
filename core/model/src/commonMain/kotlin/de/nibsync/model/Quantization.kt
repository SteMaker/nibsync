package de.nibsync.model

import kotlin.math.roundToInt

/**
 * Fixed precision of stored stroke data. New strokes are snapped to this grid before they enter
 * a document, so the in-memory stroke, the stored stroke and the stroke seen by other devices
 * are bit-identical.
 */
object Quantization {
    /** Position resolution: 1/100 mm. */
    const val POSITION_STEPS_PER_MM: Int = 100

    /** Pressure resolution: 1/4096. */
    const val PRESSURE_STEPS: Int = 4096

    /** Angle resolution: 1/1000 rad. */
    const val ANGLE_STEPS_PER_RAD: Int = 1000

    fun positionToSteps(mm: Float): Int = (mm * POSITION_STEPS_PER_MM).roundToInt()
    fun stepsToPosition(steps: Int): Float = steps.toFloat() / POSITION_STEPS_PER_MM
    fun quantizePosition(mm: Float): Float = stepsToPosition(positionToSteps(mm))

    /** Missing values ([StrokePoint.NO_VALUE]) map to -1. */
    fun pressureToSteps(p: Float): Int = if (p < 0f) -1 else (p.coerceAtMost(1f) * PRESSURE_STEPS).roundToInt()
    fun stepsToPressure(steps: Int): Float = if (steps < 0) StrokePoint.NO_VALUE else steps.toFloat() / PRESSURE_STEPS

    fun angleToSteps(rad: Float): Int = if (rad < 0f) -1 else (rad * ANGLE_STEPS_PER_RAD).roundToInt()
    fun stepsToAngle(steps: Int): Float = if (steps < 0) StrokePoint.NO_VALUE else steps.toFloat() / ANGLE_STEPS_PER_RAD

    fun quantize(p: StrokePoint): StrokePoint = StrokePoint(
        x = stepsToPosition(positionToSteps(p.x)),
        y = stepsToPosition(positionToSteps(p.y)),
        timeMs = p.timeMs,
        pressure = stepsToPressure(pressureToSteps(p.pressure)),
        tiltRad = stepsToAngle(angleToSteps(p.tiltRad)),
        orientationRad = stepsToAngle(angleToSteps(p.orientationRad)),
    )

    fun quantize(points: List<StrokePoint>): List<StrokePoint> = points.map(::quantize)
}
