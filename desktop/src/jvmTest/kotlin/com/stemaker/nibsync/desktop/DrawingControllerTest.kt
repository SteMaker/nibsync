package com.stemaker.nibsync.desktop

import com.google.common.truth.Truth.assertThat
import com.stemaker.nibsync.document.DocumentSession
import com.stemaker.nibsync.document.DocumentState
import com.stemaker.nibsync.document.erase.EraserMode
import com.stemaker.nibsync.model.Asset
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.DocumentId
import com.stemaker.nibsync.model.DocumentMeta
import com.stemaker.nibsync.model.FileLink
import com.stemaker.nibsync.model.FileLinkDraft
import com.stemaker.nibsync.model.ImageDraft
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.shapes.GeometricShapeRecognizer
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DrawingControllerTest {
    private val tol = 0.5f
    private val reach = 3f
    private var clock = 1_000L

    private fun TestScope.controller() = NoteController(
        DocumentSession(DocumentState.empty(DocumentMeta(DocumentId("d"), "t", createdAtEpochMs = 0)), DEV, { }, backgroundScope, clock = { 0L }),
        { _, _ -> 6f },
        GeometricShapeRecognizer(),
    )

    /** A drag from (x0, y0) to (x1, y1) mm in [steps] moves. */
    private fun NoteController.drag(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 20, pressure: Float? = null) {
        pointerDown(x0, y0, clock, pressure, tol, reach)
        for (i in 1..steps) {
            clock += 10
            pointerMove(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps, clock, pressure)
        }
        clock += 10
        pointerUp(x1, y1, clock, tol, 210f)
    }

    private fun NoteController.click(x: Float, y: Float) {
        clock += 1000
        pointerDown(x, y, clock, null, tol, reach)
        clock += 50
        pointerUp(x, y, clock, tol, 210f)
    }

    private fun NoteController.doubleClick(x: Float, y: Float) {
        click(x, y)
        clock += 100
        pointerDown(x, y, clock, null, tol, reach)
        clock += 50
        pointerUp(x, y, clock, tol, 210f)
    }

    @Test
    fun penDragsDrawStrokesWithTheChosenColourAndWidth() = runTest {
        val c = controller()
        c.selectPen(BrushFamily.FOUNTAIN_PEN)
        c.selectColor(DesktopPalette.inkColors[2])
        c.selectSize(1.2f)
        c.drag(20f, 20f, 80f, 25f, pressure = 0.3f)
        val stroke = c.session.state.value.strokes.single()
        assertThat(stroke.brush.family).isEqualTo(BrushFamily.FOUNTAIN_PEN)
        assertThat(stroke.brush.colorArgb).isEqualTo(DesktopPalette.inkColors[2])
        assertThat(stroke.brush.sizeMm).isEqualTo(1.2f)
        assertThat(stroke.tool).isEqualTo(InputTool.STYLUS)
        assertThat(stroke.points.first().pressure).isWithin(0.001f).of(0.3f)
        // A mouse has no pressure.
        c.drag(20f, 40f, 80f, 45f)
        assertThat(c.session.state.value.strokes.last().tool).isEqualTo(InputTool.MOUSE)
        assertThat(c.session.state.value.strokes.last().points.first().hasPressure).isFalse()
        assertThat(c.gesture.value).isNull()
    }

    @Test
    fun erasersRemoveOrCutStrokes() = runTest {
        val c = controller()
        c.selectPen(BrushFamily.BALLPOINT)
        c.drag(20f, 20f, 100f, 20f)
        c.drag(20f, 50f, 100f, 50f)
        c.selectEraser(EraserMode.OBJECT)
        c.drag(60f, 15f, 60f, 25f)
        assertThat(c.session.state.value.strokes.map { it.points.first().y }).containsExactly(50f)
        c.selectEraser(EraserMode.PIXEL)
        c.drag(60f, 45f, 60f, 55f)
        // The line at y = 50 is cut in two.
        assertThat(c.session.state.value.strokes).hasSize(2)
        assertThat(c.undo()).isTrue()
        assertThat(c.session.state.value.strokes).hasSize(1)
    }

    @Test
    fun lassoSelectsMovesCopiesPastesAndDeletes() = runTest {
        val c = controller()
        c.selectPen(BrushFamily.BALLPOINT)
        c.drag(20f, 20f, 40f, 20f)
        c.drag(20f, 80f, 40f, 80f)
        c.selectTool(DesktopTool.LASSO)
        // A lasso around the upper line.
        c.pointerDown(10f, 10f, clock, null, tol, reach)
        for ((x, y) in listOf(50f to 10f, 50f to 30f, 10f to 30f, 10f to 11f)) c.pointerMove(x, y, ++clock, null)
        c.pointerUp(10f, 11f, ++clock, tol, 210f)
        val upper = c.session.state.value.strokes.first { it.points.first().y == 20f }
        assertThat(c.selection.value).containsExactly(upper.id)
        // Dragging inside the frame moves it.
        c.drag(30f, 20f, 30f, 60f)
        assertThat(c.session.state.value.strokes.map { it.points.first().y }).containsExactly(60f, 80f)
        assertThat(c.copySelection()).isTrue()
        assertThat(c.paste(150f, 150f)).isTrue()
        assertThat(c.session.state.value.strokes).hasSize(3)
        assertThat(c.deleteSelection()).isTrue()
        assertThat(c.session.state.value.strokes).hasSize(2)
    }

    @Test
    fun aClickedPictureIsSelectedScaledAndLeftAgain() = runTest {
        val c = controller()
        val asset = Asset("e".repeat(64), Asset.PNG)
        c.insert(listOf(ImageDraft(20f, 20f, 100f, 50f, asset)))
        assertThat(c.tools.value.tool).isEqualTo(DesktopTool.LASSO)
        // Beside it: the selection ends, back to the text tool, no text box yet.
        c.click(150f, 200f)
        assertThat(c.selection.value).isEmpty()
        assertThat(c.tools.value.tool).isEqualTo(DesktopTool.TEXT)
        assertThat(c.text.editing.value).isNull()
        // With the pen: a click on the picture selects it, no dot.
        c.selectPen(BrushFamily.BALLPOINT)
        c.click(30f, 30f)
        val image = c.session.state.value.files.single()
        assertThat(c.selection.value).containsExactly(image.id)
        assertThat(c.session.state.value.strokes).isEmpty()
        // The corner handle (frame corner, 2 mm outside) halves it.
        c.drag(122f, 72f, 72f, 47f)
        val scaled = c.session.state.value.files.single()
        assertThat(scaled.widthMm).isWithin(2f).of(50f)
        assertThat(scaled.heightMm / scaled.widthMm).isWithin(0.01f).of(0.5f)
        c.click(180f, 250f)
        assertThat(c.tools.value.tool).isEqualTo(DesktopTool.PEN)
    }

    @Test
    fun linksAreSelectedByAClickAndOpenedByADoubleClick() = runTest {
        val c = controller()
        val opened = mutableListOf<FileLink>()
        c.openFile = { opened += it }
        c.insert(listOf(FileLinkDraft(20f, 20f, 60f, 12f, Asset("f".repeat(64), Asset.PDF), "Skript.pdf")))
        c.click(150f, 200f)
        c.click(30f, 25f)
        assertThat(opened).isEmpty()
        assertThat(c.selection.value).hasSize(1)
        c.click(150f, 200f)
        c.doubleClick(30f, 25f)
        assertThat(opened.single().fileName).isEqualTo("Skript.pdf")
    }

    @Test
    fun holdingThePenStillMakesAStraightLine() = runTest {
        val c = controller()
        c.selectPen(BrushFamily.BALLPOINT)
        c.pointerDown(20f, 20f, clock, null, tol, reach)
        for (i in 1..40) c.pointerMove(20f + i * 2f, 20f + if (i % 2 == 0) 0.6f else -0.6f, clock + i * 10L, null)
        val end = clock + 400
        repeat(5) { c.pointerMove(100f, 20f, end + it, null) }
        c.checkHold(end + 600)
        val line = c.session.state.value.strokes.single()
        // The wobbly input (±0.6 mm) became a straight line.
        assertThat(line.points.maxOf { it.y } - line.points.minOf { it.y }).isLessThan(0.1f)
        assertThat(c.gesture.value).isNull()
        c.pointerUp(100f, 20f, end + 700, tol, 210f)
        assertThat(c.session.state.value.strokes).hasSize(1)
    }

    @Test
    fun aClosedLoopIsNoClick() = runTest {
        val c = controller()
        c.insert(listOf(ImageDraft(20f, 20f, 100f, 50f, Asset("e".repeat(64), Asset.PNG))))
        c.click(150f, 200f)
        // A circle drawn with the pen on the picture ends where it started: still a stroke.
        c.selectPen(BrushFamily.BALLPOINT)
        c.pointerDown(50f, 40f, clock, null, tol, reach)
        for (i in 1..36) {
            val a = i * Math.PI / 18
            c.pointerMove(50f + 10f * kotlin.math.sin(a).toFloat(), 40f - 10f + 10f * kotlin.math.cos(a).toFloat(), clock + i, null)
        }
        c.pointerUp(50f, 40f, clock + 40, tol, 210f)
        assertThat(c.session.state.value.strokes).hasSize(1)
        assertThat(c.selection.value).isEmpty()
    }
}
