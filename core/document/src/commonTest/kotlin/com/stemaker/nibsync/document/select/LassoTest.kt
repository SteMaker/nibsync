package com.stemaker.nibsync.document.select

import com.stemaker.nibsync.document.HIGHLIGHTER
import com.stemaker.nibsync.document.erase.horizontal
import com.stemaker.nibsync.document.erase.strokeOf
import com.stemaker.nibsync.model.Layer
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
    fun selectsTextBoxesMostlyInside() {
        val box = com.stemaker.nibsync.document.textBox("t", y = 60f) // x 10..60, y 60..70
        assertEquals(setOf(box.id), Lasso.select(strokes + box, rect(5f, 55f, 65f, 75f)))
        // Lasso over the left 40 % of the box only.
        assertEquals(emptySet(), Lasso.select(listOf(box), rect(5f, 55f, 30f, 75f)))
        assertEquals(setOf(box.id), Lasso.select(listOf(box), rect(5f, 55f, 45f, 75f)))
    }

    @Test
    fun clipboardCarriesTextBoxes() {
        val box = com.stemaker.nibsync.document.textBox("t", y = 60f)
        val clip = assertNotNull(ClipContent.of(listOf(a, box)))
        val placed = clip.placedAt(100f, 100f)
        val movedBox = placed[1] as com.stemaker.nibsync.model.TextBoxDraft
        // Bounds of a (9.75..20.25 x 9.75..10.25) and the box (10..60 x 60..70) are centred at (34.875, 39.875).
        assertEquals(75.125f, movedBox.x, 0.001f)
        assertEquals(120.125f, movedBox.y, 0.001f)
        assertEquals(box.text, movedBox.text)
    }

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
        val pasted = clip.placedAt(100f, 200f).map { it as com.stemaker.nibsync.model.StrokeDraft }
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
