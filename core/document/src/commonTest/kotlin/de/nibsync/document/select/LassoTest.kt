package de.nibsync.document.select

import de.nibsync.document.HIGHLIGHTER
import de.nibsync.document.erase.horizontal
import de.nibsync.document.erase.strokeOf
import de.nibsync.model.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LassoTest {
    private val a = strokeOf("a", horizontal(10f, 20f, y = 10f))
    private val b = strokeOf("b", horizontal(10f, 20f, y = 30f))
    private val long = strokeOf("long", horizontal(0f, 100f, y = 50f))
    private val strokes = listOf(a, b, long)

    private fun rect(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, t, r, b, l, b)

    @Test
    fun selectsEnclosedStrokes() {
        assertEquals(setOf(a.id), Lasso.select(strokes, rect(5f, 5f, 25f, 15f)))
        assertEquals(setOf(a.id, b.id), Lasso.select(strokes, rect(5f, 5f, 25f, 35f)))
    }

    @Test
    fun strokeMostlyOutsideIsNotSelected() {
        // Lasso covers 30 of the 100 mm of the long stroke.
        assertEquals(emptySet(), Lasso.select(listOf(long), rect(0f, 45f, 30f, 55f)))
        // ... but 60 mm is enough.
        assertEquals(setOf(long.id), Lasso.select(listOf(long), rect(-1f, 45f, 60f, 55f)))
    }

    @Test
    fun freehandNonConvexLasso() {
        // A "U" shaped lasso around a and b but with a notch between them on the right.
        val u = floatArrayOf(5f, 5f, 25f, 5f, 25f, 15f, 12f, 20f, 25f, 25f, 25f, 35f, 5f, 35f)
        assertEquals(setOf(a.id, b.id), Lasso.select(strokes, u))
        assertFalse(Lasso.contains(u, 20f, 20f)) // in the notch
        assertTrue(Lasso.contains(u, 8f, 20f))
    }

    @Test
    fun tapsSelectNothing() {
        assertEquals(emptySet(), Lasso.select(strokes, floatArrayOf(15f, 10f, 15.5f, 10f, 15f, 10.5f)))
        assertEquals(emptySet(), Lasso.select(strokes, floatArrayOf(15f, 10f)))
    }

    @Test
    fun areaIsShoelace() {
        assertEquals(200f, Lasso.area(rect(0f, 0f, 20f, 10f)))
    }

    @Test
    fun boundsOfSelection() {
        val box = assertNotNull(boundsOf(listOf(a, b)))
        assertEquals(10f - 0.25f, box.left)
        assertEquals(30f + 0.25f, box.bottom)
        assertNull(boundsOf(emptyList()))
    }

    @Test
    fun translatedKeepsEverythingButPosition() {
        val marker = strokeOf("m", horizontal(0f, 10f), HIGHLIGHTER)
        val moved = marker.translated(5f, -2f)
        assertEquals(Layer.MARKER, moved.layer)
        assertEquals(HIGHLIGHTER, moved.brush)
        assertEquals(marker.points.map { it.x + 5f }, moved.points.map { it.x })
        assertEquals(marker.points.map { it.y - 2f }, moved.points.map { it.y })
        assertEquals(marker.points.map { it.pressure }, moved.points.map { it.pressure })
    }

    @Test
    fun clipboardPastesCentredAtTarget() {
        val clip = assertNotNull(ClipContent.of(listOf(a, b)))
        assertEquals(10.5f, clip.width)
        val pasted = clip.placedAt(100f, 200f)
        assertEquals(2, pasted.size)
        val xs = pasted.flatMap { it.points }.map { it.x }
        val ys = pasted.flatMap { it.points }.map { it.y }
        assertEquals(100f, (xs.min() + xs.max()) / 2, 1e-3f)
        assertEquals(200f, (ys.min() + ys.max()) / 2, 1e-3f)
        // Relative layout is kept: b stays 20 mm below a.
        assertEquals(20f, pasted[1].points.first().y - pasted[0].points.first().y, 1e-3f)
        assertNull(ClipContent.of(emptyList()))
    }
}
