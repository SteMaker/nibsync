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
        for ((_, r) in RichTextLayout.anchorBoxes(text, layout, start)) drawAnchorFlag(r)
        drawText(layout)
    }

    /** A to-do's check circle, as on a to-do on its own: a red ring, or green with a tick when [done]. */
    internal fun DrawScope.drawCheckBox(r: Rect, done: Boolean) {
        val center = Offset((r.left + r.right) / 2, (r.top + r.bottom) / 2)
        val rim = r.width * 0.16f
        val radius = r.width / 2 - rim / 2
        if (done) {
            drawCircle(DONE_COLOR, radius + rim / 2, center)
            val tick = androidx.compose.ui.graphics.Path().apply {
                moveTo(center.x - radius * 0.5f, center.y + radius * 0.02f)
                lineTo(center.x - radius * 0.12f, center.y + radius * 0.42f)
                lineTo(center.x + radius * 0.55f, center.y - radius * 0.38f)
            }
            drawPath(tick, androidx.compose.ui.graphics.Color.White, style = Stroke(width = rim * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        } else {
            drawCircle(OPEN_COLOR, radius, center, style = Stroke(width = rim))
        }
    }

    /** An anchor in text: a small flag on a pole, like an anchor on the page, over its sign [r]. */
    internal fun DrawScope.drawAnchorFlag(r: Rect) {
        val h = r.height * 0.8f
        val top = r.top + (r.height - h) / 2
        val pole = r.left + r.width * 0.3f
        drawLine(RichTextLayout.ANCHOR_COLOR, Offset(pole, top), Offset(pole, top + h), strokeWidth = h * 0.09f)
        val flag = androidx.compose.ui.graphics.Path().apply {
            moveTo(pole, top)
            lineTo(pole + h * 0.5f, top + h * 0.2f)
            lineTo(pole, top + h * 0.42f)
            close()
        }
        drawPath(flag, RichTextLayout.ANCHOR_COLOR)
    }

    private val OPEN_COLOR = androidx.compose.ui.graphics.Color(0xffd32f2f)
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
