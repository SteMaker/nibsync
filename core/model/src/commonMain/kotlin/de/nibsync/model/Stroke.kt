package de.nibsync.model

import de.nibsync.model.geometry.Rect

/**
 * One sampled input point of a stroke.
 *
 * @property x horizontal position in millimetres from the left page edge.
 * @property y vertical position in millimetres from the top of the document.
 * @property timeMs milliseconds since the first point of the stroke.
 * @property pressure normalised pressure in 0..1, or [NO_VALUE] if not reported.
 * @property tiltRad stylus tilt in radians (0 = perpendicular), or [NO_VALUE].
 * @property orientationRad stylus orientation in radians, or [NO_VALUE].
 */
data class StrokePoint(
    val x: Float,
    val y: Float,
    val timeMs: Long,
    val pressure: Float = NO_VALUE,
    val tiltRad: Float = NO_VALUE,
    val orientationRad: Float = NO_VALUE,
) {
    val hasPressure: Boolean get() = pressure >= 0f
    val hasTilt: Boolean get() = tiltRad >= 0f
    val hasOrientation: Boolean get() = orientationRad >= 0f

    companion object {
        const val NO_VALUE: Float = -1f
    }
}

/**
 * A finished, immutable handwritten stroke. Editing a stroke (moving, recolouring, partial
 * erasing) is expressed as removing it and adding a new one with a fresh [id]; that keeps
 * concurrent edits from several devices mergeable.
 *
 * The raw input points are the source of truth, not a rendered mesh, so any platform can
 * re-render the stroke with its own brush engine.
 */
data class Stroke(
    override val id: StrokeId,
    override val layer: Layer,
    val brush: BrushSpec,
    val tool: InputTool,
    val points: List<StrokePoint>,
    override val createdAtEpochMs: Long,
    override val author: DeviceId,
) : PageElement {
    init {
        require(points.isNotEmpty()) { "A stroke needs at least one point" }
        require(layer.holdsStrokes) { "Strokes cannot be placed on layer $layer" }
    }

    /** Axis-aligned bounds of the stroke including half the brush width on every side. */
    override val bounds: Rect by lazy {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val pad = brush.sizeMm / 2f
        Rect(minX - pad, minY - pad, maxX + pad, maxY + pad)
    }
}

/**
 * A stroke as captured by the input layer, before it has been given an identity and placed in
 * a document.
 */
data class StrokeDraft(
    val brush: BrushSpec,
    val tool: InputTool,
    val points: List<StrokePoint>,
    val layer: Layer = brush.family.defaultLayer,
) : ElementDraft {
    override fun translated(dx: Float, dy: Float): StrokeDraft =
        copy(points = points.map { it.copy(x = it.x + dx, y = it.y + dy) })
}
