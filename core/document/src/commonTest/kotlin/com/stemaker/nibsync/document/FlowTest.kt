package com.stemaker.nibsync.document

import com.stemaker.nibsync.document.select.Lasso
import com.stemaker.nibsync.model.Asset
import com.stemaker.nibsync.model.FlowBlock
import com.stemaker.nibsync.model.FlowSlot
import com.stemaker.nibsync.model.FractionalIndex
import com.stemaker.nibsync.model.ImageDraft
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.TextBox
import com.stemaker.nibsync.model.TextBoxDraft
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowTest {
    private val flowId = StrokeId("flow")
    private fun TestScope.session() = DocumentSession(DocumentState.empty(META), DEV_A, { }, backgroundScope, clock = { 0L })

    private fun box(order: String, h: Float = 10f, y: Float = 20f) = TextBoxDraft(20f, y, 80f, h, RichText.plain(order), FlowSlot(flowId, order))

    private fun image(order: String) = ImageDraft(20f, 20f, 40f, 30f, Asset("a".repeat(64), Asset.PNG), FlowSlot(flowId, order))

    @Test
    fun orderKeysAlwaysLeaveRoom() {
        var low = FlowSlot.FIRST
        val high = FractionalIndex.between(low, null)
        repeat(50) {
            val mid = FractionalIndex.between(low, high)
            assertTrue(mid > low && mid < high, "$low < $mid < $high")
            low = mid
        }
        var top = FlowSlot.FIRST
        repeat(50) {
            val before = FractionalIndex.between(null, top)
            assertTrue(before < top)
            assertTrue(!before.endsWith('a'))
            top = before
        }
    }

    @Test
    fun blocksStackInTheirOrderAndFollowEachOther() = runTest {
        val s = session()
        val (a, picture, b) = s.replaceElements(emptySet(), listOf(box("c"), image("n"), box("t")))
        val placed = { id: StrokeId -> (s.state.value.element(id) as FlowBlock).y }
        assertEquals(20f, placed(a.id), 0.01f)
        assertEquals(33f, placed(picture.id), 0.01f)
        assertEquals(66f, placed(b.id), 0.01f)
        // The first block gets taller: the others follow, with no op for them.
        s.replaceElements(listOf(a.id), listOf(box("c", h = 16f)))
        assertEquals(39f, placed(picture.id), 0.01f)
        assertEquals(72f, placed(b.id), 0.01f)
        // A block in between is removed: the gap closes.
        s.removeStrokes(listOf(picture.id))
        assertEquals(39f, placed(b.id), 0.01f)
    }

    @Test
    fun theLayoutDoesNotDependOnTheOrderOfOps() = runTest {
        val a = DocumentSession(DocumentState.empty(META), DEV_A, { }, backgroundScope, clock = { 0L })
        val ops = ArrayList<DocumentOp>()
        val logged = DocumentSession(DocumentState.empty(META), DEV_A, { ops += it }, backgroundScope, clock = { 0L })
        logged.replaceElements(emptySet(), listOf(box("c"), image("n")))
        logged.edit { moveFlow(flowId, 30f, 40f) }
        logged.edit { moveFlow(flowId, 50f, 60f) }
        logged.flush()
        val reversed = DocumentState.empty(META).applyAll(ops.asReversed())
        assertEquals(logged.state.value.elements.map { it.bounds }.toSet(), reversed.elements.map { it.bounds }.toSet())
        assertEquals(50f, reversed.flow(flowId)!!.x, 0.01f)
        assertEquals(60f, reversed.flow(flowId)!!.y, 0.01f)
        a.close()
    }

    @Test
    fun movingAFlowIsOneSmallStepAndKeepsIds() = runTest {
        val s = session()
        val blocks = s.replaceElements(emptySet(), listOf(box("c"), image("n")))
        s.edit { moveFlow(flowId, 30f, 100f) }
        assertEquals(blocks.map { it.id }.toSet(), s.state.value.elements.map { it.id }.toSet())
        assertEquals(100f, s.state.value.flow(flowId)!!.y, 0.01f)
        s.undo()
        assertEquals(20f, s.state.value.flow(flowId)!!.y, 0.01f)
        s.redo()
        assertEquals(30f, s.state.value.flow(flowId)!!.x, 0.01f)
    }

    @Test
    fun whatIsBelowMovesAsAUnitWhenAFlowGrows() = runTest {
        val s = session()
        val first = s.replaceElements(emptySet(), listOf(TextBoxDraft(20f, 20f, 80f, 10f, RichText.plain("x")))).single() as TextBox
        s.replaceElements(emptySet(), listOf(box("c", y = 50f), image("n")))
        s.edit { flowing(first.slot.flowId) { replaceElements(listOf(first.id), listOf(TextBoxDraft(20f, 20f, 80f, 25f, RichText.plain("x"), first.slot))) } }
        assertEquals(65f, s.state.value.flow(flowId)!!.y, 0.01f)
        // It took one position, not a rewrite of the blocks.
        s.undo()
        assertEquals(50f, s.state.value.flow(flowId)!!.y, 0.01f)
    }

    @Test
    fun selectingAnyPartOfABoxTakesTheWholeBox() = runTest {
        val s = session()
        val (text, picture) = s.replaceElements(emptySet(), listOf(box("c"), image("n")))
        val aroundText = floatArrayOf(10f, 10f, 110f, 10f, 110f, 31f, 10f, 31f)
        assertEquals(setOf(text.id, picture.id), Lasso.select(s.state.value.elements, aroundText))
        val aroundPicture = floatArrayOf(10f, 32f, 110f, 32f, 110f, 70f, 10f, 70f)
        assertEquals(setOf(text.id, picture.id), Lasso.select(s.state.value.elements, aroundPicture))
    }
}
