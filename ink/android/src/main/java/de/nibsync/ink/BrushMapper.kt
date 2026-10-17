package de.nibsync.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import de.nibsync.model.BrushFamily
import de.nibsync.model.BrushSpec

/**
 * Maps platform-neutral [BrushSpec]s to Ink brushes. Stroke coordinates are millimetres.
 *
 * Stock brush versions are pinned explicitly, so a library update cannot silently change how
 * existing notes look. Switching to a newer version must go along with a new [BrushFamily].
 */
object BrushMapper {
    /**
     * Smallest distance (mm) Ink treats as distinct when building stroke geometry. 0.01 mm is
     * well below a pixel even at the maximum zoom on a high-density tablet.
     */
    const val EPSILON_MM: Float = 0.01f

    private val ballpoint by lazy { StockBrushes.marker(StockBrushes.MarkerVersion.V1) }
    private val fountainPen by lazy { StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1) }
    private val highlighter by lazy {
        // DISCARD: overlapping parts of one highlighter stroke do not get darker.
        StockBrushes.highlighter(SelfOverlap.DISCARD, StockBrushes.HighlighterVersion.V1)
    }

    fun family(family: BrushFamily): androidx.ink.brush.BrushFamily = when (family) {
        BrushFamily.BALLPOINT -> ballpoint
        BrushFamily.FOUNTAIN_PEN -> fountainPen
        BrushFamily.HIGHLIGHTER -> highlighter
    }

    fun toInk(spec: BrushSpec): Brush =
        Brush.createWithColorIntArgb(family(spec.family), spec.colorArgb, spec.sizeMm, EPSILON_MM)
}
