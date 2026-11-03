package com.stemaker.nibsync.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SnapGridTest {
    private val dotted = BackgroundSpec(BackgroundKind.DOTTED, 5f)

    @Test
    fun pointsSnapToTheDotsOfThePage() {
        // A4: 42 columns of 5 mm, 0 mm margin left over.
        assertEquals(20f to 35f, SnapGrid().snap(21.9f, 33.1f, dotted, 210f))
        // A5 (148 mm): 29 columns, 1.5 mm on each side.
        assertEquals(21.5f to 35f, SnapGrid().snap(21.9f, 33.1f, dotted, 148f))
        // The dots match what is drawn.
        assertEquals(BackgroundPattern.columns(dotted, 148f)[4], SnapGrid().snap(21.9f, 33.1f, dotted, 148f).first)
    }

    @Test
    fun ruledPaperSnapsOnlyRowsBlankPaperNothing() {
        assertEquals(21.9f to 35f, SnapGrid().snap(21.9f, 33.1f, BackgroundSpec(BackgroundKind.LINED, 7f), 210f))
        assertEquals(21.9f to 33.1f, SnapGrid().snap(21.9f, 33.1f, BackgroundSpec(BackgroundKind.BLANK), 210f))
        assertEquals(21.9f to 33.1f, SnapGrid(SnapGrid.Mode.OFF).snap(21.9f, 33.1f, dotted, 210f))
    }

    @Test
    fun aFixedGridWorksOnEveryPage() {
        assertEquals(20f to 30f, SnapGrid(SnapGrid.Mode.FIXED, 10f).snap(21.9f, 33.1f, BackgroundSpec(BackgroundKind.BLANK), 210f))
    }

    @Test
    fun aMoveIsAdjustedSoThePointLandsOnTheGrid() {
        val (dx, dy) = SnapGrid().snapMove(12f, 12f, 7.6f, 2f, dotted, 210f)
        assertEquals(8f, dx, 0.001f)
        assertEquals(3f, dy, 0.001f)
    }

    @Test
    fun theSettingRoundTrips() {
        for (g in listOf(SnapGrid(), SnapGrid(SnapGrid.Mode.OFF), SnapGrid(SnapGrid.Mode.FIXED, 2.5f))) assertEquals(g, SnapGrid.fromSetting(g.toSetting()))
    }
}
