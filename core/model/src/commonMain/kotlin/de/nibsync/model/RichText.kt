package de.nibsync.model

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
