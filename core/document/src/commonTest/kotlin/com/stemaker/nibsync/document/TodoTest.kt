package com.stemaker.nibsync.document

import com.stemaker.nibsync.document.select.ClipContent
import com.stemaker.nibsync.document.select.translated
import com.stemaker.nibsync.model.Layer
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TodoContent
import com.stemaker.nibsync.model.TodoDraft
import com.stemaker.nibsync.model.TodoItem
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodoTest {
    private val written = mutableListOf<DocumentOp>()

    private fun TestScope.session(initial: DocumentState = DocumentState.empty(META)) =
        DocumentSession(initial, DEV_A, { written += it }, backgroundScope, clock = { 1234L })

    private fun todo(id: String, task: String = id, y: Float = 0f) =
        TodoItem(StrokeId(id), StrokeId(task), 10f, y, 60f, 14f, TodoContent.Text(RichText.plain("Milch")), 0, DEV_A)

    private fun addTodos(counter: Long, device: com.stemaker.nibsync.model.DeviceId, vararg items: TodoItem) =
        DocumentOp.AddTodos(OpStamp(counter, device), items.toList())

    private fun done(counter: Long, device: com.stemaker.nibsync.model.DeviceId, task: String, done: Boolean = true) =
        DocumentOp.SetTodoDone(OpStamp(counter, device), StrokeId(task), done)

    @Test
    fun todosLieAboveInk() {
        val doc = DocumentState.empty(META).applyAll(listOf(addTodos(1, DEV_A, todo("t")), add(2, DEV_A, stroke("s"))))
        assertEquals(listOf("s", "t"), doc.elementIds)
        assertEquals(Layer.TODO, doc.todos.single().layer)
    }

    @Test
    fun doneStateIsLastWriterWinsInAnyOrder() {
        val ops = listOf(addTodos(1, DEV_A, todo("t")), done(2, DEV_A, "t"), done(3, DEV_B, "t", done = false), done(3, DEV_A, "t"))
        val expected = DocumentState.empty(META).applyAll(ops)
        // (3, b) beats (3, a): same counter, larger device id.
        assertFalse(expected.isDone(StrokeId("t")))
        for (order in listOf(ops.reversed(), listOf(ops[2], ops[0], ops[3], ops[1]))) {
            assertEquals(expected.isDone(StrokeId("t")), DocumentState.empty(META).applyAll(order).isDone(StrokeId("t")))
        }
    }

    @Test
    fun tickingOffSurvivesAConcurrentMove() {
        // Device A ticks the task off while device B moves the to-do (remove + add, same task).
        val base = listOf(addTodos(1, DEV_A, todo("t1", task = "task")))
        val tick = done(2, DEV_A, "task")
        val move = listOf(remove(2, DEV_B, "t1"), addTodos(3, DEV_B, todo("t2", task = "task", y = 50f)))
        for (ops in listOf(base + tick + move, base + move + tick)) {
            val doc = DocumentState.empty(META).applyAll(ops)
            assertEquals(listOf("t2"), doc.todos.map { it.id.value })
            assertTrue(doc.isDone(StrokeId("task")))
        }
    }

    @Test
    fun sessionTicksOffWithUndo() = runTest {
        val s = session()
        val item = s.replaceElements(emptySet(), listOf(TodoDraft(0f, 0f, 60f, 14f, TodoContent.Text(RichText.plain("x"))))).single() as TodoItem
        assertNotEquals(item.id, item.taskId)
        s.setTodoDone(item.taskId, true)
        assertTrue(s.state.value.isDone(item.taskId))
        s.setTodoDone(item.taskId, true) // no change, no op
        assertTrue(s.undo())
        assertFalse(s.state.value.isDone(item.taskId))
        assertTrue(s.redo())
        assertTrue(s.state.value.isDone(item.taskId))
        s.flush()
        assertEquals(listOf(DocumentOp.AddTodos::class, DocumentOp.SetTodoDone::class, DocumentOp.SetTodoDone::class, DocumentOp.SetTodoDone::class), written.map { it::class })
        assertEquals(s.state.value.isDone(item.taskId), DocumentState.empty(META).applyAll(written).isDone(item.taskId))
    }

    @Test
    fun movingKeepsTheTaskAndPastingMakesANewOne() = runTest {
        val s = session()
        val item = s.replaceElements(emptySet(), listOf(TodoDraft(0f, 0f, 60f, 14f, TodoContent.Text(RichText.plain("x"))))).single() as TodoItem
        s.setTodoDone(item.taskId, true)
        val moved = s.replaceElements(setOf(item.id), listOf(item.translated(5f, 5f))).single() as TodoItem
        assertEquals(item.taskId, moved.taskId)
        assertTrue(s.state.value.isDone(moved.taskId))
        // Undoing the move restores a copy of the same task.
        s.undo()
        assertEquals(item.taskId, s.state.value.todos.single().taskId)

        val pasted = s.replaceElements(emptySet(), ClipContent.of(listOf(moved))!!.placedAt(100f, 100f)).single() as TodoItem
        assertNotEquals(item.taskId, pasted.taskId)
        assertFalse(s.state.value.isDone(pasted.taskId))
    }

    @Test
    fun inkContentIsQuantizedAndRelative() = runTest {
        val s = session()
        val ink = s.todoInk(listOf(draft(y = 3.0001f)))
        assertEquals(3f, ink.strokes.single().points.first().y)
        assertNull(ink.strokes.single().points.firstOrNull { it.y < 0f })
        assertTrue(!ink.isEmpty)
    }
}
