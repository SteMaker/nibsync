package com.stemaker.nibsync.text

import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TextFont
import com.stemaker.nibsync.model.TextParagraph

/**
 * Paragraph styles offered in the text tool bar. A preset is a quick way to format whole
 * paragraphs and is stored as ordinary marks (size, bold, font), so every app version shows it.
 */
enum class TextPreset(val sizePt: Float, val bold: Boolean, val font: TextFont) {
    NORMAL(RichText.DEFAULT_SIZE_PT, bold = false, TextFont.SANS),
    HEADING_1(22f, bold = true, TextFont.SANS),
    HEADING_2(16f, bold = true, TextFont.SANS),
    HEADING_3(13f, bold = true, TextFont.SANS),
    CODE(RichText.DEFAULT_SIZE_PT, bold = false, TextFont.MONO);

    val isHeading: Boolean get() = this == HEADING_1 || this == HEADING_2 || this == HEADING_3

    companion object {
        /** Extra space below the last line of a heading, in units of its font size. */
        const val HEADING_GAP_EM = 0.5f

        /**
         * True for a paragraph formatted as a heading: all its text bold, in one size of at least
         * [HEADING_3]'s. Headings get [HEADING_GAP_EM] of space below them.
         */
        fun isHeading(p: TextParagraph): Boolean = headingSizePt(p) != null

        /** The font size of a heading paragraph, else null. */
        fun headingSizePt(p: TextParagraph): Float? {
            if (p.text.isBlank() || p.continued) return null
            fun covers(style: (MarkStyle) -> Boolean) = p.marks.any { style(it.style) && it.start == 0 && it.end >= p.text.length }
            if (!covers { it == MarkStyle.Bold }) return null
            val size = p.marks.firstOrNull { it.style is MarkStyle.Size && it.start == 0 && it.end >= p.text.length }?.style as? MarkStyle.Size ?: return null
            return size.pt.takeIf { it >= HEADING_3.sizePt }
        }
    }
}
