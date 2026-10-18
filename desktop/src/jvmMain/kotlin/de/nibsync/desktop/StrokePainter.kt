package de.nibsync.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import de.nibsync.model.BrushFamily
import de.nibsync.model.InputTool
import de.nibsync.model.Stroke
import kotlin.math.hypot

/**
 * Draws strokes from their stored points, in a [DrawScope] whose units are millimetres.
 * Mirrors the Android brushes (ink/android BrushMapper) closely enough to look alike:
 * ballpoint and highlighter keep their width, the fountain pen follows stylus pressure and
 * tapers slightly at the end.
 */
object StrokePainter {
    // Same response as BrushMapper's fountain pen.
    private const val PRESSURE_FOR_MIN_WIDTH = 0f
    private const val PRESSURE_FOR_MAX_WIDTH = 0.5f
    private const val MIN_WIDTH_FACTOR = 0.28f
    private const val MAX_WIDTH_FACTOR = 1.6f
    private const val TAPER_SIZES = 3f
    private const val TAPER_END_FACTOR = 0.75f

    fun draw(scope: DrawScope, stroke: Stroke) {
        val color = Color(stroke.brush.colorArgb)
        when (stroke.brush.family) {
            BrushFamily.FOUNTAIN_PEN -> drawOutline(scope, stroke, color)
            // One path drawn once: a translucent highlighter does not darken where it overlaps itself.
            BrushFamily.HIGHLIGHTER -> scope.drawPath(centerLine(stroke), color, style = StrokeStyle(stroke.brush.sizeMm, cap = StrokeCap.Square, join = StrokeJoin.Round))
            BrushFamily.BALLPOINT -> scope.drawPath(centerLine(stroke), color, style = StrokeStyle(stroke.brush.sizeMm, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (stroke.points.size == 1 && stroke.brush.family != BrushFamily.FOUNTAIN_PEN) {
            val p = stroke.points[0]
            scope.drawCircle(color, stroke.brush.sizeMm / 2, Offset(p.x, p.y))
        }
    }

    private fun centerLine(stroke: Stroke) = Path().apply {
        stroke.points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    }

    /** Width of a fountain pen stroke at each point, in mm. */
    fun widths(stroke: Stroke): FloatArray {
        val pts = stroke.points
        val size = stroke.brush.sizeMm
        val usePressure = stroke.tool == InputTool.STYLUS
        // Distance from each point to the end, for the taper.
        val remaining = FloatArray(pts.size)
        for (i in pts.size - 2 downTo 0) {
            remaining[i] = remaining[i + 1] + hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y)
        }
        return FloatArray(pts.size) { i ->
            val p = pts[i]
            val pressure = if (usePressure && p.hasPressure) {
                val t = ((p.pressure - PRESSURE_FOR_MIN_WIDTH) / (PRESSURE_FOR_MAX_WIDTH - PRESSURE_FOR_MIN_WIDTH)).coerceIn(0f, 1f)
                MIN_WIDTH_FACTOR + (MAX_WIDTH_FACTOR - MIN_WIDTH_FACTOR) * t
            } else {
                1f
            }
            val taperT = (remaining[i] / (TAPER_SIZES * size)).coerceIn(0f, 1f)
            val taper = TAPER_END_FACTOR + (1f - TAPER_END_FACTOR) * taperT
            size * pressure * taper
        }
    }

    /**
     * A variable-width stroke as discs at the points joined by quads. Each piece is filled on
     * its own: combined into one path, overlapping pieces of opposite winding would cancel out.
     * The ink is opaque, so overlaps do not show.
     */
    private fun drawOutline(scope: DrawScope, stroke: Stroke, color: Color) {
        val pts = stroke.points
        val w = widths(stroke)
        val quad = Path()
        for (i in pts.indices) {
            val r = w[i] / 2
            scope.drawCircle(color, r, Offset(pts[i].x, pts[i].y))
            if (i == 0) continue
            val a = pts[i - 1]
            val b = pts[i]
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len < 1e-4f) continue
            val nx = -(b.y - a.y) / len
            val ny = (b.x - a.x) / len
            val ra = w[i - 1] / 2
            quad.reset()
            quad.moveTo(a.x - nx * ra, a.y - ny * ra)
            quad.lineTo(b.x - nx * r, b.y - ny * r)
            quad.lineTo(b.x + nx * r, b.y + ny * r)
            quad.lineTo(a.x + nx * ra, a.y + ny * ra)
            quad.close()
            scope.drawPath(quad, color)
        }
    }
}
