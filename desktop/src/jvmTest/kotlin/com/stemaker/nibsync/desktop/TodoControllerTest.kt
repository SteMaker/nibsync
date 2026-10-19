package com.stemaker.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TodoContent
import com.stemaker.nibsync.model.TodoItem
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TodoControllerTest {
    private fun TestScope.controller() = NoteController(
        DocumentSession(DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)), DEV, { }, backgroundScope, clock = { 0L }),
        measureText = { _, _ -> 6f },
    )

    /** A click (press and release in place) at ([x], [y]) mm. */
    private fun NoteController.click(x: Float, y: Float, at: Long = 1000L, pressure: Float? = null) {
        pointerDown(x, y, at, pressure, toleranceMm = 1f, handleReachMm = 3f)
        pointerUp(x, y, at + 50, toleranceMm = 1f, pageWidthMm = 210f)
    }

    private fun NoteController.drag(x0: Float, y0: Float, x1: Float, y1: Float, at: Long = 5000L) {
        pointerDown(x0, y0, at, 0.5f, toleranceMm = 1f, handleReachMm = 3f)
        for (i in 1..10) pointerMove(x0 + (x1 - x0) * i / 10, y0 + (y1 - y0) * i / 10, at + i * 10, 0.5f)
        pointerUp(x1, y1, at + 200, toleranceMm = 1f, pageWidthMm = 210f)
    }

    @Test
    fun typedTodoAndTickingItOffWithAnyTool() = runTest {
        val c = controller()
        c.selectTodo(ink = false)
        c.click(40f, 50f)
        c.todo.editing.value!!.text!!.setContent(RichText.plain("Angebot schicken"))
        c.click(40f, 150f, at = 3000) // beside it: stores and ends it
        assertThat(c.todo.editing.value).isNull()
        val item = c.session.state.value.todos.single()
        assertThat((item.content as TodoContent.Text).text.plainText).isEqualTo("Angebot schicken")
        assertThat(c.session.state.value.isDone(item.taskId)).isFalse()

        // A click on the circle ticks it off, also with the pen tool; again reopens it.
        c.selectPen(com.stemaker.nibsync.model.BrushFamily.BALLPOINT)
        val circle = item.checkBounds
        c.click((circle.left + circle.right) / 2, (circle.top + circle.bottom) / 2, at = 6000)
        assertThat(c.session.state.value.isDone(item.taskId)).isTrue()
        assertThat(c.session.state.value.strokes).isEmpty()
        c.click(circle.left + 1f, circle.top + 1f, at = 9000)
        assertThat(c.session.state.value.isDone(item.taskId)).isFalse()
        // Not with the eraser.
        c.selectEraser(com.stemaker.nibsync.document.erase.EraserMode.OBJECT)
        c.click(circle.left + 1f, circle.top + 1f, at = 12000)
        assertThat(c.session.state.value.isDone(item.taskId)).isFalse()
    }

    @Test
    fun handwrittenTodoTakesTheStrokesInside() = runTest {
        val c = controller()
        c.selectTodo(ink = true)
        c.click(20f, 30f)
        val e = c.todo.editing.value!!
        assertThat(e.isInk).isTrue()
        val cx = e.x + TodoItem.CONTENT_OFFSET_MM
        c.drag(cx + 2f, e.y + 5f, cx + 30f, e.y + 8f)
        c.drag(cx + 2f, e.y + 9f, cx + 20f, e.y + 9f, at = 8000)
        val item = c.session.state.value.todos.single()
        assertThat((item.content as TodoContent.Ink).strokes).hasSize(2)
        // Nothing went onto the page itself.
        assertThat(c.session.state.value.strokes).isEmpty()
        // A stroke outside the open to-do only ends it.
        c.click(20f, 150f, at = 12000)
        assertThat(c.todo.editing.value).isNull()
        assertThat(c.session.state.value.todos).hasSize(1)
    }

    @Test
    fun clickingATodoOpensItAndEscapeEndsIt() = runTest {
        val c = controller()
        c.selectTodo(ink = false)
        c.click(40f, 50f)
        c.todo.editing.value!!.text!!.setContent(RichText.plain("x"))
        c.escape()
        val item = c.session.state.value.todos.single()
        c.selectTool(DesktopTool.TEXT)
        c.click(item.x + 30f, item.y + 5f, at = 4000)
        assertThat(c.todo.editing.value!!.itemId).isEqualTo(item.id)
        assertThat(c.tools.value.tool).isEqualTo(DesktopTool.TODO)
        c.escape()
        assertThat(c.todo.editing.value).isNull()
        // With the lasso, a click selects it to move it.
        c.selectTool(DesktopTool.LASSO)
        c.click(item.x + 30f, item.y + 5f, at = 8000)
        assertThat(c.selection.value).containsExactly(item.id)
    }
}
