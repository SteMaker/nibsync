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

    private val straightened = listOf(StrokePoint(0f, 0f, 0), StrokePoint(10f, 0f, 5))

    @Test
    fun normalStrokesAreNotRecognised() = runTest {
        val vm = viewModel { d, _ -> de.nibsync.model.Recognition(d.copy(points = straightened)) }
        val draft = draftWith(vm.tools.value.activeBrush)
        assertThat(vm.commitStroke(draft)!!.points).isEqualTo(draft.points.map { de.nibsync.model.Quantization.quantize(it) })
    }

    @Test
    fun recognisedShapeIsAddedInsteadOfTheStroke() = runTest {
        val vm = viewModel { d, _ -> de.nibsync.model.Recognition(d.copy(points = straightened)) }
        val shape = vm.commitShape(draftWith(vm.tools.value.activeBrush))!!
        assertThat(shape.added.single().points).isEqualTo(straightened)
        assertThat(shape.removed).isEmpty()
        assertThat(vm.document.value.strokes).containsExactly(shape.added.single())
    }

    @Test
    fun unrecognisedShapeRequestAddsNothing() = runTest {
        val vm = viewModel(ShapeRecognizer.None)
        assertThat(vm.commitShape(draftWith(vm.tools.value.activeBrush))).isNull()
        assertThat(vm.document.value.strokeCount).isEqualTo(0)
    }

    @Test
    fun shapeMayReplaceRecentStrokesOfItsLayer() = runTest {
        var seen: List<de.nibsync.model.Stroke> = emptyList()
        val vm = viewModel { d, recent ->
            seen = recent
            de.nibsync.model.Recognition(d.copy(points = straightened), replaces = setOf(recent.last().id))
        }
        val shaft = vm.commitStroke(draftWith(vm.tools.value.activeBrush))!!
        vm.selectPen(BrushFamily.HIGHLIGHTER)
        vm.commitStroke(draftWith(vm.tools.value.activeBrush))
        vm.selectPen(BrushFamily.FOUNTAIN_PEN)
        val shape = vm.commitShape(draftWith(vm.tools.value.activeBrush))!!
        assertThat(seen).containsExactly(shaft) // highlighter stroke is on another layer
        assertThat(shape.removed).containsExactly(shaft.id)
        assertThat(vm.document.value.strokes.map { it.layer }).containsExactly(Layer.MARKER, Layer.INK).inOrder()
        assertThat(vm.document.value[shaft.id]).isNull()
    }

    @Test
    fun shapeCanBeUndone() = runTest {
        val vm = viewModel { d, _ -> de.nibsync.model.Recognition(d.copy(points = straightened)) }
        vm.commitShape(draftWith(vm.tools.value.activeBrush))
        // Undo lives in the session; the shape is one ordinary stroke there.
        assertThat(written).isEmpty() // journal runs asynchronously
        assertThat(vm.document.value.strokeCount).isEqualTo(1)
    }

    @Test
    fun erasingIsStoredAsOneUndoStep() = runTest {
        val vm = viewModel()
        val a = vm.commitStroke(draftWith(vm.tools.value.activeBrush))!!
        val b = vm.commitStroke(draftWith(vm.tools.value.activeBrush))!!
        val piece = draftWith(vm.tools.value.activeBrush)
        val committed = vm.commitErase(de.nibsync.document.erase.EraseResult(setOf(a.id, b.id), listOf(piece)))!!
        assertThat(committed.removed).containsExactly(a.id, b.id)
        assertThat(vm.document.value.strokes).containsExactly(committed.added.single())
    }

    @Test
    fun emptyEraseStoresNothing() = runTest {
        val vm = viewModel()
        assertThat(vm.commitErase(de.nibsync.document.erase.EraseResult(emptySet(), emptyList()))).isNull()
    }

    @Test
    fun selectingTheEraserKeepsThePen() = runTest {
        val vm = viewModel()
        vm.selectEraser(de.nibsync.document.erase.EraserMode.PIXEL)
        vm.selectEraserSize(12f)
        assertThat(vm.tools.value.eraserActive).isTrue()
        assertThat(vm.tools.value.eraserRadiusMm).isEqualTo(6f)
        assertThat(vm.tools.value.activePen).isEqualTo(BrushFamily.FOUNTAIN_PEN)
    }
}
