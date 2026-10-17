package de.nibsync.canvas

import com.google.common.truth.Truth.assertThat
import de.nibsync.document.DocumentOp
import de.nibsync.document.DocumentSession
import de.nibsync.document.DocumentState
import de.nibsync.model.BrushFamily
import de.nibsync.model.DeviceId
import de.nibsync.model.DocumentId
import de.nibsync.model.DocumentMeta
import de.nibsync.model.InputTool
import de.nibsync.model.Layer
import de.nibsync.model.ShapeRecognizer
import de.nibsync.model.StrokeDraft
import de.nibsync.model.StrokePoint
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CanvasViewModelTest {
    private val written = mutableListOf<DocumentOp>()

    private fun TestScope.viewModel(recognizer: ShapeRecognizer = ShapeRecognizer.None) = CanvasViewModel(
        DocumentSession(
            DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)),
            DeviceId("dev"),
            { written += it },
            backgroundScope,
            clock = { 0L },
        ),
        recognizer,
    )

    private fun draftWith(brush: de.nibsync.model.BrushSpec) =
        StrokeDraft(brush, InputTool.STYLUS, listOf(StrokePoint(1f, 2f, 0, 0.5f), StrokePoint(3f, 4f, 5, 0.75f)))

    @Test
    fun committedStrokeEntersTheDocument() = runTest {
        val vm = viewModel()
        val stroke = vm.commitStroke(draftWith(vm.tools.value.activeBrush))
        assertThat(stroke).isNotNull()
        assertThat(vm.document.value.strokes).containsExactly(stroke)
        assertThat(stroke!!.points.map { it.pressure }).containsExactly(0.5f, 0.75f).inOrder()
    }

    @Test
    fun highlighterStrokeIsPlacedBehindInk() = runTest {
        val vm = viewModel()
        val ink = vm.commitStroke(draftWith(vm.tools.value.activeBrush))!!
        vm.selectPen(BrushFamily.HIGHLIGHTER)
        val marker = vm.commitStroke(draftWith(vm.tools.value.activeBrush))!!
        assertThat(marker.layer).isEqualTo(Layer.MARKER)
        assertThat(vm.document.value.strokes).containsExactly(marker, ink).inOrder()
    }

    @Test
    fun toolSelectionUpdatesState() = runTest {
        val vm = viewModel()
        vm.selectPen(BrushFamily.BALLPOINT)
        vm.selectColor(PenPalette.inkColors[1])
        vm.selectSize(0.35f)
        vm.setDrawWithFinger(true)
        val t = vm.tools.value
        assertThat(t.activeBrush.family).isEqualTo(BrushFamily.BALLPOINT)
        assertThat(t.activeBrush.colorArgb).isEqualTo(PenPalette.inkColors[1])
        assertThat(t.activeBrush.sizeMm).isEqualTo(0.35f)
        assertThat(t.drawWithFinger).isTrue()
    }

    @Test
    fun emptyDraftIsRejected() = runTest {
        val vm = viewModel()
        assertThat(vm.commitStroke(StrokeDraft(vm.tools.value.activeBrush, InputTool.STYLUS, emptyList()))).isNull()
        assertThat(vm.document.value.strokeCount).isEqualTo(0)
    }

    @Test
    fun draftsPassThroughShapeRecognizer() = runTest {
        val straightened = listOf(StrokePoint(0f, 0f, 0), StrokePoint(10f, 0f, 5))
        val vm = viewModel { it.copy(points = straightened) }
        val stroke = vm.commitStroke(draftWith(vm.tools.value.activeBrush))!!
        assertThat(stroke.points).isEqualTo(straightened)
    }
}
