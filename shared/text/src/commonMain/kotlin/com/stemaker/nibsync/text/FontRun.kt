package com.stemaker.nibsync.text

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichSpanStyle
import com.mohamedrejeb.richeditor.model.RichTextConfig
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.TextFont

/**
 * Editor span for an anchor or a link in the text: one unit, which text typed beside it does not
 * join. Equal by [style] only.
 */
@OptIn(ExperimentalRichTextApi::class)
internal class LinkRun(val style: MarkStyle) : RichSpanStyle {
    override val acceptsNewTextAtEdges: Boolean get() = false
    override val isAtomic: Boolean get() = true

    override fun getSpanStyle(config: RichTextConfig): SpanStyle = RichTextLayout.linkStyle(style)

    override fun equals(other: Any?): Boolean = other is LinkRun && other.style == style

    override fun hashCode(): Int = style.hashCode()

    override fun toString(): String = "LinkRun($style)"
}

/** Editor span for a font: the library has no font mark of its own. Equal by [font] only. */
@OptIn(ExperimentalRichTextApi::class)
internal class FontRun(val font: TextFont) : RichSpanStyle {
    override fun getSpanStyle(config: RichTextConfig): SpanStyle = SpanStyle(fontFamily = TextFonts.family(font))

    /** Code is shaded as on the page; the editor shades it line by line. */
    override fun DrawScope.drawCustomStyle(layoutResult: TextLayoutResult, textRange: TextRange, richTextConfig: RichTextConfig, topPadding: Float, startPadding: Float) {
        if (font != TextFont.MONO) return
        for (r in RichTextLayout.inlineRects(layoutResult, textRange.start, textRange.end)) {
            with(RichTextPainter) { drawCode(r.translate(startPadding, topPadding)) }
        }
    }

    override fun equals(other: Any?): Boolean = other is FontRun && other.font == font

    override fun hashCode(): Int = font.hashCode()

    override fun toString(): String = "FontRun($font)"
}
