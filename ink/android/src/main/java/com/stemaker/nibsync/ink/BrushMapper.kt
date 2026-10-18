package com.stemaker.nibsync.ink

import android.annotation.SuppressLint
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec

/**
 * Maps platform-neutral [BrushSpec]s to Ink brushes. Stroke coordinates are millimetres.
 *
 * Stock brush versions are pinned explicitly, so a library update cannot silently change how
 * existing notes look. Switching to a newer version must go along with a new [BrushFamily].
 * The fountain pen is NibSync's own brush definition (see [nibSyncFountainPenV1]).
 */
object BrushMapper {
    /**
     * Smallest distance (mm) Ink treats as distinct when building stroke geometry. 0.01 mm is
     * well below a pixel even at the maximum zoom on a high-density tablet.
     */
    const val EPSILON_MM: Float = 0.01f

    private val ballpoint by lazy { StockBrushes.marker(StockBrushes.MarkerVersion.V1) }
    /**
     * Stylus pressure range mapped onto the fountain pen's width. Real handwriting with e.g. the
     * HONOR Magic-Pencil 3 stays around 0.05-0.3, so Ink's stock pressure pen (which only reacts
     * to 0.8-1.0) never changes width. At [PRESSURE_FOR_MIN_WIDTH] the stroke is
     * [MIN_WIDTH_FACTOR] of the nominal size, at [PRESSURE_FOR_MAX_WIDTH] [MAX_WIDTH_FACTOR].
     */
    const val PRESSURE_FOR_MIN_WIDTH: Float = 0f
    const val PRESSURE_FOR_MAX_WIDTH: Float = 0.5f
    const val MIN_WIDTH_FACTOR: Float = 0.28f
    const val MAX_WIDTH_FACTOR: Float = 1.6f

    private val fountainPen by lazy { nibSyncFountainPenV1() }
    private val highlighter by lazy {
        // DISCARD: overlapping parts of one highlighter stroke do not get darker.
        StockBrushes.highlighter(SelfOverlap.DISCARD, StockBrushes.HighlighterVersion.V1)
    }

    /**
     * Ink's pressure pen V1 with a pressure response that matches real writing pressure.
     * Custom brush construction is still restricted/experimental API in Ink 1.0.0; it is used
     * only here and covered by an instrumented test (pressure must change the width).
     */
    @OptIn(ExperimentalInkCustomBrushApi::class)
    @SuppressLint("RestrictedApi")
    private fun nibSyncFountainPenV1(): androidx.ink.brush.BrushFamily = androidx.ink.brush.BrushFamily(
        tip = BrushTip(
            behaviors = listOf(
                StockBrushes.predictionFadeOutBehavior,
                // Slight taper towards the end of the stroke (as in the stock pen).
                BrushBehavior(
                    BrushBehavior.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE,
                    BrushBehavior.Target.SIZE_MULTIPLIER,
                    sourceValueRangeStart = 3f,
                    sourceValueRangeEnd = 0f,
                    targetModifierRangeStart = 1f,
                    targetModifierRangeEnd = 0.75f,
                ),
                BrushBehavior(
                    BrushBehavior.Source.NORMALIZED_PRESSURE,
                    BrushBehavior.Target.SIZE_MULTIPLIER,
                    sourceValueRangeStart = PRESSURE_FOR_MIN_WIDTH,
                    sourceValueRangeEnd = PRESSURE_FOR_MAX_WIDTH,
                    targetModifierRangeStart = MIN_WIDTH_FACTOR,
                    targetModifierRangeEnd = MAX_WIDTH_FACTOR,
                    responseTimeMillis = 20L,
                    enabledToolTypes = setOf(InputToolType.STYLUS),
                ),
            ),
        ),
        clientBrushFamilyId = "nibsync/fountain-pen/v1",
        inputModel = androidx.ink.brush.BrushFamily.SlidingWindowModel(),
    )

    fun family(family: BrushFamily): androidx.ink.brush.BrushFamily = when (family) {
        BrushFamily.BALLPOINT -> ballpoint
        BrushFamily.FOUNTAIN_PEN -> fountainPen
        BrushFamily.HIGHLIGHTER -> highlighter
    }

    fun toInk(spec: BrushSpec): Brush =
        Brush.createWithColorIntArgb(family(spec.family), spec.colorArgb, spec.sizeMm, EPSILON_MM)
}
