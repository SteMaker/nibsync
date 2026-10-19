package com.stemaker.nibsync.model

/** Font of a text run. Persisted by name: never rename. Each platform embeds the same fonts. */
enum class TextFont { SANS, SERIF, MONO, HANDWRITING }

/** List formatting of a paragraph. Persisted by name: never rename. */
enum class ListKind { NONE, BULLET, NUMBERED }

/**
 * Formatted text, independent of any editor library: paragraphs with list formatting and
 * character styles given as ranges. Text without a size or font mark uses [DEFAULT_SIZE_PT]
 * and [TextFont.SANS].
 */
data class RichText(val paragraphs: List<TextParagraph>) {
    init {
        require(paragraphs.isNotEmpty()) { "RichText needs at least one paragraph" }
    }

    /** The text without formatting, paragraphs separated by line breaks. */
    val plainText: String get() = paragraphs.joinToString("\n") { it.text }

    val isBlank: Boolean get() = paragraphs.all { it.text.isBlank() }

    companion object {
        const val DEFAULT_SIZE_PT = 12f

        /** Sizes offered in the UI; any positive size is valid in files. */
        val SIZES_PT = listOf(8f, 10f, 12f, 14f, 18f, 24f, 32f)

        /** One millimetre per point: 1 pt = 1/72 inch. */
        const val MM_PER_PT = 25.4f / 72f

        val EMPTY = RichText(listOf(TextParagraph("")))

        /** Unformatted text; line breaks start new paragraphs. */
        fun plain(text: String) = RichText(text.split("\n").map { TextParagraph(it) })
    }
}

/**
 * One paragraph. [indent] is the list nesting depth (0 = top level). [marks] style character
 * ranges of [text]; they may overlap (bold and a size, say) but two marks of the same kind
 * do not.
 */
data class TextParagraph(
    val text: String,
    val list: ListKind = ListKind.NONE,
    val indent: Int = 0,
    val marks: List<TextMark> = emptyList(),
) {
    init {
        require(indent >= 0) { "indent must not be negative" }
        marks.forEach {
            require(it.start >= 0 && it.end <= text.length && it.start < it.end) { "mark $it out of range for ${text.length} chars" }
        }
    }
}

/**
 * Line and page breaks that must not be inside a paragraph's text: pasting can bring them in
 * (Windows copies "\r\n", some pages U+2028). Paragraphs are already separate lines.
 */
private fun isStrayBreak(c: Char) =
    c == '\r' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\u0085' || c == '\u2028' || c == '\u2029'

/** The paragraph without stray line breaks; its marks keep covering the same characters. */
fun TextParagraph.withoutStrayBreaks(): TextParagraph {
    if (text.none(::isStrayBreak)) return this
    // New position of every old position (and of the end).
    val newIndex = IntArray(text.length + 1)
    val out = StringBuilder(text.length)
    for (i in text.indices) {
        newIndex[i] = out.length
        if (!isStrayBreak(text[i])) out.append(text[i])
    }
    newIndex[text.length] = out.length
    val marks = marks.map { TextMark(newIndex[it.start], newIndex[it.end], it.style) }.filter { it.start < it.end }
    return copy(text = out.toString(), marks = marks)
}

/** The text without stray line breaks inside its paragraphs, see [withoutStrayBreaks]. */
fun RichText.withoutStrayBreaks(): RichText =
    if (paragraphs.none { p -> p.text.any(::isStrayBreak) }) this else RichText(paragraphs.map { it.withoutStrayBreaks() })

/** Style [style] applies to characters [start] (inclusive) to [end] (exclusive). */
data class TextMark(val start: Int, val end: Int, val style: MarkStyle)

sealed interface MarkStyle {
    data object Bold : MarkStyle
    data object Italic : MarkStyle
    data class Size(val pt: Float) : MarkStyle {
        init {
            require(pt > 0f) { "size must be positive" }
        }
    }
    data class Font(val font: TextFont) : MarkStyle
}
