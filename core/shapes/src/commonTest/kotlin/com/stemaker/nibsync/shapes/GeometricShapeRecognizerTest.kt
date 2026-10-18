package com.stemaker.nibsync.shapes

import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.Layer
import com.stemaker.nibsync.model.StrokeDraft
import com.stemaker.nibsync.model.StrokePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Single-stroke recognition without document context. */
private fun GeometricShapeRecognizer.recognize(draft: StrokeDraft): StrokeDraft? = recognize(draft, emptyList())?.replacement

class GeometricShapeRecognizerTest {
    private val pen = BrushSpec(BrushFamily.FOUNTAIN_PEN, 0xff000000.toInt(), 0.8f)

    private fun draft(points: List<Pt>, brush: BrushSpec = pen) = StrokeDraft(
        brush, InputTool.STYLUS,
        points.mapIndexed { i, p -> StrokePoint(p.x.toFloat(), p.y.toFloat(), i * 4L, pressure = 0.1f + (i % 5) * 0.05f, tiltRad = 0.5f, orientationRad = 1f) },
    )

    @Test
    fun replacementKeepsPenAndUsesEvenPressure() {
        val input = draft(HandDrawn(1).path(Pt(10.0, 10.0), Pt(80.0, 10.0), Pt(80.0, 50.0), Pt(10.0, 50.0), Pt(10.0, 11.0)))
        val out = assertNotNull(GeometricShapeRecognizer().recognize(input))
        assertEquals(pen, out.brush)
        assertEquals(InputTool.STYLUS, out.tool)
        assertEquals(1, out.points.map { it.pressure }.toSet().size)
        assertEquals(0.2f, out.points.first().pressure)
        assertEquals(0.5f, out.points.first().tiltRad)
    }

    @Test
    fun timestampsIncreaseStrictly() {
        val out = assertNotNull(GeometricShapeRecognizer().recognize(draft(HandDrawn(2).ellipse(Pt(50.0, 50.0), 20.0, 20.0))))
        for (i in 1 until out.points.size) assertTrue(out.points[i].timeMs >= out.points[i - 1].timeMs)
        assertTrue(out.points.last().timeMs > 1000) // slow synthetic pen keeps corners sharp
    }

    @Test
    fun highlighterShapeStaysOnMarkerLayer() {
        val hl = BrushSpec(BrushFamily.HIGHLIGHTER, 0x80ffeb3b.toInt(), 5f)
        val out = assertNotNull(GeometricShapeRecognizer().recognize(draft(HandDrawn(3).path(Pt(0.0, 0.0), Pt(100.0, 2.0)), hl)))
        assertEquals(Layer.MARKER, out.layer)
        assertEquals(out.points.first().y, out.points.last().y)
    }

    @Test
    fun unrecognisedStrokeGivesNull() {
        val input = draft(HandDrawn(4).path(Pt(0.0, 0.0), Pt(10.0, 30.0), Pt(20.0, 5.0), Pt(30.0, 30.0), Pt(40.0, 0.0)))
        assertNull(GeometricShapeRecognizer().recognize(input))
    }

    @Test
    fun missingPressureStaysMissing() {
        val input = StrokeDraft(pen, InputTool.TOUCH, HandDrawn(5).path(Pt(0.0, 0.0), Pt(50.0, 0.0)).mapIndexed { i, p ->
            StrokePoint(p.x.toFloat(), p.y.toFloat(), i.toLong())
        })
        val out = assertNotNull(GeometricShapeRecognizer().recognize(input))
        assertTrue(out.points.none { it.hasPressure })
    }
}
