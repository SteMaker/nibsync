package de.nibsync.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import de.nibsync.model.ListKind
import de.nibsync.model.MarkStyle
import de.nibsync.model.RichText
import de.nibsync.model.StrokeId
import de.nibsync.model.TextBox
import de.nibsync.model.TextFont
import kotlin.math.roundToInt

/**
 * Fonts and text layout of text boxes on the PC, matching the Android app (feature/text):
 * the same embedded fonts, points as font unit, list prefixes in the indent gutter.
 */
object DesktopFonts {
    private fun family(prefix: String, italic: Boolean) = FontFamily(
        buildList {
            add(Font("${prefix}_400_normal.ttf", FontWeight.Normal))
            add(Font("${prefix}_700_normal.ttf", FontWeight.Bold))
            if (italic) {
                add(Font("${prefix}_400_italic.ttf", FontWeight.Normal, FontStyle.Italic))
                add(Font("${prefix}_700_italic.ttf", FontWeight.Bold, FontStyle.Italic))
            }
        },
    )

    private val sans = family("noto_sans", italic = true)
    private val serif = family("noto_serif", italic = true)
    private val mono = family("noto_sans_mono", italic = false)
    private val handwriting = family("caveat", italic = false)

    fun family(font: TextFont): FontFamily = when (font) {
        TextFont.SANS -> sans
        TextFont.SERIF -> serif
        TextFont.MONO -> mono
        TextFont.HANDWRITING -> handwriting
    }

    val TEXT_COLOR = Color(0xff202020)

    val baseStyle get() = TextStyle(fontFamily = sans, fontSize = RichText.DEFAULT_SIZE_PT.sp, color = TEXT_COLOR)

    /** Points to pixels for a page shown at [pxPerMm]. */
    fun densityFor(pxPerMm: Float) = Density(density = pxPerMm * RichText.MM_PER_PT, fontScale = 1f)
}

/** Lays out text boxes once at [LAYOUT_PX_PER_MM]; drawn scaled with the page. */
class DesktopTextLayout {
    private val density = DesktopFonts.densityFor(LAYOUT_PX_PER_MM)
    private val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr, cacheSize = 0)
    private val cache = HashMap<StrokeId, Pair<TextBox, TextLayoutResult>>()

    fun layout(text: RichText, widthMm: Float): TextLayoutResult {
        val annotated = annotated(text) { prefix -> measurer.measure(prefix, DesktopFonts.baseStyle).size.width / density.density }
        val width = (widthMm * LAYOUT_PX_PER_MM).roundToInt().coerceAtLeast(1)
        return measurer.measure(annotated, DesktopFonts.baseStyle, constraints = Constraints(maxWidth = width))
    }

    fun layoutOf(box: TextBox): TextLayoutResult {
        cache[box.id]?.let { (cached, result) -> if (cached == box) return result }
        return layout(box.text, box.widthMm).also { cache[box.id] = box to it }
    }

    fun retainOnly(ids: Set<StrokeId>) {
        cache.keys.retainAll(ids)
    }

    companion object {
        const val LAYOUT_PX_PER_MM = 20f
        const val LIST_INDENT_PT = 20
        private val BULLETS = listOf("•", "◦", "▪")
        private const val MAX_LEVELS = 8

        /** Same as the Android RichTextLayout: paragraphs split by paragraph styles plus a space. */
        fun annotated(text: RichText, prefixWidthPt: (String) -> Float): AnnotatedString = buildAnnotatedString {
            val numbers = IntArray(MAX_LEVELS)
            text.paragraphs.forEachIndexed { index, p ->
                val level = p.indent.coerceAtMost(MAX_LEVELS - 1)
                val prefix = when (p.list) {
                    ListKind.NONE -> {
                        numbers.fill(0)
                        ""
                    }
                    ListKind.BULLET -> {
                        numbers.fill(0, level)
                        BULLETS[level % BULLETS.size] + " "
                    }
                    ListKind.NUMBERED -> {
                        numbers.fill(0, level + 1)
                        numbers[level]++
                        "${numbers[level]}. "
                    }
                }
                val style = if (prefix.isEmpty()) {
                    ParagraphStyle()
                } else {
                    val base = (LIST_INDENT_PT * (level + 1)).toFloat()
                    ParagraphStyle(textIndent = TextIndent((base - prefixWidthPt(prefix)).coerceAtLeast(0f).sp, base.sp))
                }
                val start = length
                append(prefix)
                val textStart = length
                append(p.text)
                if (index < text.paragraphs.lastIndex) append(' ')
                addStyle(style, start, length)
                for (m in p.marks) addStyle(m.style.toSpanStyle(), textStart + m.start, textStart + m.end)
            }
        }

        private fun MarkStyle.toSpanStyle(): SpanStyle = when (this) {
            MarkStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
            MarkStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
            is MarkStyle.Size -> SpanStyle(fontSize = pt.sp)
            is MarkStyle.Font -> SpanStyle(fontFamily = DesktopFonts.family(font))
        }
    }
}
