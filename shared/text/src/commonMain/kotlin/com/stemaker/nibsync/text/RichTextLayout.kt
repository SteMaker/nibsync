package com.stemaker.nibsync.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import com.stemaker.nibsync.model.ListKind
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TextFont
import com.stemaker.nibsync.model.TextParagraph

/**
 * List numbers carried into a text from text above it: per level the last number, 0 for none.
 */
data class ListStart(val numbers: List<Int>) {
    internal fun array(): IntArray = IntArray(RichTextLayout.MAX_LEVELS) { numbers.getOrElse(it) { 0 } }

    companion object {
        val NONE = ListStart(emptyList())
    }
}

/** Shaded code in a laid-out text (layout pixels); a block has the [text] its copy symbol copies. */
data class CodeArea(val rect: Rect, val text: String?)

/**
 * Builds the [AnnotatedString] that shows a [RichText] on the page, laid out like the editor
 * does it: list prefixes in the indent gutter, [TextMetrics.LIST_INDENT_PT] per level.
 */
object RichTextLayout {
    private val BULLETS = listOf("•", "◦", "▪")

    /**
     * The list prefix shown before each paragraph, empty if it has none. A numbered item below a
     * numbered one shows the numbers of the levels above it too ("3.1."). [start] holds the
     * numbers going on from text above it in its flow (see [listStart]).
     */
    private fun prefixes(text: RichText, start: ListStart = ListStart.NONE): List<String> {
        val numbers = start.array()
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
                    // Up through the numbered levels above, e.g. "3.1."
                    val top = (level downTo 0).takeWhile { numbers[it] > 0 }.last()
                    (top..level).joinToString(".", postfix = ". ") { numbers[it].toString() }
                }
            }
        }
    }

    /** The list numbers after [text] that started with [start]: what the next text block goes on with. */
    fun numbersAfter(text: RichText, start: ListStart = ListStart.NONE): ListStart {
        val numbers = start.array()
        for (p in text.paragraphs) {
            if (p.continued) continue
            val level = p.indent.coerceAtMost(MAX_LEVELS - 1)
            when (p.list) {
                ListKind.NONE -> numbers.fill(0)
                ListKind.BULLET -> numbers.fill(0, level)
                ListKind.NUMBERED -> {
                    numbers.fill(0, level + 1)
                    numbers[level]++
                }
            }
        }
        return ListStart(numbers.toList())
    }

    /**
     * Where the lists of [box] start: its flow's text blocks above it are one text, split by
     * tables or pictures, so a numbered list goes on below them.
     */
    fun listStart(state: DocumentState, box: TextBox): ListStart {
        val flow = state.flowOf(box.id) ?: return ListStart.NONE
        var numbers = ListStart.NONE
        for (block in flow.blocks) {
            if (block.id == box.id) return numbers
            if (block is TextBox) numbers = numbersAfter(block.text, numbers)
        }
        return ListStart.NONE
    }

    /** Start of each paragraph's text (behind its prefix) in the string built by [annotated]. */
    private fun textStarts(text: RichText, prefixes: List<String>): List<Int> {
        var pos = 0
        return text.paragraphs.mapIndexed { index, p ->
            (pos + prefixes[index].length).also { pos = it + p.text.length + 1 }
        }
    }

    /**
     * Code in a laid-out text, in layout pixels: whole paragraphs in the code font form a block,
     * shaded across the width with [CodeArea.text] to copy; code inside other text is shaded
     * line by line.
     */
    fun codeAreas(text: RichText, layout: TextLayoutResult, start: ListStart = ListStart.NONE): List<CodeArea> {
        val starts = textStarts(text, prefixes(text, start))
        val length = layout.layoutInput.text.length
        val code = text.paragraphs.map(::isCode)
        val out = ArrayList<CodeArea>()
        var i = 0
        while (i < text.paragraphs.size) {
            if (!code[i]) {
                for (m in text.paragraphs[i].marks) {
                    if (m.style == MarkStyle.Font(TextFont.MONO)) out += inlineRects(layout, starts[i] + m.start, starts[i] + m.end).map { CodeArea(it, null) }
                }
                i++
                continue
            }
            // Empty lines between code paragraphs belong to the block.
            var last = i
            while (last + 1 < code.size && (code[last + 1] || text.paragraphs[last + 1].text.isEmpty() && (last + 2 until code.size).firstOrNull { text.paragraphs[it].text.isNotEmpty() }?.let { code[it] } == true)) last++
            val from = starts[i].coerceIn(0, maxOf(0, length - 1))
            val to = (starts[last] + text.paragraphs[last].text.length).coerceIn(0, length)
            val top = layout.getLineTop(layout.getLineForOffset(from))
            val bottom = layout.getLineBottom(layout.getLineForOffset(maxOf(from, to - 1)))
            val left = layout.getHorizontalPosition(from, true)
            val pad = CODE_PAD_EM * (bottom - top) / (layout.getLineForOffset(maxOf(from, to - 1)) - layout.getLineForOffset(from) + 1)
            out += CodeArea(
                Rect(maxOf(0f, left - pad), top, layout.size.width.toFloat(), bottom),
                (i..last).joinToString("\n") { text.paragraphs[it].text },
            )
            i = last + 1
        }
        return out
    }

    /** Rectangles behind the characters [start] until [end], one per line. */
    internal fun inlineRects(layout: TextLayoutResult, start: Int, end: Int): List<Rect> {
        val length = layout.layoutInput.text.length
        if (start >= end || start >= length) return emptyList()
        val last = minOf(end, length) - 1
        return (layout.getLineForOffset(start)..layout.getLineForOffset(last)).mapNotNull { line ->
            val a = maxOf(start, layout.getLineStart(line))
            val b = minOf(last, layout.getLineEnd(line, visibleEnd = true) - 1)
            if (a > b) return@mapNotNull null
            val first = layout.getBoundingBox(a)
            val lastBox = layout.getBoundingBox(b)
            val pad = CODE_PAD_EM * first.height
            Rect(first.left - pad, layout.getLineTop(line), lastBox.right + pad, layout.getLineBottom(line))
        }
    }

    /** True for a paragraph all in the code font. */
    fun isCode(p: TextParagraph): Boolean =
        p.text.isNotEmpty() && p.marks.any { it.style == MarkStyle.Font(TextFont.MONO) && it.start == 0 && it.end >= p.text.length }

    /** Where the copy symbol of a code block is drawn and tapped, in layout pixels. */
    fun copyButton(area: CodeArea, layout: TextLayoutResult): Rect {
        val line = layout.getLineForOffset(layout.getOffsetForPosition(Offset(area.rect.left + 1f, area.rect.top + 1f)))
        val size = (layout.getLineBottom(line) - layout.getLineTop(line)) * 0.9f
        val m = size * 0.1f
        return Rect(area.rect.right - size - m, area.rect.top + m, area.rect.right - m, area.rect.top + m + size)
    }

    /** The code to copy when ([x], [y]) px is on a code block's copy symbol, else null. */
    fun codeToCopyAt(text: RichText, layout: TextLayoutResult, x: Float, y: Float, start: ListStart = ListStart.NONE): String? =
        codeAreas(text, layout, start).firstOrNull { it.text != null && copyButton(it, layout).inflate(COPY_SLOP_PX).contains(Offset(x, y)) }?.text

    /** The anchor or link at position [offset] of the text built by [annotated], or null. */
    fun linkAt(text: RichText, offset: Int, start: ListStart = ListStart.NONE): MarkStyle? {
        var pos = 0
        val prefixes = prefixes(text, start)
        text.paragraphs.forEachIndexed { index, p ->
            val start = pos + prefixes[index].length
            if (offset in start until start + p.text.length) {
                return p.marks.firstOrNull { offset - start >= it.start && offset - start < it.end && (it.style is MarkStyle.Anchor || it.style is MarkStyle.NoteLink || it.style is MarkStyle.Task) }?.style
            }
            pos = start + p.text.length + 1
        }
        return null
    }

    /** Where the check box of each to-do in [text] is drawn in its [layout] (px), with its task. */
    fun taskBoxes(text: RichText, layout: TextLayoutResult, start: ListStart = ListStart.NONE): List<Pair<MarkStyle.Task, Rect>> {
        val starts = textStarts(text, prefixes(text, start))
        val length = layout.layoutInput.text.length
        return text.paragraphs.flatMapIndexed { i, p ->
            p.marks.mapNotNull { m ->
                val task = m.style as? MarkStyle.Task ?: return@mapNotNull null
                val at = starts[i] + m.start
                if (at >= length) return@mapNotNull null
                val line = layout.getLineForOffset(at)
                val top = layout.getLineTop(line)
                val bottom = layout.getLineBottom(line)
                val left = layout.getHorizontalPosition(at, true)
                // A square as high as the letters, on the first line.
                val size = (bottom - top) * 0.62f
                val y = top + (bottom - top - size) / 2
                task to Rect(left, y, left + size, y + size)
            }
        }
    }

    /** The to-do whose check box is at ([x], [y]) px in [layout], or null. */
    fun taskAt(text: RichText, layout: TextLayoutResult, x: Float, y: Float, start: ListStart = ListStart.NONE): MarkStyle.Task? =
        taskBoxes(text, layout, start).firstOrNull { (_, r) -> r.inflate(r.width * 0.4f).contains(Offset(x, y)) }?.first

    /** The anchor or link shown at ([x], [y]) px in [layout], the layout of [text], or null. */
    fun linkAt(text: RichText, layout: TextLayoutResult, x: Float, y: Float, start: ListStart = ListStart.NONE): MarkStyle? {
        val offset = layout.getOffsetForPosition(Offset(x, y))
        // The nearest position can be on either side of the character under the point.
        return listOf(offset, offset - 1).firstOrNull { it >= 0 && it < layout.layoutInput.text.length && layout.getBoundingBox(it).contains(Offset(x, y)) }
            ?.let { linkAt(text, it, start) }
    }

    /** [prefixWidthPt] measures a list prefix in the base style, in points. */
    fun annotated(text: RichText, start: ListStart = ListStart.NONE, prefixWidthPt: (String) -> Float): AnnotatedString = buildAnnotatedString {
        val prefixes = prefixes(text, start)
        text.paragraphs.forEachIndexed { index, p ->
            val level = p.indent.coerceAtMost(MAX_LEVELS - 1)
            val prefix = prefixes[index]
            val base = (TextMetrics.LIST_INDENT_PT * (level + 1)).toFloat()
            val indented = when {
                p.continued -> ParagraphStyle(textIndent = TextIndent(base.sp, base.sp))
                prefix.isEmpty() -> ParagraphStyle()
                else -> ParagraphStyle(textIndent = TextIndent((base - prefixWidthPt(prefix)).coerceAtLeast(0f).sp, base.sp))
            }
            val style = TextPreset.headingSizePt(p)?.let { indented.merge(TextMetrics.headingStyle(it)) } ?: indented
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
        // Blank characters: the painter draws the check box over them.
        is MarkStyle.Task -> SpanStyle()
    }

    /** How an anchor or link in the text looks. */
    internal fun linkStyle(style: MarkStyle): SpanStyle =
        if (style is MarkStyle.Anchor) SpanStyle(color = ANCHOR_COLOR, fontWeight = FontWeight.Bold) else SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline)

    const val MAX_LEVELS = 8
    private const val CODE_PAD_EM = 0.15f
    private const val COPY_SLOP_PX = 10f

    /** Behind code (the page and the editor). */
    val CODE_BACKGROUND = Color(0xffeef0f3)
    val COPY_SYMBOL = Color(0xff6b7280)
    private val ANCHOR_COLOR = Color(0xff00796b)
    private val LINK_COLOR = Color(0xff1565c0)
}
