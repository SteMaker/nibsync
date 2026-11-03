package com.stemaker.nibsync.text

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.stemaker.nibsync.model.RichText

/** Draws laid-out text on the page (layout pixels): code shaded, code blocks with a copy symbol. */
object RichTextPainter {
    fun draw(scope: DrawScope, text: RichText, layout: TextLayoutResult, start: ListStart = ListStart.NONE) = with(scope) {
        for (area in RichTextLayout.codeAreas(text, layout, start)) {
            drawCode(area.rect)
            if (area.text != null) drawCopySymbol(RichTextLayout.copyButton(area, layout))
        }
        drawText(layout)
    }

    internal fun DrawScope.drawCode(r: Rect) {
        val radius = minOf(r.height, r.width) * 0.15f
        drawRoundRect(RichTextLayout.CODE_BACKGROUND, Offset(r.left, r.top), r.size, CornerRadius(radius))
    }

    /** Two overlapping sheets, the usual "copy" symbol. */
    private fun DrawScope.drawCopySymbol(r: Rect) {
        val s = r.width
        val line = Stroke(width = s * 0.08f)
        val sheet = Size(s * 0.55f, s * 0.65f)
        val corner = CornerRadius(s * 0.08f)
        drawRoundRect(RichTextLayout.COPY_SYMBOL, Offset(r.left + s * 0.15f, r.top + s * 0.1f), sheet, corner, style = line)
        drawRoundRect(RichTextLayout.CODE_BACKGROUND, Offset(r.left + s * 0.32f, r.top + s * 0.27f), sheet, corner)
        drawRoundRect(RichTextLayout.COPY_SYMBOL, Offset(r.left + s * 0.32f, r.top + s * 0.27f), sheet, corner, style = line)
    }
}
