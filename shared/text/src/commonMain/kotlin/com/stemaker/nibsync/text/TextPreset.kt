package com.stemaker.nibsync.text

import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TextFont

/**
 * Paragraph styles offered in the text tool bar. A preset is a quick way to format whole
 * paragraphs and is stored as ordinary marks (size, bold, font), so every app version shows it.
 */
enum class TextPreset(val sizePt: Float, val bold: Boolean, val font: TextFont) {
    NORMAL(RichText.DEFAULT_SIZE_PT, bold = false, TextFont.SANS),
    HEADING_1(24f, bold = true, TextFont.SANS),
    HEADING_2(18f, bold = true, TextFont.SANS),
    HEADING_3(14f, bold = true, TextFont.SANS),
    CODE(RichText.DEFAULT_SIZE_PT, bold = false, TextFont.MONO),
}
