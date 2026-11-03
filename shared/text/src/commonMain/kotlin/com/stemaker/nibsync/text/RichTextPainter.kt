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
import com.stemaker.nibsync.model.StrokeId

/** Draws laid-out text on the page (layout pixels): code shaded, code blocks with a copy symbol. */
object RichTextPainter {
    /** [done] tells whether a to-do's task is done (its box gets a tick). */
    fun draw(scope: DrawScope, text: RichText, layout: TextLayoutResult, start: ListStart = ListStart.NONE, done: (StrokeId) -> Boolean = { false }) = with(scope) {
        for (area in RichTextLayout.codeAreas(text, layout, start)) {
            drawCode(area.rect)
            if (area.text != null) drawCopySymbol(RichTextLayout.copyButton(area, layout))
        }
        for ((task, r) in RichTextLayout.taskBoxes(text, layout, start)) drawCheckBox(r, done(task.taskId))
        drawText(layout)
    }

    /** A to-do's check box, ticked if [done]. */
    internal fun DrawScope.drawCheckBox(r: Rect, done: Boolean) {
        val s = r.width
        drawRoundRect(TextMetrics.TEXT_COLOR, Offset(r.left, r.top), r.size, CornerRadius(s * 0.18f), style = Stroke(width = s * 0.1f))
        if (done) {
            val tick = androidx.compose.ui.graphics.Path().apply {
                moveTo(r.left + s * 0.2f, r.top + s * 0.52f)
                lineTo(r.left + s * 0.42f, r.top + s * 0.74f)
                lineTo(r.left + s * 0.82f, r.top + s * 0.26f)
            }
            drawPath(tick, DONE_COLOR, style = Stroke(width = s * 0.14f))
        }
    }

    private val DONE_COLOR = androidx.compose.ui.graphics.Color(0xff2e7d32)

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
