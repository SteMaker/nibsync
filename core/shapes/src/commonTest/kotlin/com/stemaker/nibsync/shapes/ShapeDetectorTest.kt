package com.stemaker.nibsync.shapes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShapeDetectorTest {
    private val seeds = 1..20

    private fun assertNear(expected: Double, actual: Double, tolerance: Double, what: String = "") =
        assertTrue(abs(expected - actual) <= tolerance, "$what expected $expected ± $tolerance but was $actual")

    private fun assertNear(expected: Pt, actual: Pt, tolerance: Double, what: String = "") =
        assertTrue(expected.distanceTo(actual) <= tolerance, "$what expected $expected ± $tolerance but was $actual")

    // ---- lines ---------------------------------------------------------------------------

    @Test
    fun wobblyLineBecomesStraightLine() = seeds.forEach { seed ->
        val shape = ShapeDetector.detect(HandDrawn(seed).path(Pt(10.0, 10.0), Pt(70.0, 35.0)))
        val line = assertIs<Shape.Line>(shape, "seed $seed")
        assertNear(Pt(10.0, 10.0), line.start, 1.5, "start")
        assertNear(Pt(70.0, 35.0), line.end, 1.5, "end")
    }

    @Test
    fun nearlyHorizontalLineIsSnapped() {
        val line = assertIs<Shape.Line>(ShapeDetector.detect(HandDrawn(3).path(Pt(10.0, 50.0), Pt(90.0, 53.0))))
        assertEquals(line.start.y, line.end.y, 1e-9)
    }

    @Test
    fun nearlyVerticalAndDiagonalLinesAreSnapped() {
        val v = assertIs<Shape.Line>(ShapeDetector.detect(HandDrawn(4).path(Pt(30.0, 10.0), Pt(32.5, 80.0))))
        assertEquals(v.start.x, v.end.x, 1e-9)
        val d = assertIs<Shape.Line>(ShapeDetector.detect(HandDrawn(5).path(Pt(0.0, 0.0), Pt(50.0, 53.0))))
        assertEquals(abs(d.end.x - d.start.x), abs(d.end.y - d.start.y), 1e-9)
    }

    @Test
    fun clearlySlantedLineIsNotSnapped() {
        val line = assertIs<Shape.Line>(ShapeDetector.detect(HandDrawn(6).path(Pt(0.0, 0.0), Pt(60.0, 20.0))))
        assertTrue(abs(line.end.y - line.start.y) > 10)
    }

    // ---- arrows --------------------------------------------------------------------------

    @Test
    fun oneStrokeArrowIsRecognised() = seeds.forEach { seed ->
        val tip = Pt(80.0, 30.0)
        val stroke = HandDrawn(seed).path(Pt(10.0, 30.0), tip, Pt(70.0, 24.0), tip, Pt(70.0, 36.0))
        val arrow = assertIs<Shape.Arrow>(ShapeDetector.detect(stroke), "seed $seed")
        assertNear(tip, arrow.tip, 1.5, "tip")
        assertNear(Pt(10.0, 30.0), arrow.tail, 1.5, "tail")
        assertNear(11.7, arrow.headLength, 3.0, "head")
    }

    @Test
    fun arrowWithSingleWingIsStillAnArrow() {
        val tip = Pt(20.0, 80.0)
        val stroke = HandDrawn(2).path(Pt(20.0, 10.0), tip, Pt(14.0, 70.0))
        assertIs<Shape.Arrow>(ShapeDetector.detect(stroke))
    }

    @Test
    fun hookIsNotAnArrow() {
        // The end bends sideways/forwards instead of back along the shaft: like a "J".
        val stroke = HandDrawn(1).path(Pt(10.0, 10.0), Pt(10.0, 60.0), Pt(25.0, 70.0))
        assertNull(ShapeDetector.detect(stroke))
    }

    // ---- rectangles ----------------------------------------------------------------------

    @Test
    fun axisAlignedRectangle() = seeds.forEach { seed ->
        val stroke = HandDrawn(seed).path(Pt(10.0, 10.0), Pt(90.0, 10.0), Pt(90.0, 50.0), Pt(10.0, 50.0), Pt(11.0, 12.0))
        val r = assertIs<Shape.Rectangle>(ShapeDetector.detect(stroke), "seed $seed")
        assertEquals(0.0, r.rotationRad, 1e-9)
        assertNear(80.0, r.width, 2.5, "width")
        assertNear(40.0, r.height, 2.5, "height")
        assertNear(Pt(50.0, 30.0), r.center, 2.0, "center")
    }

    @Test
    fun rotatedRectangleKeepsItsRotation() = seeds.forEach { seed ->
        val c = Pt(50.0, 50.0)
        val corners = rotated(listOf(Pt(20.0, 35.0), Pt(80.0, 35.0), Pt(80.0, 65.0), Pt(20.0, 65.0), Pt(21.0, 36.0)), c, deg(25.0))
        val r = assertIs<Shape.Rectangle>(ShapeDetector.detect(HandDrawn(seed).path(corners)), "seed $seed")
        val angle = ((r.rotationRad % (PI / 2)) + PI / 2) % (PI / 2)
        assertNear(deg(25.0), angle, deg(4.0), "rotation")
    }

    @Test
    fun rectangleStartingMidEdgeIsRecognised() {
        val stroke = HandDrawn(9).path(Pt(50.0, 10.0), Pt(90.0, 10.0), Pt(90.0, 50.0), Pt(10.0, 50.0), Pt(10.0, 10.0), Pt(52.0, 10.0))
        assertIs<Shape.Rectangle>(ShapeDetector.detect(stroke))
    }

    @Test
    fun nearlySquareRectangleBecomesSquare() {
        val stroke = HandDrawn(7).path(Pt(10.0, 10.0), Pt(52.0, 10.0), Pt(52.0, 50.0), Pt(10.0, 50.0), Pt(10.0, 11.0))
        val r = assertIs<Shape.Rectangle>(ShapeDetector.detect(stroke))
        assertEquals(r.width, r.height, 1e-9)
    }

    // ---- triangles -----------------------------------------------------------------------

    @Test
    fun triangleIsRecognised() = seeds.forEach { seed ->
        val a = Pt(10.0, 70.0)
        val b = Pt(50.0, 10.0)
        val c = Pt(90.0, 70.0)
        val t = assertIs<Shape.Triangle>(ShapeDetector.detect(HandDrawn(seed).path(a, b, c, Pt(11.0, 69.0))), "seed $seed")
        val got = listOf(t.a, t.b, t.c)
        for (expected in listOf(a, b, c)) assertTrue(got.any { it.distanceTo(expected) < 4 }, "seed $seed: corner $expected missing in $got")
    }

    // ---- ellipses ------------------------------------------------------------------------

    @Test
    fun circleIsRecognisedAsCircle() = seeds.forEach { seed ->
        val e = assertIs<Shape.Ellipse>(ShapeDetector.detect(HandDrawn(seed).ellipse(Pt(50.0, 50.0), 30.0, 29.0)), "seed $seed")
        assertEquals(e.rx, e.ry, 1e-9)
        assertNear(29.5, e.rx, 2.0, "radius")
        assertNear(Pt(50.0, 50.0), e.center, 2.0, "center")
    }

    @Test
    fun ellipseKeepsAxesAndRotation() = seeds.forEach { seed ->
        val e = assertIs<Shape.Ellipse>(ShapeDetector.detect(HandDrawn(seed).ellipse(Pt(60.0, 40.0), 40.0, 18.0, deg(30.0))), "seed $seed")
        assertNear(40.0, maxOf(e.rx, e.ry), 3.0, "major")
        assertNear(18.0, minOf(e.rx, e.ry), 3.0, "minor")
        val major = if (e.rx >= e.ry) e.rotationRad else e.rotationRad + PI / 2
        val angle = ((major % PI) + PI) % PI
        assertNear(deg(30.0), angle, deg(5.0), "rotation")
    }

    @Test
    fun axisAlignedEllipseIsSnapped() {
        val e = assertIs<Shape.Ellipse>(ShapeDetector.detect(HandDrawn(11).ellipse(Pt(60.0, 40.0), 40.0, 20.0, deg(4.0))))
        assertEquals(0.0, ((e.rotationRad % (PI / 2)) + PI / 2) % (PI / 2), 1e-9)
    }

    @Test
    fun ellipseWithSmallGapOrOvershootIsRecognised() {
        assertIs<Shape.Ellipse>(ShapeDetector.detect(HandDrawn(12).ellipse(Pt(50.0, 50.0), 30.0, 20.0, sweep = 2 * PI - 0.5)))
        assertIs<Shape.Ellipse>(ShapeDetector.detect(HandDrawn(13).ellipse(Pt(50.0, 50.0), 30.0, 20.0, sweep = 2 * PI + 0.8)))
    }

    @Test
    fun roundedSquareIsNotForcedIntoATriangle() {
        val shape = ShapeDetector.detect(HandDrawn(14).ellipse(Pt(50.0, 50.0), 30.0, 30.0))
        assertIs<Shape.Ellipse>(shape)
    }

    // ---- things that must stay handwriting -----------------------------------------------

    @Test
    fun tinyStrokesAreNeverReplaced() {
        assertNull(ShapeDetector.detect(HandDrawn(1).path(Pt(0.0, 0.0), Pt(3.0, 0.0))))
        assertNull(ShapeDetector.detect(HandDrawn(1).ellipse(Pt(5.0, 5.0), 1.2, 1.2)))
    }

    @Test
    fun handwritingIsNotRecognised() {
        val samples = mapOf(
            "zigzag W" to HandDrawn(1).path(Pt(0.0, 0.0), Pt(10.0, 30.0), Pt(20.0, 5.0), Pt(30.0, 30.0), Pt(40.0, 0.0)),
            "U" to HandDrawn(2).path(Pt(0.0, 0.0), Pt(0.0, 30.0), Pt(20.0, 30.0), Pt(20.0, 0.0)),
            "S curve" to HandDrawn(3).path(List(60) { i -> Pt(20 * kotlin.math.sin(i / 59.0 * 2 * PI), i.toDouble()) }),
            "spiral" to HandDrawn(4).path(List(200) { i ->
                val t = i / 199.0 * 4 * PI
                Pt(50 + (5 + 4 * t) * kotlin.math.cos(t), 50 + (5 + 4 * t) * kotlin.math.sin(t))
            }),
            "pentagon" to HandDrawn(5).path(List(6) { i ->
                Pt(50 + 30 * kotlin.math.cos(2 * PI * i / 5), 50 + 30 * kotlin.math.sin(2 * PI * i / 5))
            }),
            "back and forth" to HandDrawn(6).path(Pt(0.0, 0.0), Pt(60.0, 0.0), Pt(5.0, 2.0)),
            "loop letter l" to HandDrawn(7).path(Pt(0.0, 40.0), Pt(10.0, 0.0), Pt(14.0, 5.0), Pt(6.0, 40.0), Pt(20.0, 38.0)),
        )
        for ((name, stroke) in samples) assertNull(ShapeDetector.detect(stroke), name)
    }
}

