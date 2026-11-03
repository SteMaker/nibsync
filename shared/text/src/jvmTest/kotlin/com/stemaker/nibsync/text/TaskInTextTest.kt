package com.stemaker.nibsync.text

import androidx.compose.ui.text.TextRange
import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.MarkStyle
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TodoContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class TaskInTextTest {
    private val sign = MarkStyle.Task.SIGN

    @Test
    fun aToDoGoesInAtTheCursorAndEnterStartsTheNextOne() {
        val c = TextEditController.create(RichText.plain("Einkaufen"))
        c.state.selection = TextRange(9)
        c.insertTask(StrokeId("t1"))
        c.state.replaceSelectedText("Milch")
        assertThat(c.lineBreak(soft = false)).isTrue()
        c.state.replaceSelectedText("Brot")
        val p = c.text.paragraphs
        assertThat(p.map { it.text }).containsExactly("Einkaufen", sign + "Milch", sign + "Brot").inOrder()
        val tasks = p.map { q -> q.marks.firstOrNull { it.style is MarkStyle.Task }?.style as? MarkStyle.Task }
        assertThat(tasks[1]!!.taskId).isEqualTo(StrokeId("t1"))
        assertThat(tasks[2]!!.taskId).isNotEqualTo(StrokeId("t1"))
        // Enter in an empty to-do ends the list: the box goes away.
        assertThat(c.lineBreak(soft = false)).isTrue()
        assertThat(c.lineBreak(soft = false)).isTrue()
        assertThat(c.text.paragraphs.last().text).isEmpty()
        assertThat(c.text.paragraphs.last().marks).isEmpty()
    }

    @Test
    fun toDosInTextAndInTableCellsAreOnTheListOfOpenToDos() {
        val session = DocumentSession(DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)), DeviceId("a"), { }, CoroutineScope(Dispatchers.Unconfined), clock = { 0L })
        val text = TextBoxEditing(session)
        val tables = TableEditing(session)
        text.startNew(20f, 20f, 120f)
        text.editing.value!!.controller.insertTask(StrokeId("box-task"))
        text.editing.value!!.controller.state.replaceSelectedText("Anrufen")
        text.finish()
        tables.insert(20f, 80f, rows = 1, columns = 1, header = false, pageWidthMm = 210f)
        tables.editing.value!!.controller.insertTask(StrokeId("cell-task"))
        tables.editing.value!!.controller.state.replaceSelectedText("Prüfen")
        tables.finish()
        val todos = session.state.value.textTodos
        assertThat(todos.map { it.taskId }).containsExactly(StrokeId("box-task"), StrokeId("cell-task")).inOrder()
        assertThat((todos[0].content as TodoContent.Text).text.plainText).isEqualTo("Anrufen")
        assertThat(com.stemaker.nibsync.library.TodoIndex.openTodos(session.state.value).map { it.taskId }).containsExactly(StrokeId("box-task"), StrokeId("cell-task"))
        // Ticked off: no longer open.
        session.setTodoDone(StrokeId("cell-task"), true)
        assertThat(com.stemaker.nibsync.library.TodoIndex.openTodos(session.state.value).map { it.taskId }).containsExactly(StrokeId("box-task"))
    }
}
