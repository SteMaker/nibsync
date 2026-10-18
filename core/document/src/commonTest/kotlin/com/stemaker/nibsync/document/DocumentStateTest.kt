package com.stemaker.nibsync.document

import com.stemaker.nibsync.model.BackgroundKind
import com.stemaker.nibsync.model.BackgroundSpec
import com.stemaker.nibsync.model.StrokeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentStateTest {
    private val empty = DocumentState.empty(META)

    @Test
    fun addsStrokesInStampOrder() {
        val s = empty.apply(add(2, DEV_A, stroke("second"))).apply(add(1, DEV_A, stroke("first")))
        assertEquals(listOf("first", "second"), s.ids)
    }

    @Test
    fun equalCountersAreOrderedByDevice() {
        val s = empty.apply(add(1, DEV_B, stroke("b"))).apply(add(1, DEV_A, stroke("a")))
        assertEquals(listOf("a", "b"), s.ids)
    }

    @Test
    fun highlighterIsAlwaysBehindInk() {
        val s = empty
            .apply(add(1, DEV_A, stroke("ink")))
            .apply(add(2, DEV_A, stroke("marker", brush = HIGHLIGHTER)))
        assertEquals(listOf("marker", "ink"), s.ids)
    }

    @Test
    fun removeDeletesStroke() {
        val s = empty.apply(add(1, DEV_A, stroke("x"))).apply(remove(2, DEV_A, "x"))
        assertEquals(emptyList(), s.ids)
        assertTrue(s.isRemoved(StrokeId("x")))
    }

    @Test
    fun removeBeforeAddStillWins() {
        val s = empty.apply(remove(2, DEV_B, "x")).apply(add(1, DEV_A, stroke("x")))
        assertEquals(emptyList(), s.ids)
    }

    @Test
    fun applyingTheSameOpTwiceIsANoOp() {
        val op = add(1, DEV_A, stroke("x"))
        val once = empty.apply(op)
        assertSame(once, once.apply(op))
    }

    @Test
    fun clockTracksHighestCounter() {
        val s = empty.apply(add(7, DEV_B, stroke("x"))).apply(add(3, DEV_A, stroke("y")))
        assertEquals(7, s.clock)
        assertTrue(s.hasApplied(OpStamp(3, DEV_A)))
        assertFalse(s.hasApplied(OpStamp(4, DEV_A)))
    }

    @Test
    fun resultIsIndependentOfOpOrder() {
        val ops = listOf(
            add(1, DEV_A, stroke("a1"), stroke("a2")),
            add(1, DEV_B, stroke("b1", author = DEV_B)),
            remove(2, DEV_B, "a1"),
            add(3, DEV_A, stroke("a3", brush = HIGHLIGHTER)),
            remove(4, DEV_A, "b1"),
            add(4, DEV_B, stroke("b2", author = DEV_B)),
        )
        val expected = empty.applyAll(ops).strokes
        // All permutations of 6 ops = 720, cheap enough to check exhaustively.
        permutations(ops).forEach { perm ->
            assertEquals(expected, empty.applyAll(perm).strokes, "order $perm")
        }
        assertEquals(listOf("a3", "a2", "b2"), empty.applyAll(ops).ids)
    }

    @Test
    fun backgroundComesFromMetaUntilChanged() {
        val meta = META.copy(background = BackgroundSpec(BackgroundKind.LINED, 7f))
        val s = DocumentState.empty(meta)
        assertEquals(meta.background, s.background)
        val grid = BackgroundSpec(BackgroundKind.GRID, 5f)
        val changed = s.apply(DocumentOp.SetBackground(OpStamp(1, DEV_A), grid))
        assertEquals(grid, changed.background)
        // A later meta (e.g. downloaded by sync) does not override the explicit change.
        assertEquals(grid, changed.withMeta(meta.copy(title = "x")).background)
        assertEquals(BackgroundSpec(), DocumentState.empty(META).withMeta(META).background)
    }

    @Test
    fun latestBackgroundWinsInAnyOrder() {
        val lined = DocumentOp.SetBackground(OpStamp(3, DEV_A), BackgroundSpec(BackgroundKind.LINED, 7f))
        val grid = DocumentOp.SetBackground(OpStamp(3, DEV_B), BackgroundSpec(BackgroundKind.GRID, 5f))
        val dots = DocumentOp.SetBackground(OpStamp(2, DEV_B), BackgroundSpec(BackgroundKind.DOTTED, 5f))
        val ops = listOf(lined, grid, dots, add(1, DEV_A, stroke("x")))
        permutations(ops).forEach { perm ->
            val s = empty.applyAll(perm)
            // Equal counters: the higher device id wins, like stroke order.
            assertEquals(grid.background, s.background, "order $perm")
            assertEquals(listOf("x"), s.ids)
            assertEquals(3, s.clock)
        }
    }

    @Test
    fun textBoxesLieBetweenMarkerAndInk() {
        val s = empty
            .apply(add(1, DEV_A, stroke("ink")))
            .apply(addText(2, DEV_A, textBox("text")))
            .apply(add(3, DEV_A, stroke("marker", brush = HIGHLIGHTER)))
        assertEquals(listOf("marker", "text", "ink"), s.elementIds)
        assertEquals(listOf("marker", "ink"), s.ids)
        assertEquals(listOf("text"), s.textBoxes.map { it.id.value })
        assertEquals(2, s.strokeCount)
        // get() only finds strokes, element() any kind.
        assertEquals(null, s[StrokeId("text")])
        assertEquals("text", s.element(StrokeId("text"))?.id?.value)
    }

    @Test
    fun textBoxesMergeLikeStrokes() {
        val ops = listOf(
            addText(1, DEV_A, textBox("t1")),
            addText(2, DEV_B, textBox("t2", author = DEV_B)),
            remove(3, DEV_B, "t1"),
            add(3, DEV_A, stroke("s")),
            addText(4, DEV_A, textBox("t1")), // re-adding a removed id has no effect
        )
        val expected = empty.applyAll(ops).elements
        permutations(ops).forEach { assertEquals(expected, empty.applyAll(it).elements, "order $it") }
        assertEquals(listOf("t2", "s"), empty.applyAll(ops).elementIds)
    }

    @Test
    fun contentBottomIncludesTextBoxes() {
        val s = empty.apply(add(1, DEV_A, stroke("x", y = 20f))).apply(addText(2, DEV_A, textBox("t", y = 100f)))
        assertEquals(110f, s.contentBottomMm)
    }

    @Test
    fun duplicateAddKeepsEarliestPosition() {
        val s = empty
            .apply(add(1, DEV_A, stroke("x")))
            .apply(add(2, DEV_A, stroke("y")))
            .apply(add(3, DEV_B, stroke("x")))
        assertEquals(listOf("x", "y"), s.ids)
    }

    @Test
    fun contentBottomFollowsLowestStroke() {
        assertEquals(0f, empty.contentBottomMm)
        val s = empty.apply(add(1, DEV_A, stroke("x", y = 100f), stroke("y", y = 40f)))
        assertEquals(100.25f, s.contentBottomMm)
    }

    private fun <T> permutations(list: List<T>): Sequence<List<T>> = sequence {
        if (list.size <= 1) {
            yield(list)
            return@sequence
        }
        for (i in list.indices) {
            val rest = list.toMutableList().also { it.removeAt(i) }
            for (p in permutations(rest)) yield(listOf(list[i]) + p)
        }
    }
}
