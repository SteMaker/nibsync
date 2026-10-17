package de.nibsync.shapes

import de.nibsync.model.BrushFamily
import de.nibsync.model.BrushSpec
import de.nibsync.model.DeviceId
import de.nibsync.model.InputTool
import de.nibsync.model.Stroke
import de.nibsync.model.StrokeDraft
import de.nibsync.model.StrokeId
import de.nibsync.model.StrokePoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwoStrokeArrowTest {
    private val pen = BrushSpec(BrushFamily.FOUNTAIN_PEN, 0xff000000.toInt(), 0.5f)

    private fun points(pts: List<Pt>) = pts.mapIndexed { i, p -> StrokePoint(p.x.toFloat(), p.y.toFloat(), i * 4L, 0.2f) }

    private fun stroke(id: String, pts: List<Pt>, brush: BrushSpec = pen) = Stroke(
        StrokeId(id), brush.family.defaultLayer, brush, InputTool.STYLUS, points(pts), 0, DeviceId("d"),
    )

    private fun draft(pts: List<Pt>, brush: BrushSpec = pen) = StrokeDraft(brush, InputTool.STYLUS, points(pts))

    private val recordedPairs = with(RecordedArrows) {
        mapOf(
            "open head" to (shaft1 to openHead1),
            "open head 2" to (shaft2 to openHead2),
            "closed head" to (shaft3 to closedHead3),
            "triangle head on clean line" to (shaft4 to triangleHead4),
            "head with hook" to (shaft5 to hookedHead5),
        )
    }

    @Test
    fun recordedHeadsAreRecognisedAsHeads() {
        for ((name, pair) in recordedPairs) assertNotNull(ShapeDetector.detectArrowHead(pair.second), name)
    }

    @Test
    fun recordedTwoStrokeArrowsAreJoined() {
        for ((name, pair) in recordedPairs) {
            val (shaft, head) = pair
            val arrow = assertIs<Shape.Arrow>(ShapeDetector.joinArrow(assertNotNull(ShapeDetector.detectArrowHead(head)), shaft), name)
            // The tip is at the end of the shaft where the head was drawn.
            val shaftEnds = listOf(shaft.first(), shaft.last())
            val tipEnd = shaftEnds.minBy { it.distanceTo(arrow.tip) }
            assertTrue(tipEnd.distanceTo(arrow.tip) < 3.0, "$name: tip ${arrow.tip} far from shaft end $tipEnd")
            assertTrue(arrow.headLength in 3.0..15.0, "$name: head ${arrow.headLength}")
        }
    }

    @Test
    fun closedHeadsStayClosed() {
        with(RecordedArrows) {
            assertTrue(assertNotNull(ShapeDetector.joinArrow(assertNotNull(ShapeDetector.detectArrowHead(closedHead3)), shaft3)).closedHead)
            assertTrue(!assertNotNull(ShapeDetector.joinArrow(assertNotNull(ShapeDetector.detectArrowHead(openHead1)), shaft1)).closedHead)
        }
    }

    @Test
    fun recognizerReplacesShaftAndHeadWithOneArrow() {
        val recognizer = GeometricShapeRecognizer()
        for ((name, pair) in recordedPairs) {
            val shaft = stroke("shaft", pair.first)
            val other = stroke("other", HandDrawn(1).path(Pt(0.0, 0.0), Pt(5.0, 40.0)))
            val result = assertNotNull(recognizer.recognize(draft(pair.second), listOf(shaft, other)), name)
            assertEquals(setOf(StrokeId("shaft")), result.replaces, name)
            // The arrow outline spans the shaft.
            val xs = result.replacement.points.map { it.x.toDouble() }
            val shaftXs = pair.first.map { it.x }
            assertTrue(abs(xs.min() - shaftXs.min()) < 3 && abs(xs.max() - shaftXs.max()) < 3, name)
        }
    }

    @Test
    fun triangleWithoutShaftStaysATriangle() {
        val result = assertNotNull(GeometricShapeRecognizer().recognize(draft(RecordedArrows.closedHead3), emptyList()))
        assertTrue(result.replaces.isEmpty())
        val shapeOnly = ShapeDetector.detect(RecordedArrows.closedHead3)
        assertIs<Shape.Triangle>(shapeOnly)
    }

    @Test
    fun headFarFromTheShaftEndIsNotJoined() {
        val shaft = HandDrawn(2).path(Pt(10.0, 50.0), Pt(60.0, 50.0))
        val head = HandDrawn(3).path(Pt(25.0, 44.0), Pt(35.0, 50.0), Pt(25.0, 56.0)) // in the middle
        assertNull(ShapeDetector.joinArrow(assertNotNull(ShapeDetector.detectArrowHead(head)), shaft))
    }

    @Test
    fun headPointingTheWrongWayIsNotJoined() {
        val shaft = HandDrawn(4).path(Pt(10.0, 50.0), Pt(60.0, 50.0))
        // Apex at the shaft end, but the wings open forwards (away from the shaft).
        val head = HandDrawn(5).path(Pt(70.0, 44.0), Pt(60.0, 50.0), Pt(70.0, 56.0))
        assertNull(ShapeDetector.joinArrow(assertNotNull(ShapeDetector.detectArrowHead(head)), shaft))
    }

    @Test
    fun curvedShaftIsNotJoined() {
        val shaft = HandDrawn(6).path(List(40) { i -> Pt(10.0 + i, 50.0 + 10 * kotlin.math.sin(i / 39.0 * kotlin.math.PI)) })
        val head = HandDrawn(7).path(Pt(43.0, 44.0), Pt(49.0, 50.0), Pt(43.0, 56.0))
        assertNull(ShapeDetector.joinArrow(assertNotNull(ShapeDetector.detectArrowHead(head)), shaft))
    }

    @Test
    fun headIsJoinedOnlyWithStrokesOnTheSameLayer() {
        val highlighter = BrushSpec(BrushFamily.HIGHLIGHTER, 0x80ffeb3b.toInt(), 5f)
        val shaft = stroke("shaft", RecordedArrows.shaft1, highlighter)
        val result = GeometricShapeRecognizer().recognize(draft(RecordedArrows.openHead1), listOf(shaft))
        assertTrue(result == null || result.replaces.isEmpty())
    }

    @Test
    fun syntheticHeadsInAllDirections() {
        for (deg in 0 until 360 step 30) {
            val a = deg(deg.toDouble())
            val c = Pt(100.0, 100.0)
            val tail = c + Geometry.rotate(Pt(-50.0, 0.0), a)
            val tip = c
            val w1 = c + Geometry.rotate(Pt(-8.0, -5.0), a)
            val w2 = c + Geometry.rotate(Pt(-8.0, 5.0), a)
            val shaft = HandDrawn(deg).path(tail, tip)
            val head = HandDrawn(deg + 1).path(w1, tip, w2)
            val arrow = assertIs<Shape.Arrow>(ShapeDetector.joinArrow(assertNotNull(ShapeDetector.detectArrowHead(head), "deg $deg"), shaft), "deg $deg")
            assertTrue(arrow.tip.distanceTo(tip) < 1.5, "deg $deg tip ${arrow.tip}")
        }
    }

    @Test
    fun closedArrowOutlineHasTriangleHead() {
        val pts = ShapeTracer.trace(Shape.Arrow(Pt(0.0, 0.0), Pt(50.0, 0.0), 10.0, closedHead = true))
        assertEquals(Pt(50.0, 0.0).x, pts.last().x, 1e-9)
        // Contains the segment between the two wings (x = 50 - 10 cos 30°).
        val wingX = 50 - 10 * kotlin.math.cos(kotlin.math.PI / 6)
        assertTrue(pts.count { abs(it.x - wingX) < 1e-6 } > 5)
    }
}
