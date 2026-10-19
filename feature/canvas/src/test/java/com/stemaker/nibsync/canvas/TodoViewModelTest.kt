package com.stemaker.nibsync.canvas

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.DeviceId
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokePoint
import com.stemaker.nibsync.model.TodoContent
import com.stemaker.nibsync.model.TodoItem
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TodoViewModelTest {
    private fun TestScope.viewModel() = CanvasViewModel(
        DocumentSession(DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)), DeviceId("dev"), { }, backgroundScope, clock = { 0L }),
    )

    private fun CanvasViewModel.line(x0: Float, y: Float, x1: Float) = StrokeDraft(
        tools.value.activeBrush, InputTool.STYLUS, listOf(StrokePoint(x0, y, 0, 0.5f), StrokePoint(x1, y, 10, 0.5f)),
    )

    @Test
    fun theToolWritesNewTodosByHandAndTheHighlighterGivesWay() = runTest {
        val vm = viewModel()
        vm.selectPen(BrushFamily.HIGHLIGHTER)
        vm.selectTodo(ink = true)
        assertThat(vm.tools.value.activePen).isEqualTo(BrushFamily.BALLPOINT)
        vm.tapAt(40f, 50f)
        val e = vm.todoEditing.value!!
        assertThat(e.isInk).isTrue()
        // The check circle is where one tapped.
        assertThat(e.preview(DeviceId("x")).checkBounds.contains(40f, 50f)).isTrue()
        val cx = e.x + TodoItem.CONTENT_OFFSET_MM
        vm.commitTodoInk(vm.line(cx + 2f, e.y + 6f, cx + 30f))
        val item = vm.document.value.todos.single()
        assertThat((item.content as TodoContent.Ink).strokes).hasSize(1)
        assertThat(vm.document.value.strokes).isEmpty()
        // A tap beside it only ends it; the next one starts another.
        vm.tapAt(40f, 150f)
        assertThat(vm.todoEditing.value).isNull()
        vm.tapAt(40f, 150f)
        assertThat(vm.todoEditing.value).isNotNull()
    }

    @Test
    fun typedTodoTickedOffAndUndone() = runTest {
        val vm = viewModel()
        vm.selectTodo(ink = false)
        vm.tapAt(40f, 50f)
        vm.todoEditing.value!!.text!!.setContent(RichText.plain("Milch"))
        vm.finishText()
        val item = vm.document.value.todos.single()
        vm.toggleTodo(item.taskId)
        assertThat(vm.document.value.isDone(item.taskId)).isTrue()
        vm.undo()
        assertThat(vm.document.value.isDone(item.taskId)).isFalse()
    }

    @Test
    fun tappingATodoWithThePenOpensItTemporarily() = runTest {
        val vm = viewModel()
        vm.selectTodo(ink = false)
        vm.tapAt(40f, 50f)
        vm.todoEditing.value!!.text!!.setContent(RichText.plain("x"))
        vm.selectPen(BrushFamily.FOUNTAIN_PEN)
        val item = vm.document.value.todos.single()
        vm.tapAt(item.x + 40f, item.y + 5f)
        assertThat(vm.todoEditing.value!!.itemId).isEqualTo(item.id)
        assertThat(vm.tools.value.tool).isEqualTo(Tool.TODO)
        // Tapping into it keeps it open; tapping beside it goes back to the pen.
        vm.tapAt(item.x + 40f, item.y + 5f)
        assertThat(vm.todoEditing.value).isNotNull()
        vm.tapAt(item.x + 40f, item.y + 100f)
        assertThat(vm.todoEditing.value).isNull()
        assertThat(vm.tools.value.tool).isEqualTo(Tool.PEN)
        // A long press selects it for moving.
        vm.longPressAt(item.x + 40f, item.y + 5f)
        assertThat(vm.selection.value).hasSize(1)
    }
}
