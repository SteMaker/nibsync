package com.stemaker.nibsync.document.erase

import com.stemaker.nibsync.document.DEV_A
import com.stemaker.nibsync.document.HIGHLIGHTER
import com.stemaker.nibsync.document.PEN
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.InputTool
import com.stemaker.nibsync.model.Layer
import com.stemaker.nibsync.model.Stroke
import com.stemaker.nibsync.model.StrokeId
import com.stemaker.nibsync.model.StrokePoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Horizontal line from x0 to x1 at y, points every [step] mm, pressure rising along it. */
fun horizontal(x0: Float, x1: Float, y: Float = 0f, step: Float = 0.5f): List<StrokePoint> {
    val n = ((x1 - x0) / step).toInt()
    return List(n + 1) { i -> StrokePoint(x0 + i * step, y, i * 5L, pressure = i.toFloat() / n, tiltRad = 0.5f) }
}

fun strokeOf(id: String, points: List<StrokePoint>, brush: BrushSpec = PEN) =
    Stroke(StrokeId(id), brush.family.defaultLayer, brush, InputTool.STYLUS, points, 0, DEV_A)

class StrokeCutterTest {
    private val reach = 1f

    @Test
    fun untouchedStrokeGivesNull() {
        assertNull(StrokeCutter.cut(horizontal(0f, 10f), reach, 0f, 5f, 10f, 5f))
    }

    @Test
    fun cuttingTheMiddleLeavesTwoPieces() {
        // Eraser crosses vertically at x = 5.
        val pieces = assertNotNull(StrokeCutter.cut(horizontal(0f, 10f), reach, 5f, -5f, 5f, 5f))
        assertEquals(2, pieces.size)
        // Piece edges lie on the eraser's edge, i.e. exactly `reach` from x = 5.
        assertEquals(4f, pieces[0].last().x, 0.01f)
        assertEquals(6f, pieces[1].first().x, 0.01f)
        assertEquals(0f, pieces[0].first().x)
        assertEquals(10f, pieces[1].last().x)
    }

    @Test
    fun boundaryValuesAreInterpolated() {
        val pieces = assertNotNull(StrokeCutter.cut(horizontal(0f, 10f), reach, 5f, -5f, 5f, 5f))
        val edge = pieces[1].first() // at x = 6, i.e. 60 % along the stroke
        assertEquals(0.6f, edge.pressure, 0.01f)
        assertEquals(0.5f, edge.tiltRad, 1e-6f)
    }

    @Test
    fun piecesStartAtTimeZeroAndStayMonotonic() {
        val pieces = assertNotNull(StrokeCutter.cut(horizontal(0f, 10f), reach, 5f, -5f, 5f, 5f))
        for (p in pieces) {
            assertEquals(0L, p.first().timeMs)
            for (i in 1 until p.size) assertTrue(p[i].timeMs >= p[i - 1].timeMs)
        }
    }

    @Test
    fun erasingAnEndLeavesOnePiece() {
        val pieces = assertNotNull(StrokeCutter.cut(horizontal(0f, 10f), reach, 10f, -5f, 10f, 5f))
        assertEquals(1, pieces.size)
        assertEquals(9f, pieces[0].last().x, 0.01f)
    }

    @Test
    fun erasingAlongTheWholeStrokeLeavesNothing() {
        assertEquals(emptyList(), StrokeCutter.cut(horizontal(0f, 10f), reach, -1f, 0f, 11f, 0f))
    }

    @Test
    fun tinyLeftoversAreDropped() {
        // Leaves only 0.1 mm at the start.
        val pieces = assertNotNull(StrokeCutter.cut(horizontal(0f, 10f), reach, 1.1f, 0f, 20f, 0f))
        assertEquals(emptyList(), pieces)
    }

    @Test
    fun smallEraserBetweenSparsePointsStillCuts() {
        // Points 5 mm apart, eraser reach 0.5 mm passes between them.
        val sparse = horizontal(0f, 10f, step = 5f)
        val pieces = assertNotNull(StrokeCutter.cut(sparse, 0.5f, 2.5f, -3f, 2.5f, 3f))
        assertEquals(2, pieces.size)
        assertEquals(2f, pieces[0].last().x, 0.01f)
        assertEquals(3f, pieces[1].first().x, 0.01f)
    }

    @Test
    fun piecesStayOutsideTheEraser() {
        val pieces = assertNotNull(StrokeCutter.cut(horizontal(0f, 20f), reach, 7f, -5f, 13f, 5f))
        for (p in pieces.flatten()) {
            assertTrue(EraseGeometry.distanceToSegment(p.x, p.y, 7f, -5f, 13f, 5f) >= reach - 1e-3f, "$p inside eraser")
        }
    }

