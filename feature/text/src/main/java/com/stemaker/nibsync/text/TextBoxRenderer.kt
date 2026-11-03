package com.stemaker.nibsync.text

import android.content.Context
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
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
    private val cache = HashMap<StrokeId, Triple<TextBox, ListStart, TextLayoutResult>>()

    /** Lists of a box go on from the text above it in its flow; set by the view. */
    var listStart: (TextBox) -> ListStart = { ListStart.NONE }

    /** Laid-out text for a box of [widthMm], in layout pixels. */
    fun layout(text: RichText, widthMm: Float, start: ListStart = ListStart.NONE): TextLayoutResult {
        val annotated = RichTextLayout.annotated(text, start) { prefix ->
            measurer.measure(prefix, TextMetrics.baseStyle).size.width / density.density
        }
        val width = (widthMm * LAYOUT_PX_PER_MM).roundToInt().coerceAtLeast(1)
        // A fixed width, like the editor (and as the PC needs it for indented paragraphs).
        return measurer.measure(annotated, TextMetrics.baseStyle, constraints = Constraints.fixedWidth(width))
    }

    /** Height of [text] in a box of [widthMm], in mm: what is stored as the box height. */
    fun heightMm(text: RichText, widthMm: Float): Float =
        ceil(layout(text, widthMm).size.height / LAYOUT_PX_PER_MM * 100f) / 100f

    /** The anchor or link in [box] at ([x], [y]) mm, or null. */
    fun linkAt(box: TextBox, x: Float, y: Float): com.stemaker.nibsync.model.MarkStyle? {
        val start = listStart(box)
        return RichTextLayout.linkAt(box.text, layoutOf(box, start), (x - box.x) * LAYOUT_PX_PER_MM, (y - box.y) * LAYOUT_PX_PER_MM, start)
    }

    /** The code of a code block in [box] whose copy symbol is at ([x], [y]) mm, or null. */
    fun codeToCopyAt(box: TextBox, x: Float, y: Float): String? {
        val start = listStart(box)
        return RichTextLayout.codeToCopyAt(box.text, layoutOf(box, start), (x - box.x) * LAYOUT_PX_PER_MM, (y - box.y) * LAYOUT_PX_PER_MM, start)
    }

    private val drawScope = CanvasDrawScope()

    /** Draws [box]; [canvas] must be in page millimetres (see NoteCanvasView). */
    fun draw(canvas: android.graphics.Canvas, box: TextBox) {
        val start = listStart(box)
        val result = layoutOf(box, start)
        val save = canvas.save()
        canvas.translate(box.x, box.y)
        canvas.scale(1f / LAYOUT_PX_PER_MM, 1f / LAYOUT_PX_PER_MM)
        val size = Size(result.size.width.toFloat(), result.size.height.toFloat())
        drawScope.draw(density, LayoutDirection.Ltr, Canvas(canvas), size) { RichTextPainter.draw(this, box.text, result, start) }
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

    private fun layoutOf(box: TextBox, start: ListStart): TextLayoutResult {
        cache[box.id]?.let { (cached, s, result) -> if (cached == box && s == start) return result }
        return layout(box.text, box.widthMm, start).also { cache[box.id] = Triple(box, start, it) }
    }

    companion object {
        /** Resolution of the cached layouts: fine enough that hinting does not show when zooming. */
        const val LAYOUT_PX_PER_MM = 20f
    }
}
