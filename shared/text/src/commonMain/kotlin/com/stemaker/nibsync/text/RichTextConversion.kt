package com.stemaker.nibsync.text

import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.document.RichTextBlock
import com.mohamedrejeb.richeditor.document.RichTextBlockType
import com.mohamedrejeb.richeditor.document.RichTextDocument
import com.mohamedrejeb.richeditor.document.RichTextSpanMark
import com.stemaker.nibsync.model.ListKind
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TextMark
import com.stemaker.nibsync.model.TextParagraph
import com.stemaker.nibsync.model.withoutStrayBreaks
import kotlin.math.roundToInt

/**
 * Converts between the stored [RichText] and the editor library's document model. Formatting
 * the app does not offer (links, colours, underline, ...) is dropped on the way back.
 */
@OptIn(ExperimentalRichTextApi::class)
internal object RichTextConversion {

    /**
     * A [TextParagraph.continued] line becomes a plain paragraph indented like its item's text
     * (the editor has no such lines). The editor counts a list anew after it, so numbered items
     * behind one get their number as start number.
     */
    fun toDocument(text: RichText): RichTextDocument {
        val numbers = IntArray(MAX_LEVELS)
        var afterContinued = false
        return RichTextDocument(
            text.withoutStrayBreaks().paragraphs.map { p ->
                val level = p.indent.coerceAtMost(MAX_LEVELS - 1)
                val spans = p.marks.map { it.toSpanMark() }
                when {
                    p.continued -> {
                        afterContinued = true
                        val indent = continuedIndent(p.indent).sp
                        RichTextBlock(text = p.text, type = RichTextBlockType.Paragraph, spans = spans, textIndent = TextIndent(indent, indent))
                    }
                    p.list == ListKind.NONE -> {
                        numbers.fill(0)
                        afterContinued = false
                        RichTextBlock(text = p.text, type = RichTextBlockType.Paragraph, spans = spans)
                    }
                    p.list == ListKind.BULLET -> {
                        numbers.fill(0, level)
                        RichTextBlock(text = p.text, type = RichTextBlockType.ListItem(ordered = false, indent = p.indent), spans = spans)
                    }
                    else -> {
                        numbers.fill(0, level + 1)
                        numbers[level]++
                        val start = if (afterContinued) numbers[level] else null
                        RichTextBlock(text = p.text, type = RichTextBlockType.ListItem(ordered = true, indent = p.indent, startNumber = start), spans = spans)
                    }
                }
            },
        )
    }

    /** Indent (pt) of a continued line at list level [indent]: where its item's text starts. */
    private fun continuedIndent(indent: Int): Int = TextMetrics.LIST_INDENT_PT * (indent + 1)

    private fun TextMark.toSpanMark(): RichTextSpanMark {
        val range = start until end
        return when (val s = style) {
            MarkStyle.Bold -> RichTextSpanMark.Bold(range)
            MarkStyle.Italic -> RichTextSpanMark.Italic(range)
            is MarkStyle.Size -> RichTextSpanMark.FontSize(range, s.pt.sp)
            is MarkStyle.Font -> RichTextSpanMark.Custom(range, FontRun(s.font))
        }
    }

    /**
     * The editor's content in the stored format. Marks are kept inside their paragraph (the
     * editor can report a span reaching past the text, e.g. over a pasted "\r") and stray line
     * breaks from pasting are removed.
     */
    fun fromDocument(document: RichTextDocument): RichText {
        val out = ArrayList<TextParagraph>(document.blocks.size)
        for (block in document.blocks) {
            val type = block.type
            val length = block.text.length
            val marks = normalized(block.spans.mapNotNull { it.toTextMark()?.clampedTo(length) })
            val previous = out.lastOrNull()
            val continuedLevel = continuedLevel(block)
            val paragraph = when {
                type is RichTextBlockType.ListItem ->
                    TextParagraph(block.text, if (type.ordered) ListKind.NUMBERED else ListKind.BULLET, type.indent, marks)
                // An indented paragraph right after a list item is a further line of it.
                continuedLevel != null && previous != null && previous.list != ListKind.NONE ->
                    TextParagraph(block.text, previous.list, continuedLevel, marks, continued = true)
                else -> TextParagraph(block.text, marks = marks)
            }
            out += paragraph.withoutStrayBreaks()
        }
        return RichText(out.ifEmpty { listOf(TextParagraph("")) })
    }

    /** The list level of a paragraph indented like a continued line, else null. */
    private fun continuedLevel(block: RichTextBlock): Int? {
        val indent = block.textIndent ?: return null
        if (indent.firstLine != indent.restLine || indent.firstLine.type != TextUnitType.Sp) return null
        val level = (indent.firstLine.value / TextMetrics.LIST_INDENT_PT).roundToInt() - 1
        return level.takeIf { it >= 0 && continuedIndent(it).toFloat() == indent.firstLine.value }
    }

    private fun TextMark.clampedTo(length: Int): TextMark? {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(0, length)
        return if (s < e) TextMark(s, e, style) else null
    }

    private fun RichTextSpanMark.toTextMark(): TextMark? {
        val style = when (this) {
            is RichTextSpanMark.Bold -> MarkStyle.Bold
            is RichTextSpanMark.FontWeight -> if (weight >= BOLD_WEIGHT) MarkStyle.Bold else null
            is RichTextSpanMark.Italic -> MarkStyle.Italic
            is RichTextSpanMark.FontSize ->
                if (size.type == TextUnitType.Sp && size.value > 0f) MarkStyle.Size(size.value) else null
            is RichTextSpanMark.Custom -> (style as? FontRun)?.let { MarkStyle.Font(it.font) }
            else -> null
        } ?: return null
        return TextMark(range.first, range.last + 1, style)
    }

    /** Sorted by position, adjacent or overlapping marks with the same style merged. */
    private fun normalized(marks: List<TextMark>): List<TextMark> =
        marks.groupBy { it.style }.flatMap { (style, group) ->
            val out = ArrayList<TextMark>()
            for (m in group.sortedBy { it.start }) {
                val last = out.lastOrNull()
                if (last != null && m.start <= last.end) {
                    out[out.size - 1] = TextMark(last.start, maxOf(last.end, m.end), style)
                } else {
                    out += m
                }
            }
            out
        }.sortedWith(compareBy({ it.start }, { it.end }, { it.style.toString() }))

    private const val BOLD_WEIGHT = 600
    private const val MAX_LEVELS = 8
}
