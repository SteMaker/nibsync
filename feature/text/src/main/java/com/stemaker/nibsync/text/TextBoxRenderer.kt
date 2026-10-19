package com.stemaker.nibsync.text

import android.content.Context
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TextBox
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Lays out and draws text boxes on an Android canvas whose units are page millimetres. Text is
 * laid out once per box at [LAYOUT_PX_PER_MM] and scaled with the canvas, so zooming does not
 * re-wrap lines. Layouts are cached per box id (boxes are immutable).
 */
class TextBoxRenderer(context: Context) {
    private val density = TextMetrics.densityFor(LAYOUT_PX_PER_MM)
    private val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr, cacheSize = 0)
    private val cache = HashMap<StrokeId, Pair<TextBox, TextLayoutResult>>()

    /** Laid-out text for a box of [widthMm], in layout pixels. */
    fun layout(text: RichText, widthMm: Float): TextLayoutResult {
        val annotated = RichTextLayout.annotated(text) { prefix ->
            measurer.measure(prefix, TextMetrics.baseStyle).size.width / density.density
        }
        val width = (widthMm * LAYOUT_PX_PER_MM).roundToInt().coerceAtLeast(1)
        // A fixed width, like the editor (and as the PC needs it for indented paragraphs).
        return measurer.measure(annotated, TextMetrics.baseStyle, constraints = Constraints.fixedWidth(width))
    }

    /** Height of [text] in a box of [widthMm], in mm: what is stored as the box height. */
    fun heightMm(text: RichText, widthMm: Float): Float =
        ceil(layout(text, widthMm).size.height / LAYOUT_PX_PER_MM * 100f) / 100f

    /** Draws [box]; [canvas] must be in page millimetres (see NoteCanvasView). */
    fun draw(canvas: android.graphics.Canvas, box: TextBox) {
        val result = layoutOf(box)
        val save = canvas.save()
        canvas.translate(box.x, box.y)
        canvas.scale(1f / LAYOUT_PX_PER_MM, 1f / LAYOUT_PX_PER_MM)
        TextPainter.paint(Canvas(canvas), result)
        canvas.restoreToCount(save)
    }

    private val textCache = HashMap<StrokeId, Triple<RichText, Float, TextLayoutResult>>()

    /**
     * Draws [text] wrapped to [widthMm] with its top left at ([x], [y]) mm, e.g. a to-do's text;
     * the layout is cached under [id] like a box's.
     */
    fun drawText(canvas: android.graphics.Canvas, id: StrokeId, text: RichText, x: Float, y: Float, widthMm: Float) {
        val cached = textCache[id]?.takeIf { it.first == text && it.second == widthMm }?.third
        val result = cached ?: layout(text, widthMm).also { textCache[id] = Triple(text, widthMm, it) }
        val save = canvas.save()
        canvas.translate(x, y)
        canvas.scale(1f / LAYOUT_PX_PER_MM, 1f / LAYOUT_PX_PER_MM)
        TextPainter.paint(Canvas(canvas), result)
        canvas.restoreToCount(save)
    }

    /** Drops cached layouts of boxes no longer shown. */
    fun retainOnly(ids: Set<StrokeId>) {
        cache.keys.retainAll(ids)
    }

    /** Drops cached layouts of to-do texts no longer shown. */
    fun retainTextsOnly(ids: Set<StrokeId>) {
        textCache.keys.retainAll(ids)
    }

    private fun layoutOf(box: TextBox): TextLayoutResult {
        cache[box.id]?.let { (cached, result) -> if (cached == box) return result }
        return layout(box.text, box.widthMm).also { cache[box.id] = box to it }
    }

    companion object {
        /** Resolution of the cached layouts: fine enough that hinting does not show when zooming. */
        const val LAYOUT_PX_PER_MM = 20f
    }
}
