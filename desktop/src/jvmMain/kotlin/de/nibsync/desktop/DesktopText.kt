package de.nibsync.desktop

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import de.nibsync.model.RichText
import de.nibsync.model.StrokeId
import de.nibsync.model.TextBox
import de.nibsync.text.RichTextLayout
import de.nibsync.text.TextMetrics
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Lays out text boxes once at [LAYOUT_PX_PER_MM] (drawn scaled with the page), like the
 * Android TextBoxRenderer: same fonts and paragraph layout from shared/text.
 */
class DesktopTextLayout {
    private val density = TextMetrics.densityFor(LAYOUT_PX_PER_MM)
    private val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr, cacheSize = 0)
    private val cache = HashMap<StrokeId, Pair<TextBox, TextLayoutResult>>()

    fun layout(text: RichText, widthMm: Float): TextLayoutResult {
        val annotated = RichTextLayout.annotated(text) { prefix -> measurer.measure(prefix, TextMetrics.baseStyle).size.width / density.density }
        val width = (widthMm * LAYOUT_PX_PER_MM).roundToInt().coerceAtLeast(1)
        // A fixed width, like the editor: with only a maximum width, Compose on the desktop
        // wraps indented (list) paragraphs far too early.
        return measurer.measure(annotated, TextMetrics.baseStyle, constraints = Constraints.fixedWidth(width))
    }

    /** Height of [text] in a box of [widthMm], in mm: what is stored as the box height. */
    fun heightMm(text: RichText, widthMm: Float): Float =
        ceil(layout(text, widthMm).size.height / LAYOUT_PX_PER_MM * 100f) / 100f

    fun layoutOf(box: TextBox): TextLayoutResult {
        cache[box.id]?.let { (cached, result) -> if (cached == box) return result }
        return layout(box.text, box.widthMm).also { cache[box.id] = box to it }
    }

    fun retainOnly(ids: Set<StrokeId>) {
        cache.keys.retainAll(ids)
    }

    companion object {
        const val LAYOUT_PX_PER_MM = 20f
    }
}
