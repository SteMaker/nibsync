package com.stemaker.nibsync.document

import com.stemaker.nibsync.document.select.Reflow
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokePoint
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TextBoxDraft
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReflowTest {
    private fun TestScope.session() = DocumentSession(DocumentState.empty(META), DEV_A, { }, backgroundScope, clock = { 0L })

    private fun box(x: Float, y: Float, w: Float = 80f, h: Float = 10f) = TextBoxDraft(x, y, w, h, RichText.plain("t"))

    private fun stroke(x0: Float, y: Float, x1: Float) = StrokeDraft(
        BrushSpec(BrushFamily.BALLPOINT, 0xff000000.toInt(), 0.5f), InputTool.STYLUS,
        listOf(StrokePoint(x0, y, 0), StrokePoint(x1, y, 10)),
    )

    @Test
    fun whatIsBelowInTheColumnMovesTheMarginStays() = runTest {
        val s = session()
        val (top, below, margin, far) = s.replaceElements(
            emptySet(),
            listOf(box(20f, 20f), box(20f, 40f), box(120f, 35f, w = 40f), box(20f, 10f)),
        )
        // The top box grows by 6 mm.
        val moving = Reflow.shifts(s.state.value.elements, setOf(top.id), 20f, 100f, 30f, 36f).map { it.id }
        assertEquals(listOf(below.id), moving)
        assertTrue(margin.id !in moving && far.id !in moving)
    }

    @Test
    fun aWidePictureTakesWhatIsUnderItAlong() = runTest {
        val s = session()
        val (top, wide, under) = s.replaceElements(
            emptySet(),
            listOf(box(20f, 20f, w = 40f), box(20f, 40f, w = 150f), box(140f, 60f, w = 30f)),
        )
        val moving = Reflow.shifts(s.state.value.elements, setOf(top.id), 20f, 60f, 30f, 40f).map { it.id }
        assertEquals(listOf(wide.id, under.id), moving)
    }

    @Test
    fun aTransactionGrowsTheBoxAndMovesHandwritingAndIsUndoneInOneStep() = runTest {
        val s = session()
        val box = s.replaceElements(emptySet(), listOf(box(20f, 20f))).single() as TextBox
        val ink = s.addStroke(stroke(30f, 50f, 60f))
        val inkTop = ink.bounds.top
        s.edit { flowing(box.slot.flowId) { replaceElements(listOf(box.id), listOf(TextBoxDraft(20f, 20f, 80f, 25f, RichText.plain("t\nu"), box.slot))) } }
        assertEquals(inkTop + 15f, s.state.value.strokes.single().bounds.top, 0.05f)
        // Shrinking back pulls it up again: the gap stays.
        val grown = s.state.value.textBoxes.single()
        s.edit { flowing(grown.slot.flowId) { replaceElements(listOf(grown.id), listOf(TextBoxDraft(20f, 20f, 80f, 10f, RichText.plain("t"), grown.slot))) } }
        assertEquals(inkTop, s.state.value.strokes.single().bounds.top, 0.05f)
        // One undo per step: box and handwriting together.
        s.undo()
        assertEquals(25f, s.state.value.textBoxes.single().heightMm, 0.05f)
        assertEquals(inkTop + 15f, s.state.value.strokes.single().bounds.top, 0.05f)
        assertTrue(ink.id !in s.state.value)
    }

    @Test
    fun tinyChangesMoveNothing() = runTest {
        val s = session()
        val (top, below) = s.replaceElements(emptySet(), listOf(box(20f, 20f), box(20f, 40f)))
        assertTrue(Reflow.shifts(s.state.value.elements, setOf(top.id), 20f, 100f, 30f, 30.2f).isEmpty())
        assertEquals(listOf(below.id), Reflow.shifts(s.state.value.elements, setOf(top.id), 20f, 100f, 30f, 31f).map { it.id })
    }
}
