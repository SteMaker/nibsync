package com.stemaker.nibsync.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdGeneratorTest {
    @Test
    fun generatesVersion4Uuids() {
        val uuid = IdGenerator(Random(42)).newUuid()
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(uuid), uuid)
    }

    @Test
    fun idsAreUnique() {
        val gen = IdGenerator()
        val ids = List(1000) { gen.newStrokeId() }.toSet()
        assertEquals(1000, ids.size)
    }

    @Test
    fun documentAndDeviceIdsAcceptGeneratedUuids() {
        val gen = IdGenerator()
        gen.newDocumentId()
        gen.newDeviceId()
    }

    @Test
    fun documentIdRejectsPathCharacters() {
        assertFailsWith<IllegalArgumentException> { DocumentId("../etc") }
        assertFailsWith<IllegalArgumentException> { DocumentId("a/b") }
        assertFailsWith<IllegalArgumentException> { DocumentId("") }
    }

    @Test
    fun strokeIdRejectsBlank() {
        assertFailsWith<IllegalArgumentException> { StrokeId(" ") }
    }
}

class BrushTest {
    @Test
    fun highlighterLivesBehindInk() {
        assertEquals(Layer.MARKER, BrushFamily.HIGHLIGHTER.defaultLayer)
        assertEquals(Layer.INK, BrushFamily.BALLPOINT.defaultLayer)
        assertEquals(Layer.INK, BrushFamily.FOUNTAIN_PEN.defaultLayer)
        assertTrue(Layer.MARKER.zIndex < Layer.INK.zIndex)
        assertTrue(Layer.BACKGROUND.zIndex < Layer.MARKER.zIndex)
    }

    @Test
    fun onlyFountainPenIsPressureSensitive() {
        assertEquals(listOf(BrushFamily.FOUNTAIN_PEN), BrushFamily.entries.filter { it.pressureSensitive })
    }

    @Test
    fun rejectsNonPositiveSize() {
        assertFailsWith<IllegalArgumentException> { BrushSpec(BrushFamily.BALLPOINT, 0, 0f) }
        assertFailsWith<IllegalArgumentException> { BrushSpec(BrushFamily.BALLPOINT, 0, Float.NaN) }
    }

    @Test
    fun alphaIsExtractedFromArgb() {
        assertEquals(0x80, BrushSpec(BrushFamily.HIGHLIGHTER, 0x80ffff00.toInt(), 5f).alpha)
        assertEquals(0xff, PEN.alpha)
    }
}

class StrokeTest {
    @Test
    fun boundsIncludeHalfBrushWidth() {
        val s = testStroke(xy = arrayOf(1f to 2f, 5f to 10f, 3f to 4f))
        assertEquals(0.75f, s.bounds.left)
        assertEquals(1.75f, s.bounds.top)
        assertEquals(5.25f, s.bounds.right)
        assertEquals(10.25f, s.bounds.bottom)
    }

    @Test
    fun rejectsEmptyStroke() {
        assertFailsWith<IllegalArgumentException> { testStroke(xy = arrayOf()) }
    }

    @Test
    fun rejectsBackgroundLayer() {
        assertFailsWith<IllegalArgumentException> { testStroke().copy(layer = Layer.BACKGROUND) }
    }

    @Test
    fun draftDefaultsToBrushLayer() {
        val hl = BrushSpec(BrushFamily.HIGHLIGHTER, 0x80ffff00.toInt(), 5f)
        assertEquals(Layer.MARKER, StrokeDraft(hl, InputTool.STYLUS, listOf(StrokePoint(0f, 0f, 0))).layer)
    }

    @Test
    fun pointFlagsReflectMissingValues() {
        val p = StrokePoint(0f, 0f, 0)
        assertTrue(!p.hasPressure && !p.hasTilt && !p.hasOrientation)
        val q = StrokePoint(0f, 0f, 0, pressure = 0f, tiltRad = 0f, orientationRad = 0f)
        assertTrue(q.hasPressure && q.hasTilt && q.hasOrientation)
    }
}

class PageFormatTest {
    @Test
    fun a4PortraitIs210By297() {
        val f = PageFormat(PaperSize.A4, Orientation.PORTRAIT)
        assertEquals(210f, f.widthMm)
        assertEquals(297f, f.pageHeightMm)
    }

    @Test
    fun landscapeSwapsSides() {
        val f = PageFormat(PaperSize.A5, Orientation.LANDSCAPE)
        assertEquals(210f, f.widthMm)
        assertEquals(148f, f.pageHeightMm)
    }

    @Test
    fun aSeriesHalvesEachStep() {
        val sizes = PaperSize.entries
        for (i in 0 until sizes.size - 1) {
            // Long side of the next size is the short side of this one (rounded to whole mm).
            assertEquals(sizes[i].shortSideMm, sizes[i + 1].longSideMm)
        }
    }

    @Test
    fun defaultIsA4BlankPortrait() {
        assertEquals(PaperSize.A4, PageFormat().paper)
        assertEquals(BackgroundKind.BLANK, BackgroundSpec().kind)
    }

    @Test
    fun shapeRecognizerNoneRecognisesNothing() {
        val d = StrokeDraft(PEN, InputTool.STYLUS, listOf(StrokePoint(0f, 0f, 0)))
        assertEquals(null, ShapeRecognizer.None.recognize(d, emptyList()))
        assertNotEquals(PageFormat(PaperSize.A3), PageFormat())
    }
}

class QuantizationTest {
    @Test
    fun quantizeIsIdempotent() {
        val p = StrokePoint(12.3456f, 98.7654f, 17, pressure = 0.123456f, tiltRad = 0.98765f, orientationRad = 3.14159f)
        val q = Quantization.quantize(p)
        assertEquals(q, Quantization.quantize(q))
        assertEquals(12.35f, q.x)
        assertEquals(98.77f, q.y)
    }

    @Test
    fun missingValuesStayMissing() {
        val q = Quantization.quantize(StrokePoint(1f, 2f, 0))
        assertEquals(StrokePoint.NO_VALUE, q.pressure)
        assertEquals(StrokePoint.NO_VALUE, q.tiltRad)
        assertEquals(StrokePoint.NO_VALUE, q.orientationRad)
    }

    @Test
    fun pressureIsClampedToOne() {
        assertEquals(1f, Quantization.quantize(StrokePoint(0f, 0f, 0, pressure = 1.7f)).pressure)
    }
}
