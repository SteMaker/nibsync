package com.stemaker.nibsync.canvas

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.stemaker.nibsync.ink.InkStrokeCache
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TodoContent
import com.stemaker.nibsync.model.TodoItem
import com.stemaker.nibsync.text.TextBoxRenderer

/**
 * Draws to-dos on a canvas in page millimetres: an almost opaque card (to-dos lie above
 * everything), a red check circle with a thick rim, green with a tick once done, and the typed
 * or handwritten content, faded when done. Used by the page and the list of open to-dos.
 */
class TodoRenderer(private val text: TextBoxRenderer) {
    private val renderer = CanvasStrokeRenderer.create()
    private val inkCache = InkStrokeCache()
    private val contentMatrix = Matrix()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILL }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = FRAME
    }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }
    private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = android.graphics.Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val tickPath = Path()

    /**
     * Draws [item]. [documentToView] maps page mm to screen pixels (for the ink renderer);
     * [content] false leaves out the content (an editor shows it), [editing] marks the frame.
     */
    fun draw(canvas: Canvas, item: TodoItem, done: Boolean, documentToView: Matrix, content: Boolean = true, editing: Boolean = false) {
        rect.set(item.x, item.y, item.x + item.widthMm, item.y + item.heightMm)
        canvas.drawRoundRect(rect, CORNER_MM, CORNER_MM, fill)
        frame.color = if (editing) EDIT_FRAME else FRAME
        frame.strokeWidth = if (editing) 0.4f else 0.3f
        canvas.drawRoundRect(rect, CORNER_MM, CORNER_MM, frame)

        val c = item.checkBounds
        val cx = (c.left + c.right) / 2
        val cy = (c.top + c.bottom) / 2
        val rim = TodoItem.CHECK_SIZE_MM * 0.16f
        val r = TodoItem.CHECK_SIZE_MM / 2 - rim / 2
        if (done) {
            canvas.drawCircle(cx, cy, r + rim / 2, green)
            tick.strokeWidth = rim * 0.9f
            tickPath.reset()
            tickPath.moveTo(cx - r * 0.5f, cy + r * 0.02f)
            tickPath.lineTo(cx - r * 0.12f, cy + r * 0.42f)
            tickPath.lineTo(cx + r * 0.55f, cy - r * 0.38f)
            canvas.drawPath(tickPath, tick)
        } else {
            red.style = Paint.Style.STROKE
            red.strokeWidth = rim
            canvas.drawCircle(cx, cy, r, red)
        }
        if (!content) return
        val save = if (done) canvas.saveLayerAlpha(rect, DONE_ALPHA) else canvas.save()
        when (val body = item.content) {
            is TodoContent.Text -> text.drawText(canvas, item.id, body.text, item.contentX, item.contentY, item.contentWidthMm)
            is TodoContent.Ink -> {
                canvas.translate(item.contentX, item.contentY)
                contentMatrix.set(documentToView)
                contentMatrix.preTranslate(item.contentX, item.contentY)
                for (s in body.strokes) renderer.draw(canvas, inkCache[s], contentMatrix)
            }
        }
        canvas.restoreToCount(save)
    }

    /** Drops cached meshes and layouts of to-dos no longer shown. */
    fun retainOnly(items: Collection<TodoItem>) {
        inkCache.retainOnly(items.flatMapTo(HashSet()) { (it.content as? TodoContent.Ink)?.strokes.orEmpty().map { s -> s.id } })
        text.retainTextsOnly(items.mapTo(HashSet<StrokeId>()) { it.id })
    }

    companion object {
        const val RED = 0xffd32f2f.toInt()
        const val GREEN = 0xff2e7d32.toInt()
        private const val FILL = 0xf5fffdf7.toInt()
        private const val FRAME = 0xffb0bec5.toInt()
        private const val EDIT_FRAME = 0xff1a3fa8.toInt()
        private const val CORNER_MM = 1.5f
        private const val DONE_ALPHA = 115
    }
}
