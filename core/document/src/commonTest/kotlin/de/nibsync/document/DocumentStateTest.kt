package de.nibsync.document

import de.nibsync.model.StrokeId
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
