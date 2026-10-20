package com.stemaker.nibsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PenPreferencesTest {
    @Test
    fun roundTripsThroughStrings() {
        val prefs = PenPreferences(
            widths = PenWidths.DEFAULTS + (BrushFamily.FOUNTAIN_PEN to listOf(0.6f, 1f, 2.25f)),
        ).used(BrushFamily.FOUNTAIN_PEN, 2.25f).used(BrushFamily.HIGHLIGHTER, 3f)
        val stored = prefs.toStrings()
        assertEquals(prefs, PenPreferences.fromStrings(stored::get))
        assertEquals("0.6,1,2.25", stored["pen.FOUNTAIN_PEN.widths"])
    }

    @Test
    fun highlighterIsNotRememberedAsLastPen() {
        val prefs = PenPreferences().used(BrushFamily.BALLPOINT, 0.5f).used(BrushFamily.HIGHLIGHTER, 5f)
        assertEquals(BrushFamily.BALLPOINT, prefs.lastPen)
        assertEquals(1, prefs.widthIndex[BrushFamily.HIGHLIGHTER])
    }

    @Test
    fun brokenValuesFallBackToDefaults() {
        val stored = mapOf(
            "pen.BALLPOINT.widths" to "0.3,abc,1",
            "pen.HIGHLIGHTER.widths" to "1,2",
            "pen.FOUNTAIN_PEN.width" to "7",
            "pen.last" to "HIGHLIGHTER",
        )
        val prefs = PenPreferences.fromStrings(stored::get)
        assertEquals(PenWidths.DEFAULTS, prefs.widths)
        assertEquals(emptyMap(), prefs.widthIndex)
        assertNull(prefs.lastPen)
        assertEquals(0.8f, prefs.startWidth(BrushFamily.FOUNTAIN_PEN))
    }

    @Test
    fun parsesWidthsWithCommaAndChecksTheRange() {
        assertEquals(0.7f, PenWidths.parse(BrushFamily.BALLPOINT, " 0,7 "))
        assertNull(PenWidths.parse(BrushFamily.BALLPOINT, "9"))
        assertNull(PenWidths.parse(BrushFamily.HIGHLIGHTER, "0.5"))
        assertEquals("12", PenWidths.format(12f))
    }
}
