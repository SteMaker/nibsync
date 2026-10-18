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
        assertThat((shape.added.single() as de.nibsync.model.Stroke).points).isEqualTo(straightened)
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

    @Test
    fun undoAndRedoFollowTheSession() = runTest {
        val vm = viewModel()
        assertThat(vm.canUndo.value).isFalse()
        vm.commitStroke(draftWith(vm.tools.value.activeBrush))
        assertThat(vm.canUndo.value).isTrue()
        assertThat(vm.undo()).isTrue()
        assertThat(vm.document.value.strokeCount).isEqualTo(0)
        assertThat(vm.canUndo.value).isFalse()
        assertThat(vm.canRedo.value).isTrue()
        assertThat(vm.redo()).isTrue()
        assertThat(vm.document.value.strokeCount).isEqualTo(1)
        assertThat(vm.redo()).isFalse()
    }

    // ---- lasso selection -------------------------------------------------------------------

    /** A short horizontal stroke at height [y] from x = 10 to 20 mm. */
    private fun lineAt(y: Float) = StrokeDraft(
        de.nibsync.model.BrushSpec(BrushFamily.BALLPOINT, 0xff000000.toInt(), 0.5f), InputTool.STYLUS,
        List(21) { i -> StrokePoint(10f + i * 0.5f, y, i * 5L, 0.5f) },
    )

    private fun box(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, t, r, b, l, b)

    @Test
    fun lassoSelectsAndDeletes() = runTest {
        val vm = viewModel()
        val a = vm.commitStroke(lineAt(10f))!!
        val b = vm.commitStroke(lineAt(40f))!!
        vm.selectLasso()
        vm.selectByLasso(box(0f, 0f, 30f, 20f))
        assertThat(vm.selection.value).containsExactly(a.id)
        vm.deleteSelection()
        assertThat(vm.document.value.strokes).containsExactly(b)
        assertThat(vm.selection.value).isEmpty()
        vm.undo()
        assertThat(vm.document.value.strokeCount).isEqualTo(2)
    }

    @Test
    fun movingTheSelectionIsOneUndoStepAndKeepsItSelected() = runTest {
        val vm = viewModel()
        vm.commitStroke(lineAt(10f))
        vm.commitStroke(lineAt(15f))
        vm.selectLasso()
        vm.selectByLasso(box(0f, 0f, 30f, 20f))
        val moved = vm.moveSelection(5f, 100f)!!
        assertThat(moved.added).hasSize(2)
        assertThat(vm.selection.value).isEqualTo(moved.added.map { it.id }.toSet())
        assertThat(vm.document.value.strokes.map { it.points.first().y }).containsExactly(110f, 115f).inOrder()
        assertThat(vm.document.value.strokes.map { it.points.first().x }).containsExactly(15f, 15f)
        vm.undo()
        assertThat(vm.document.value.strokes.map { it.points.first().y }).containsExactly(10f, 15f).inOrder()
        assertThat(vm.selection.value).isEmpty() // moved copies are gone
    }

    @Test
    fun copyAndPasteAtAnotherPlace() = runTest {
        val vm = viewModel()
        vm.commitStroke(lineAt(10f))
        vm.selectLasso()
        vm.selectByLasso(box(0f, 0f, 30f, 20f))
        assertThat(vm.clipboard.content.value).isNull()
        vm.copySelection()
        val pasted = vm.paste(100f, 200f)!!
        val p = (pasted.added.single() as de.nibsync.model.Stroke).points
        assertThat((p.first().x + p.last().x) / 2).isWithin(0.01f).of(100f)
        assertThat(p.first().y).isWithin(0.01f).of(200f)
        assertThat(vm.document.value.strokeCount).isEqualTo(2)
        assertThat(vm.selection.value).containsExactly(pasted.added.single().id)
        // Paste again: clipboard keeps its content.
        vm.paste(50f, 50f)
        assertThat(vm.document.value.strokeCount).isEqualTo(3)
    }

    @Test
    fun switchingToolsDropsTheSelection() = runTest {
        val vm = viewModel()
        vm.commitStroke(lineAt(10f))
        vm.selectLasso()
        vm.selectByLasso(box(0f, 0f, 30f, 20f))
        vm.selectPen(BrushFamily.BALLPOINT)
        assertThat(vm.selection.value).isEmpty()
    }

    @Test
    fun emptyLassoClearsSelection() = runTest {
        val vm = viewModel()
        vm.commitStroke(lineAt(10f))
        vm.selectLasso()
        vm.selectByLasso(box(0f, 0f, 30f, 20f))
        vm.selectByLasso(box(100f, 100f, 120f, 120f))
        assertThat(vm.selection.value).isEmpty()
        assertThat(vm.moveSelection(1f, 1f)).isNull()
    }

    // ---- text boxes ----------------------------------------------------------------------

    private fun CanvasViewModel.type(text: String) {
        textEditing.value!!.controller.setContent(de.nibsync.model.RichText.plain(text))
    }

    @Test
    fun frameStartsANewBoxThatIsOnlyStoredWithText() = runTest {
        val vm = viewModel()
        vm.selectText()
        vm.requestText(30f, 50f, 80f, pageWidthMm = 210f)
        val editing = vm.textEditing.value!!
        assertThat(editing.boxId).isNull()
        assertThat(editing.x).isEqualTo(30f)
        assertThat(editing.y).isEqualTo(50f)
        assertThat(editing.widthMm).isEqualTo(80f)
        vm.saveText()
        vm.finishText()
        assertThat(vm.document.value.textBoxes).isEmpty()
        assertThat(vm.canUndo.value).isFalse()
    }

    @Test
    fun tapOnABoxEntersTextModeAndATapElsewhereGoesBack() = runTest {
        val vm = viewModel()
        vm.selectPen(BrushFamily.BALLPOINT)
        vm.requestText(30f, 50f, 80f, pageWidthMm = 210f) // via the text tool
        vm.type("Box")
        vm.finishText()
        val box = vm.document.value.textBoxes.single()
        vm.selectPen(BrushFamily.BALLPOINT)
        assertThat(vm.tools.value.tool).isEqualTo(Tool.PEN)

        // Tap on the box with the pen tool: text mode, box open.
        vm.requestText(box.x + 1f, box.y + 1f, null, pageWidthMm = 210f)
        assertThat(vm.tools.value.tool).isEqualTo(Tool.TEXT)
        assertThat(vm.textEditing.value!!.boxId).isEqualTo(box.id)
        // Tap off any box: editing ends, back to the pen.
        vm.requestText(150f, 250f, null, pageWidthMm = 210f)
        assertThat(vm.textEditing.value).isNull()
        assertThat(vm.tools.value.tool).isEqualTo(Tool.PEN)
        assertThat(vm.tools.value.activePen).isEqualTo(BrushFamily.BALLPOINT)
        assertThat(vm.textBoxAt(box.x + 1f, box.y + 1f)).isEqualTo(box)
        assertThat(vm.textBoxAt(150f, 250f)).isNull()
    }

    @Test
    fun textModeReturnsToTheToolUsedBefore() = runTest {
        val vm = viewModel()
        vm.selectLasso()
        vm.selectText()
        vm.requestText(150f, 250f, null, pageWidthMm = 210f) // tap, no box: leave text mode
        assertThat(vm.tools.value.tool).isEqualTo(Tool.LASSO)
    }

    @Test
    fun typedTextIsSavedOnceAndEditedInPlace() = runTest {
        val vm = viewModel()
        vm.measureText = { _, _ -> 9f }
        vm.requestText(30f, 50f, 80f, pageWidthMm = 210f)
        vm.type("Hallo")
        vm.saveText()
        val box = vm.document.value.textBoxes.single()
        assertThat(box.text.plainText).isEqualTo("Hallo")
        assertThat(box.heightMm).isEqualTo(9f)
        assertThat(vm.textEditing.value!!.boxId).isEqualTo(box.id)
        vm.saveText() // unchanged: the box is not replaced
        assertThat(vm.document.value.textBoxes.single().id).isEqualTo(box.id)

        vm.type("Hallo Welt")
        vm.finishText()
        assertThat(vm.textEditing.value).isNull()
        val edited = vm.document.value.textBoxes.single()
        assertThat(edited.text.plainText).isEqualTo("Hallo Welt")

        // A tap into the box opens it again.
        vm.requestText(edited.x + 5f, edited.y + 2f, null, pageWidthMm = 210f)
        assertThat(vm.textEditing.value!!.boxId).isEqualTo(edited.id)
        assertThat(vm.textEditing.value!!.controller.text.plainText).isEqualTo("Hallo Welt")
    }

    @Test
    fun clearedOrDeletedBoxesAreRemoved() = runTest {
        val vm = viewModel()
        vm.requestText(30f, 50f, 80f, pageWidthMm = 210f)
        vm.type("weg damit")
        vm.finishText()
        val box = vm.document.value.textBoxes.single()
        vm.requestText(box.x + 1f, box.y + 1f, null, pageWidthMm = 210f)
        vm.type("")
        vm.finishText()
        assertThat(vm.document.value.textBoxes).isEmpty()

        vm.requestText(30f, 50f, 80f, pageWidthMm = 210f)
        vm.type("auch weg")
        vm.saveText()
        vm.deleteText()
        assertThat(vm.document.value.textBoxes).isEmpty()
        assertThat(vm.textEditing.value).isNull()
    }

    @Test
    fun draggedFrameSetsTheWidthWithinThePage() = runTest {
        val vm = viewModel()
        vm.requestText(20f, 40f, 50f, pageWidthMm = 148f)
        assertThat(vm.textEditing.value!!.widthMm).isEqualTo(50f)
        assertThat(vm.textEditing.value!!.y).isEqualTo(40f)
        vm.requestText(120f, 40f, 300f, pageWidthMm = 148f)
        val e = vm.textEditing.value!!
        assertThat(e.widthMm).isEqualTo(143f)
        assertThat(e.x).isEqualTo(5f) // moved left to stay on the page
        vm.resizeText(-500f)
        assertThat(vm.textEditing.value!!.widthMm).isEqualTo(de.nibsync.model.TextBox.MIN_WIDTH_MM)
        vm.moveText(-100f, 10f)
        assertThat(vm.textEditing.value!!.x).isEqualTo(0f)
        assertThat(vm.textEditing.value!!.y).isEqualTo(50f)
    }

    @Test
    fun undoWhileEditingStoresTheTextFirst() = runTest {
        val vm = viewModel()
        vm.requestText(30f, 50f, 80f, pageWidthMm = 210f)
        vm.type("eins")
        assertThat(vm.undo()).isTrue() // stores "eins", then undoes that
        assertThat(vm.textEditing.value).isNull()
        assertThat(vm.document.value.textBoxes).isEmpty()
        assertThat(vm.redo()).isTrue()
        assertThat(vm.document.value.textBoxes.single().text.plainText).isEqualTo("eins")
    }

    @Test
    fun lassoMovesAndCopiesTextBoxesWithStrokes() = runTest {
        val vm = viewModel()
        vm.measureText = { _, _ -> 6f }
        vm.commitStroke(lineAt(10f))
        vm.requestText(10f, 20f, 40f, pageWidthMm = 210f)
        vm.type("Notiz")
        vm.finishText()
        vm.selectLasso()
        vm.selectByLasso(box(0f, 0f, 60f, 30f))
        assertThat(vm.selection.value).hasSize(2)
        vm.moveSelection(0f, 100f)
        assertThat(vm.document.value.textBoxes.single().y).isEqualTo(120f)
        vm.copySelection()
        vm.paste(100f, 100f)
        assertThat(vm.document.value.textBoxes).hasSize(2)
        assertThat(vm.document.value.strokeCount).isEqualTo(2)
    }

    @Test
    fun otherToolsEndTextEditing() = runTest {
        val vm = viewModel()
        vm.requestText(30f, 50f, 80f, pageWidthMm = 210f)
        vm.type("x")
        vm.selectPen(BrushFamily.BALLPOINT)
        assertThat(vm.textEditing.value).isNull()
        assertThat(vm.document.value.textBoxes).hasSize(1)
    }

    // ---- pictures and file links ------------------------------------------------------------

    private val picture = de.nibsync.model.Asset("c".repeat(64), de.nibsync.model.Asset.PNG)
    private val pdf = de.nibsync.model.Asset("d".repeat(64), de.nibsync.model.Asset.PDF)

    @Test
    fun insertedPictureIsSelectedAndATapElsewhereGoesBack() = runTest {
        val vm = viewModel()
        vm.selectPen(BrushFamily.HIGHLIGHTER)
        val added = vm.insert(listOf(de.nibsync.model.ImageDraft(20f, 20f, 100f, 50f, picture))).single()
        assertThat(vm.tools.value.tool).isEqualTo(Tool.LASSO)
        assertThat(vm.selection.value).containsExactly(added.id)
        vm.tapAt(150f, 200f)
        assertThat(vm.selection.value).isEmpty()
        assertThat(vm.tools.value.tool).isEqualTo(Tool.PEN)
        assertThat(vm.tools.value.activePen).isEqualTo(BrushFamily.HIGHLIGHTER)
        // A tap on the picture selects it again.
        vm.tapAt(30f, 30f)
        assertThat(vm.tools.value.tool).isEqualTo(Tool.LASSO)
        assertThat(vm.selection.value).containsExactly(added.id)
    }

    @Test
    fun manualLassoStaysOnATapOnEmptyPaper() = runTest {
        val vm = viewModel()
        vm.selectLasso()
        vm.tapAt(150f, 200f)
        assertThat(vm.tools.value.tool).isEqualTo(Tool.LASSO)
    }

    @Test
    fun tapOnALinkOpensItWithoutChangingTheTool() = runTest {
        val vm = viewModel()
        val opened = mutableListOf<de.nibsync.model.FileLink>()
        vm.openFile = { opened += it }
        val link = vm.insert(listOf(de.nibsync.model.FileLinkDraft(10f, 10f, 60f, 12f, pdf, "Skript.pdf"))).single()
        vm.tapAt(150f, 200f)
        vm.tapAt(20f, 15f)
        assertThat(opened.map { it.id }).containsExactly(link.id)
        assertThat(vm.tools.value.tool).isEqualTo(Tool.PEN)
    }

    @Test
    fun selectedPictureScalesFromItsCorner() = runTest {
        val vm = viewModel()
        vm.insert(listOf(de.nibsync.model.ImageDraft(20f, 20f, 100f, 50f, picture)))
        val edit = vm.resizeSelection(0.5f)!!
        val scaled = vm.document.value.files.single()
        assertThat(scaled.x).isEqualTo(20f)
        assertThat(scaled.widthMm).isEqualTo(50f)
        assertThat(scaled.heightMm).isEqualTo(25f)
        assertThat(vm.selection.value).containsExactly(edit.added.single().id)
        assertThat(vm.undo()).isTrue()
        assertThat(vm.document.value.files.single().widthMm).isEqualTo(100f)
        // Several elements, or strokes, are not scaled.
        vm.commitStroke(lineAt(10f))
        vm.selectLasso()
        vm.selectByLasso(box(0f, 0f, 200f, 200f))
        assertThat(vm.resizeSelection(2f)).isNull()
    }

    @Test
    fun pastingPicturesIntoAnotherDocumentCopiesTheirFiles() = runTest {
        val clip = StrokeClipboard()
        fun vmFor(doc: String) = CanvasViewModel(
            DocumentSession(DocumentState.empty(DocumentMeta(DocumentId(doc), "t", createdAtEpochMs = 0)), DeviceId("dev"), { }, backgroundScope, clock = { 0L }),
            ShapeRecognizer.None,
            clip,
        )
        val first = vmFor("first")
        val second = vmFor("second")
        val copied = mutableListOf<Triple<DocumentId, DocumentId, Set<de.nibsync.model.Asset>>>()
        second.copyAssets = { from, to, assets -> copied += Triple(from, to, assets) }
        first.insert(listOf(de.nibsync.model.ImageDraft(20f, 20f, 100f, 50f, picture)))
        first.copySelection()
        second.paste(100f, 100f)
        assertThat(copied).containsExactly(Triple(DocumentId("first"), DocumentId("second"), setOf(picture)))
        assertThat(second.document.value.files.single().asset).isEqualTo(picture)
    }
}
