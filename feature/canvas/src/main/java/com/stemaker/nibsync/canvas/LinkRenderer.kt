package com.stemaker.nibsync.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import com.stemaker.nibsync.model.Anchor
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.LinkElement
import com.stemaker.nibsync.model.NoteLink

/**
 * Draws anchors (a flag and the name) and links to notes (a chip with an arrow and the target's
 * name) in document coordinates (mm), like the PC's PagePainter.
 */
class LinkRenderer {
    /** Current title of a linked document (null: unknown here, the stored one is shown). */
    var titleOf: (DocumentId) -> String? = { null }

    private val anchorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ANCHOR_FILL }
    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ANCHOR }
    private val pole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ANCHOR
        style = Paint.Style.STROKE
    }
    private val linkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINK_FILL }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINK }
    private val linkOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LINK
        style = Paint.Style.STROKE
        strokeWidth = 0.3f
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = LinkElement.FONT_MM }
    private val rect = RectF()
    private val path = Path()

    /** [pxPerMm] is the zoom; it is not needed for vector drawing but kept like MediaRenderer's. */
    @Suppress("UNUSED_PARAMETER")
    fun draw(canvas: Canvas, element: LinkElement, pxPerMm: Float) {
        when (element) {
            is Anchor -> drawAnchor(canvas, element)
            is NoteLink -> drawLink(canvas, element)
        }
    }

    private fun drawAnchor(canvas: Canvas, a: Anchor) {
        val h = a.heightMm
        rect.set(a.x, a.y, a.x + a.widthMm, a.y + h)
        canvas.drawRoundRect(rect, h * 0.2f, h * 0.2f, anchorFill)
        val x = a.x + h * 0.3f
        pole.strokeWidth = h * 0.08f
        canvas.drawLine(x, a.y + h * 0.15f, x, a.y + h * 0.85f, pole)
        path.reset()
        path.moveTo(x, a.y + h * 0.15f)
        path.lineTo(x + h * 0.45f, a.y + h * 0.32f)
        path.lineTo(x, a.y + h * 0.5f)
        path.close()
        canvas.drawPath(path, anchorPaint)
        drawLabel(canvas, a.name, ANCHOR, a.x + h, a.y, a.widthMm - h - h * 0.15f, h)
    }

    private fun drawLink(canvas: Canvas, link: NoteLink) {
        val h = link.heightMm
        rect.set(link.x, link.y, link.x + link.widthMm, link.y + h)
        canvas.drawRoundRect(rect, h * 0.2f, h * 0.2f, linkFill)
        canvas.drawRoundRect(rect, h * 0.2f, h * 0.2f, linkOutline)
        val pad = h * 0.15f
        val b = h - 2 * pad
        val bx = link.x + pad
        val by = link.y + pad
        rect.set(bx, by, bx + b, by + b)
        canvas.drawRoundRect(rect, h * 0.1f, h * 0.1f, linkPaint)
        arrow.strokeWidth = b * 0.11f
        canvas.drawLine(bx + b * 0.28f, by + b * 0.72f, bx + b * 0.72f, by + b * 0.28f, arrow)
        canvas.drawLine(bx + b * 0.4f, by + b * 0.28f, bx + b * 0.72f, by + b * 0.28f, arrow)
        canvas.drawLine(bx + b * 0.72f, by + b * 0.6f, bx + b * 0.72f, by + b * 0.28f, arrow)
        drawLabel(canvas, link.label(titleOf(link.target) ?: link.title), LINK, link.x + h, link.y, link.widthMm - h - pad, h)
    }

    private fun drawLabel(canvas: Canvas, text: String, color: Int, x: Float, y: Float, width: Float, h: Float) {
        if (width <= 0f) return
        label.color = color
        val shown = TextUtils.ellipsize(text, label, width, TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, x, y + h / 2 + label.textSize * 0.35f, label)
    }

    private companion object {
        const val ANCHOR = 0xff00796b.toInt()
        const val ANCHOR_FILL = 0x2200796b
        const val LINK = 0xff1a3fa8.toInt()
        const val LINK_FILL = 0xfff5f7fa.toInt()
    }
}
