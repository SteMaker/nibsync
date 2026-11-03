package com.stemaker.nibsync.desktop

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.text.ListStart
import com.stemaker.nibsync.text.RichTextLayout
import com.stemaker.nibsync.text.TextMetrics
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Lays out text boxes once at [LAYOUT_PX_PER_MM] (drawn scaled with the page), like the
 * Android TextBoxRenderer: same fonts and paragraph layout from shared/text.
 */
class DesktopTextLayout {
    private val density = TextMetrics.densityFor(LAYOUT_PX_PER_MM)
    private val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr, cacheSize = 0)
    private val cache = HashMap<StrokeId, Triple<TextBox, ListStart, TextLayoutResult>>()

    fun layout(text: RichText, widthMm: Float, start: ListStart = ListStart.NONE): TextLayoutResult {
        val annotated = RichTextLayout.annotated(text, start) { prefix -> measurer.measure(prefix, TextMetrics.baseStyle).size.width / density.density }
        val width = (widthMm * LAYOUT_PX_PER_MM).roundToInt().coerceAtLeast(1)
        // A fixed width, like the editor: with only a maximum width, Compose on the desktop
        // wraps indented (list) paragraphs far too early.
        return measurer.measure(annotated, TextMetrics.baseStyle, constraints = Constraints.fixedWidth(width))
    }

    /** Height of [text] in a box of [widthMm], in mm: what is stored as the box height. */
    fun heightMm(text: RichText, widthMm: Float): Float =
        ceil(layout(text, widthMm).size.height / LAYOUT_PX_PER_MM * 100f) / 100f

    /** The anchor or link in [box] at ([x], [y]) mm, or null. */
    fun linkAt(box: TextBox, x: Float, y: Float, start: ListStart = ListStart.NONE): com.stemaker.nibsync.model.MarkStyle? =
        RichTextLayout.linkAt(box.text, layoutOf(box, start), (x - box.x) * LAYOUT_PX_PER_MM, (y - box.y) * LAYOUT_PX_PER_MM, start)

    /** The code of a code block in [box] whose copy symbol is at ([x], [y]) mm, or null. */
    fun codeToCopyAt(box: TextBox, x: Float, y: Float, start: ListStart = ListStart.NONE): String? =
        RichTextLayout.codeToCopyAt(box.text, layoutOf(box, start), (x - box.x) * LAYOUT_PX_PER_MM, (y - box.y) * LAYOUT_PX_PER_MM, start)

    /** Where the sign of the anchor [anchorId] is in [box] (mm, top left), or null. */
    fun anchorPlace(box: TextBox, anchorId: StrokeId, start: ListStart = ListStart.NONE): Pair<Float, Float>? =
        RichTextLayout.anchorBoxes(box.text, layoutOf(box, start), start).firstOrNull { it.first.anchorId == anchorId }?.second
            ?.let { r -> box.x + r.left / LAYOUT_PX_PER_MM to box.y + r.top / LAYOUT_PX_PER_MM }

    /** The to-do whose check box is at ([x], [y]) mm in [box], or null. */
    fun taskAt(box: TextBox, x: Float, y: Float, start: ListStart = ListStart.NONE): com.stemaker.nibsync.model.MarkStyle.Task? =
        RichTextLayout.taskAt(box.text, layoutOf(box, start), (x - box.x) * LAYOUT_PX_PER_MM, (y - box.y) * LAYOUT_PX_PER_MM, start)

    /** The to-do whose check box is at ([x], [y]) mm in a cell of [table], or null. */
    fun taskAt(table: com.stemaker.nibsync.model.TableElement, x: Float, y: Float): com.stemaker.nibsync.model.MarkStyle.Task? {
        val g = table.geometry
        val area = g.areaAtPoint(x - table.x, y - table.y) ?: return null
        val shown = com.stemaker.nibsync.text.TableText.shown(table, area)
        val r = com.stemaker.nibsync.text.TableText.contentRect(table, area)
        val layout = layoutOf(table.tableId, g.key(area), shown, r.width)
        return RichTextLayout.taskAt(shown, layout, (x - table.x - r.left) * LAYOUT_PX_PER_MM, (y - table.y - r.top) * LAYOUT_PX_PER_MM)
    }

    /** [box] laid out, its lists going on from [start]; cached per box. */
    fun layoutOf(box: TextBox, start: ListStart = ListStart.NONE): TextLayoutResult {
        cache[box.id]?.let { (cached, s, result) -> if (cached == box && s == start) return result }
        return layout(box.text, box.widthMm, start).also { cache[box.id] = Triple(box, start, it) }
    }

    private val todoCache = HashMap<StrokeId, Triple<RichText, Float, TextLayoutResult>>()

    /** Layout of a to-do's text in its content width, cached like [layoutOf]. */
    fun layoutOf(id: StrokeId, text: RichText, widthMm: Float): TextLayoutResult {
        todoCache[id]?.let { (t, w, result) -> if (t == text && w == widthMm) return result }
        return layout(text, widthMm).also { todoCache[id] = Triple(text, widthMm, it) }
    }

    private val cellCache = HashMap<Pair<StrokeId, com.stemaker.nibsync.model.CellKey>, Triple<RichText, Float, TextLayoutResult>>()

    /** Layout of a table cell's text in its content width, cached per table and cell. */
    fun layoutOf(tableId: StrokeId, cell: com.stemaker.nibsync.model.CellKey, text: RichText, widthMm: Float): TextLayoutResult {
        val k = tableId to cell
        cellCache[k]?.let { (t, w, result) -> if (t == text && w == widthMm) return result }
        if (cellCache.size > MAX_CELLS) cellCache.clear()
        return layout(text, widthMm).also { cellCache[k] = Triple(text, widthMm, it) }
    }

    fun retainOnly(ids: Set<StrokeId>) {
        cache.keys.retainAll(ids)
        todoCache.keys.retainAll(ids)
    }

    companion object {
        const val LAYOUT_PX_PER_MM = 20f
        private const val MAX_CELLS = 2000
    }
}
