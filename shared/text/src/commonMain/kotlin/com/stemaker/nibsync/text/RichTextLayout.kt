package com.stemaker.nibsync.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import com.stemaker.nibsync.model.ListKind
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.RichText

/**
 * Builds the [AnnotatedString] that shows a [RichText] on the page, laid out like the editor
 * does it: list prefixes in the indent gutter, [TextMetrics.LIST_INDENT_PT] per level.
 */
object RichTextLayout {
    private val BULLETS = listOf("•", "◦", "▪")

    /** The list prefix shown before each paragraph, empty if it has none. */
    private fun prefixes(text: RichText): List<String> {
        val numbers = IntArray(MAX_LEVELS)
        return text.paragraphs.map { p ->
            val level = p.indent.coerceAtMost(MAX_LEVELS - 1)
            // A further line of the item above has no prefix, and the numbering goes on.
            if (p.continued) "" else when (p.list) {
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
        }
    }

    /** The anchor or link at position [offset] of the text built by [annotated], or null. */
    fun linkAt(text: RichText, offset: Int): MarkStyle? {
        var pos = 0
        val prefixes = prefixes(text)
        text.paragraphs.forEachIndexed { index, p ->
            val start = pos + prefixes[index].length
            if (offset in start until start + p.text.length) {
                return p.marks.firstOrNull { offset - start >= it.start && offset - start < it.end && (it.style is MarkStyle.Anchor || it.style is MarkStyle.NoteLink) }?.style
            }
            pos = start + p.text.length + 1
        }
        return null
    }

    /** The anchor or link shown at ([x], [y]) px in [layout], the layout of [text], or null. */
    fun linkAt(text: RichText, layout: TextLayoutResult, x: Float, y: Float): MarkStyle? {
        val offset = layout.getOffsetForPosition(Offset(x, y))
        // The nearest position can be on either side of the character under the point.
        return listOf(offset, offset - 1).firstOrNull { it >= 0 && it < layout.layoutInput.text.length && layout.getBoundingBox(it).contains(Offset(x, y)) }
            ?.let { linkAt(text, it) }
    }

    /** [prefixWidthPt] measures a list prefix in the base style, in points. */
    fun annotated(text: RichText, prefixWidthPt: (String) -> Float): AnnotatedString = buildAnnotatedString {
        val prefixes = prefixes(text)
        text.paragraphs.forEachIndexed { index, p ->
            val level = p.indent.coerceAtMost(MAX_LEVELS - 1)
            val prefix = prefixes[index]
            val base = (TextMetrics.LIST_INDENT_PT * (level + 1)).toFloat()
            val style = when {
                p.continued -> ParagraphStyle(textIndent = TextIndent(base.sp, base.sp))
                prefix.isEmpty() -> ParagraphStyle()
                else -> ParagraphStyle(textIndent = TextIndent((base - prefixWidthPt(prefix)).coerceAtLeast(0f).sp, base.sp))
            }
            val start = length
            append(prefix)
            val textStart = length
            append(p.text)
            // Like the editor: paragraphs are separated by their paragraph styles alone, plus a
            // trailing space. A '\n' here would add an empty line after every paragraph.
            if (index < text.paragraphs.lastIndex) append(' ')
            addStyle(style, start, length)
            for (m in p.marks) addStyle(m.style.toSpanStyle(), textStart + m.start, textStart + m.end)
        }
    }

    private fun MarkStyle.toSpanStyle(): SpanStyle = when (this) {
        MarkStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
        MarkStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
        is MarkStyle.Size -> SpanStyle(fontSize = pt.sp)
        is MarkStyle.Font -> SpanStyle(fontFamily = TextFonts.family(font))
        is MarkStyle.Anchor, is MarkStyle.NoteLink -> linkStyle(this)
    }

    /** How an anchor or link in the text looks. */
    internal fun linkStyle(style: MarkStyle): SpanStyle =
        if (style is MarkStyle.Anchor) SpanStyle(color = ANCHOR_COLOR, fontWeight = FontWeight.Bold) else SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline)

    private const val MAX_LEVELS = 8
    private val ANCHOR_COLOR = Color(0xff00796b)
    private val LINK_COLOR = Color(0xff1565c0)
}
