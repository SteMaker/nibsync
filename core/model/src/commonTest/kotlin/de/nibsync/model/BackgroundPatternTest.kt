package de.nibsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackgroundPatternTest {
    private val lined = BackgroundSpec(BackgroundKind.LINED, 7f)
    private val grid = BackgroundSpec(BackgroundKind.GRID, 5f)
    private val dotted = BackgroundSpec(BackgroundKind.DOTTED, 5f)

    @Test
    fun blankHasNothing() {
        val blank = BackgroundSpec()
        assertEquals(emptyList(), BackgroundPattern.rows(blank, 0f, 300f))
        assertEquals(emptyList(), BackgroundPattern.columns(blank, 210f))
    }

    @Test
    fun rowsStartOneSpacingBelowTheTop() {
        assertEquals(listOf(7f, 14f, 21f), BackgroundPattern.rows(lined, 0f, 21f))
    }

    @Test
    fun rowsCoverOnlyTheRequestedRange() {
        // Visible area somewhere down an endless page.
        assertEquals(listOf(700f, 707f), BackgroundPattern.rows(lined, 695.5f, 710f))
        assertEquals(emptyList(), BackgroundPattern.rows(lined, 701f, 706f))
        assertEquals(emptyList(), BackgroundPattern.rows(lined, 50f, 10f))
    }

    @Test
    fun linedPaperHasNoColumns() {
        assertEquals(emptyList(), BackgroundPattern.columns(lined, 210f))
    }

    @Test
    fun gridColumnsAreCentred() {
        // A4 width 210 mm with 5 mm squares: 42 squares, no margin, edge lines left out.
        val a4 = BackgroundPattern.columns(grid, 210f)
        assertEquals(41, a4.size)
        assertEquals(5f, a4.first())
        assertEquals(205f, a4.last())
        // A5 148 mm: 29 squares, 1.5 mm margin on both sides.
        val a5 = BackgroundPattern.columns(grid, 148f)
        assertEquals(1.5f, a5.first())
        assertEquals(146.5f, a5.last(), 0.001f)
        assertEquals(a5.first(), 148f - a5.last(), 0.001f)
    }

    @Test
    fun dotsUseRowsAndColumns() {
        assertTrue(BackgroundPattern.isDotted(dotted))
        assertEquals(BackgroundPattern.columns(grid, 148f), BackgroundPattern.columns(dotted, 148f))
        assertEquals(BackgroundPattern.rows(grid, 0f, 50f), BackgroundPattern.rows(dotted, 0f, 50f))
    }

    @Test
    fun countIsCapped() {
        val tiny = BackgroundSpec(BackgroundKind.GRID, 0.001f)
        assertEquals(BackgroundPattern.MAX_LINES, BackgroundPattern.rows(tiny, 0f, 1000f).size)
        assertTrue(BackgroundPattern.columns(tiny, 1000f).size <= BackgroundPattern.MAX_LINES + 1)
    }

    @Test
    fun defaultsPerKind() {
        assertEquals(7f, BackgroundSpec.of(BackgroundKind.LINED).spacingMm)
        assertEquals(5f, BackgroundSpec.of(BackgroundKind.GRID).spacingMm)
        assertEquals(5f, BackgroundSpec.of(BackgroundKind.DOTTED).spacingMm)
        assertFailsWith<IllegalArgumentException> { BackgroundSpec(BackgroundKind.GRID, 0f) }
    }
}