    @Test
    fun reachIncludesHalfTheStrokeWidth() {
        val wide = strokeOf("h", horizontal(0f, 10f), HIGHLIGHTER) // 6 mm wide
        assertEquals(1f + 3f, StrokeCutter.reach(wide, 1f))
        // Eraser 3.5 mm above the centre line still touches the highlighter's ink.
        assertTrue(StrokeCutter.touches(wide.points, StrokeCutter.reach(wide, 1f), 0f, 3.5f, 10f, 3.5f))
        assertFalse(StrokeCutter.touches(wide.points, StrokeCutter.reach(wide, 1f), 0f, 4.5f, 10f, 4.5f))
    }

    @Test
    fun crossingSegmentsHaveDistanceZero() {
        assertEquals(0f, EraseGeometry.segmentDistance(0f, 0f, 10f, 10f, 0f, 10f, 10f, 0f))
        assertEquals(5f, EraseGeometry.segmentDistance(0f, 0f, 10f, 0f, 0f, 5f, 10f, 5f))
        assertEquals(hypot(1f, 1f), EraseGeometry.segmentDistance(0f, 0f, 1f, 0f, 2f, 1f, 3f, 1f), 1e-6f)
    }
}

class EraseGestureTest {
    private var counter = 0
    private fun ids() = { StrokeId("piece-${counter++}") }

    private val a = strokeOf("a", horizontal(0f, 20f, y = 0f))
    private val b = strokeOf("b", horizontal(0f, 20f, y = 10f))
    private val marker = strokeOf("m", horizontal(0f, 20f, y = 20f), HIGHLIGHTER)

    @Test
    fun objectEraserRemovesOnlyTouchedStrokes() {
        val g = EraseGesture(EraserMode.OBJECT, 0.5f, listOf(a, b), ids())
        g.moveTo(5f, -3f)
        g.moveTo(5f, 3f) // crosses a only
        assertEquals(listOf(b), g.visible)
        val r = g.result()
        assertEquals(setOf(a.id), r.removed)
        assertEquals(emptyList(), r.added)
    }

    @Test
    fun objectEraserSweepsBetweenSamples() {
        val g = EraseGesture(EraserMode.OBJECT, 0.5f, listOf(a, b), ids())
        g.moveTo(5f, -5f)
        g.moveTo(5f, 15f) // one long move across both
        assertEquals(emptyList(), g.visible)
        assertEquals(setOf(a.id, b.id), g.result().removed)
    }

    @Test
    fun singleTapErasesUnderThePen() {
        val g = EraseGesture(EraserMode.OBJECT, 1f, listOf(a, b), ids())
        g.moveTo(5f, 10.5f)
        assertEquals(setOf(b.id), g.result().removed)
    }

    @Test
    fun pixelEraserSplitsAndKeepsPenAndLayer() {
        val g = EraseGesture(EraserMode.PIXEL, 1f, listOf(a, marker), ids())
        g.moveTo(10f, -5f)
        g.moveTo(10f, 25f)
        val r = g.result()
        assertEquals(setOf(a.id, marker.id), r.removed)
        assertEquals(4, r.added.size)
        assertEquals(2, r.added.count { it.layer == Layer.MARKER && it.brush == HIGHLIGHTER })
        assertEquals(2, r.added.count { it.layer == Layer.INK && it.brush == PEN })
        // Visible: pieces in place of their strokes, z-order kept.
        assertEquals(listOf(Layer.INK, Layer.INK, Layer.MARKER, Layer.MARKER), g.visible.map { it.layer })
    }

    @Test
    fun pixelEraserKeepsCuttingPiecesWithinOneGesture() {
        val g = EraseGesture(EraserMode.PIXEL, 0.5f, listOf(a), ids())
        g.moveTo(5f, -3f); g.moveTo(5f, 3f) // first cut
        g.moveTo(15f, 3f) // move over without touching (y = 3 > reach)
        g.moveTo(15f, -3f) // second cut
        val pieces = g.result().added
        assertEquals(3, pieces.size)
        // Reach = 0.5 eraser + 0.25 half pen width: pieces start at 0, 5.75 and 15.75 mm.
        val starts = pieces.map { it.points.first().x }
        listOf(0f, 5.75f, 15.75f).zip(starts).forEach { (expected, actual) -> assertEquals(expected, actual, 0.01f) }
    }

    @Test
    fun wipingAStrokeCompletelyRemovesIt() {
        val g = EraseGesture(EraserMode.PIXEL, 1f, listOf(a), ids())
        g.moveTo(-2f, 0f)
        g.moveTo(22f, 0f)
        val r = g.result()
        assertEquals(setOf(a.id), r.removed)
        assertEquals(emptyList(), r.added)
        assertEquals(emptyList(), g.visible)
    }

    @Test
    fun noContactNoChanges() {
        val g = EraseGesture(EraserMode.PIXEL, 1f, listOf(a, b), ids())
        g.moveTo(5f, 4f)
        g.moveTo(15f, 6f)
        assertFalse(g.hasChanges)
        assertTrue(g.result().isEmpty)
        assertEquals(listOf(a, b), g.visible)
    }
}
