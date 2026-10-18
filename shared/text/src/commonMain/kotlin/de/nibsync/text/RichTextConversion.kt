package de.nibsync.text

import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.document.RichTextBlock
import com.mohamedrejeb.richeditor.document.RichTextBlockType
import com.mohamedrejeb.richeditor.document.RichTextDocument
import com.mohamedrejeb.richeditor.document.RichTextSpanMark
import de.nibsync.model.ListKind
import de.nibsync.model.MarkStyle
import de.nibsync.model.RichText
import de.nibsync.model.TextMark
import de.nibsync.model.TextParagraph

/**
 * Converts between the stored [RichText] and the editor library's document model. Formatting
 * the app does not offer (links, colours, underline, ...) is dropped on the way back.
 */
@OptIn(ExperimentalRichTextApi::class)
internal object RichTextConversion {

    fun toDocument(text: RichText): RichTextDocument = RichTextDocument(
        text.paragraphs.map { p ->
            RichTextBlock(
                text = p.text,
                type = when (p.list) {
                    ListKind.NONE -> RichTextBlockType.Paragraph
                    ListKind.BULLET -> RichTextBlockType.ListItem(ordered = false, indent = p.indent)
                    ListKind.NUMBERED -> RichTextBlockType.ListItem(ordered = true, indent = p.indent)
                },
                spans = p.marks.map { it.toSpanMark() },
            )
        },
    )

    private fun TextMark.toSpanMark(): RichTextSpanMark {
        val range = start until end
        return when (val s = style) {
            MarkStyle.Bold -> RichTextSpanMark.Bold(range)
            MarkStyle.Italic -> RichTextSpanMark.Italic(range)
            is MarkStyle.Size -> RichTextSpanMark.FontSize(range, s.pt.sp)
            is MarkStyle.Font -> RichTextSpanMark.Custom(range, FontRun(s.font))
        }
    }

    fun fromDocument(document: RichTextDocument): RichText = RichText(
        document.blocks.map { block ->
            val type = block.type
            TextParagraph(
                text = block.text,
                list = when {
                    type !is RichTextBlockType.ListItem -> ListKind.NONE
                    type.ordered -> ListKind.NUMBERED
                    else -> ListKind.BULLET
                },
                indent = (type as? RichTextBlockType.ListItem)?.indent ?: 0,
                marks = normalized(block.spans.mapNotNull { it.toTextMark() }),
            )
        },
    )

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
}