class ShapeTracerTest {
    @Test
    fun rectangleOutlineIsClosedAndDense() {
        val r = Shape.Rectangle(Pt(50.0, 50.0), 40.0, 20.0, 0.0)
        val pts = ShapeTracer.trace(r)
        assertEquals(pts.first(), pts.last())
        for (i in 1 until pts.size) assertTrue(pts[i].distanceTo(pts[i - 1]) <= ShapeTracer.STEP_MM + 1e-9)
        // All points lie on the outline.
        assertTrue(pts.all { abs(it.x - 30) < 1e-6 || abs(it.x - 70) < 1e-6 || abs(it.y - 40) < 1e-6 || abs(it.y - 60) < 1e-6 })
        assertTrue(r.corners.all { c -> pts.any { it.distanceTo(c) < 1e-6 } })
    }

    @Test
    fun ellipseOutlineLiesOnEllipse() {
        val e = Shape.Ellipse(Pt(0.0, 0.0), 30.0, 10.0, deg(20.0))
        for (p in ShapeTracer.trace(e)) {
            val q = Geometry.rotate(p, -deg(20.0))
            assertEquals(1.0, (q.x / 30) * (q.x / 30) + (q.y / 10) * (q.y / 10), 1e-6)
        }
    }

    @Test
    fun arrowHeadIsSymmetric() {
        val pts = ShapeTracer.trace(Shape.Arrow(Pt(0.0, 0.0), Pt(50.0, 0.0), 10.0))
        val wings = listOf(pts[pts.size / 2 + 1], pts.last()).let { pts.filter { p -> p.x < 50 && abs(p.y) > 4.9 } }
        assertTrue(wings.isNotEmpty())
        assertEquals(pts.maxOf { it.y }, -pts.minOf { it.y }, 1e-6)
        assertEquals(50 - 10 * kotlin.math.cos(PI / 6), pts.last().x, 1e-6)
    }
}
