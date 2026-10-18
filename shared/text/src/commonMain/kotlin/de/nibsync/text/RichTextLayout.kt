package de.nibsync.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import de.nibsync.model.ListKind
import de.nibsync.model.MarkStyle
import de.nibsync.model.RichText

/**
 * Builds the [AnnotatedString] that shows a [RichText] on the page, laid out like the editor
 * does it: list prefixes in the indent gutter, [TextMetrics.LIST_INDENT_PT] per level.
 */
object RichTextLayout {
    private val BULLETS = listOf("•", "◦", "▪")

    /** [prefixWidthPt] measures a list prefix in the base style, in points. */
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
                val base = (TextMetrics.LIST_INDENT_PT * (level + 1)).toFloat()
                ParagraphStyle(textIndent = TextIndent((base - prefixWidthPt(prefix)).coerceAtLeast(0f).sp, base.sp))
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
    }

    private const val MAX_LEVELS = 8
}
