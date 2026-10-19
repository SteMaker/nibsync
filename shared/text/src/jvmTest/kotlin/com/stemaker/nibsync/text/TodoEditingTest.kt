package com.stemaker.nibsync.text

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokePoint
import com.stemaker.nibsync.model.TodoContent
import com.stemaker.nibsync.model.TodoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class TodoEditingTest {
    private val session = DocumentSession(
        DocumentState.empty(DocumentMeta(DocumentId("d"), "d", createdAtEpochMs = 0)),
        DeviceId("a"), { }, CoroutineScope(Dispatchers.Unconfined), clock = { 0L },
    )
    private val editing = TodoEditing(session)
    private val doc get() = session.state.value

    private fun line(x0: Float, y: Float, x1: Float) =
        StrokeDraft(BrushSpec(BrushFamily.BALLPOINT, 0xff000000.toInt(), 0.5f), InputTool.STYLUS, listOf(StrokePoint(x0, y, 0), StrokePoint(x1, y, 10)))

    @Test
    fun typedTodoIsStoredOnceItHasText() {
        editing.startNew(20f, 30f, ink = false, pageWidthMm = 210f)
        editing.save()
        assertThat(doc.todos).isEmpty()
        editing.editing.value!!.text!!.setContent(RichText.plain("Milch kaufen"))
        editing.save()
        val item = doc.todos.single()
        assertThat((item.content as TodoContent.Text).text.plainText).isEqualTo("Milch kaufen")
        assertThat(item.widthMm).isEqualTo(TodoItem.DEFAULT_WIDTH_MM)
        // Changing it keeps the task.
        editing.editing.value!!.text!!.setContent(RichText.plain("Milch und Brot"))
        editing.finish()
        assertThat(doc.todos.single().taskId).isEqualTo(item.taskId)
        assertThat(editing.editing.value).isNull()
    }

    @Test
    fun handwritingIsRelativeAndGrowsTheFrame() {
        editing.startNew(20f, 30f, ink = true, pageWidthMm = 210f)
        val contentX = 20f + TodoItem.CONTENT_OFFSET_MM
        editing.addInk(line(contentX + 5f, 35f, contentX + 120f))
        val item = doc.todos.single()
        val stroke = (item.content as TodoContent.Ink).strokes.single()
        assertThat(stroke.points.first().x).isEqualTo(5f)
        assertThat(stroke.points.first().y).isEqualTo(35f - 30f - TodoItem.PADDING_MM)
        assertThat(item.widthMm).isAtLeast(120f + TodoItem.CONTENT_OFFSET_MM)
        editing.addInk(line(contentX, 40f, contentX + 10f))
        assertThat((doc.todos.single().content as TodoContent.Ink).strokes).hasSize(2)
        assertThat(doc.todos.single().taskId).isEqualTo(item.taskId)
    }

    @Test
    fun resizeStopsAtTheContentAndEmptyTodosVanish() {
        editing.startNew(0f, 0f, ink = true, pageWidthMm = 210f)
        editing.addInk(line(TodoItem.CONTENT_OFFSET_MM, 5f, TodoItem.CONTENT_OFFSET_MM + 40f))
        editing.resize(-200f, -200f)
        val e = editing.editing.value!!
        assertThat(e.widthMm).isAtLeast(40f + TodoItem.CONTENT_OFFSET_MM)
        assertThat(e.heightMm).isAtLeast(TodoItem.MIN_HEIGHT_MM)
        editing.resize(30f, 10f)
        editing.save()
        assertThat(doc.todos.single().widthMm).isWithin(0.01f).of(e.widthMm + 30f)

        editing.startNew(0f, 100f, ink = false, pageWidthMm = 210f)
        editing.finish()
        assertThat(doc.todos).hasSize(1)
        editing.open(doc.todos.single())
        editing.delete()
        assertThat(doc.todos).isEmpty()
    }
}
