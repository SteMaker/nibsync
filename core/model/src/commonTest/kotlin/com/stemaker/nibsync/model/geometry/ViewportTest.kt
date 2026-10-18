package com.stemaker.nibsync.model.geometry

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun assertClose(expected: Float, actual: Float, eps: Float = 1e-3f) =
    assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")

class ScaleTranslateTest {
    @Test
    fun inverseRoundTrips() {
        val t = ScaleTranslate(2.5f, 10f, -40f)
        val inv = t.inverse()
        assertClose(3f, inv.mapX(t.mapX(3f)))
        assertClose(-7f, inv.mapY(t.mapY(-7f)))
    }

    @Test
    fun matrixValuesAreRowMajor() {
        assertEquals(
            listOf(2f, 0f, 3f, 0f, 2f, 4f, 0f, 0f, 1f),
            ScaleTranslate(2f, 3f, 4f).toMatrixValues().toList(),
        )
    }
}

class ViewportTest {
    // A4 page on a 2100 x 1500 px view: exactly 10 px per mm at zoom 1.
    private val base = Viewport(pageWidthMm = 210f, viewWidthPx = 2100f, viewHeightPx = 1500f, minPageHeightMm = 297f)

    @Test
    fun pageWidthFillsViewAtZoom1() {
        assertClose(10f, base.scale)
        assertClose(0f, base.documentToView.mapX(0f))
        assertClose(2100f, base.documentToView.mapX(210f))
    }

    @Test
    fun documentIsAtLeastOnePageHigh() {
        assertClose(297f, base.documentHeightMm)
    }

    @Test
    fun documentGrowsWithContent() {
        val v = base.withContentBottom(1000f)
        // content + half a screen (150 mm visible / 2)
        assertClose(1075f, v.documentHeightMm)
    }

    @Test
    fun scrollIsClampedToDocument() {
        val v = base.scrollByPx(0f, 1_000_000f)
        assertClose(297f - 150f, v.scrollYMm)
        assertClose(0f, v.scrollByPx(0f, -1e9f).scrollYMm)
    }

    @Test
    fun scrollingFurtherAfterWritingExtendsThePage() {
        val v = base.scrollByPx(0f, 1e9f)
        val bottomBefore = v.scrollYMm
        val grown = v.withContentBottom(v.visibleDocumentRect.bottom).scrollByPx(0f, 1e9f)
        assertTrue(grown.scrollYMm > bottomBefore)
    }

    @Test
    fun noHorizontalScrollAtZoom1() {
        assertClose(0f, base.scrollByPx(500f, 0f).scrollXMm)
    }

    @Test
    fun zoomKeepsFocusPointFixed() {
        val v = base.withContentBottom(2000f).scrollByPx(0f, 3000f)
        val focusX = 700f
        val focusY = 400f
        val docX = v.viewToDocument.mapX(focusX)
        val docY = v.viewToDocument.mapY(focusY)
        val z = v.zoomBy(2f, focusX, focusY)
        assertClose(2f, z.zoom)
        assertClose(focusX, z.documentToView.mapX(docX), 0.05f)
        assertClose(focusY, z.documentToView.mapY(docY), 0.05f)
    }

    @Test
    fun zoomIsClamped() {
        assertClose(4f, base.zoomBy(100f, 0f, 0f).zoom)
        assertClose(0.5f, base.zoomBy(0.001f, 0f, 0f).zoom)
    }

    @Test
    fun zoomedOutPageIsCentred() {
        val v = base.zoomBy(0.5f, 0f, 0f)
        val left = v.documentToView.mapX(0f)
        val right = v.documentToView.mapX(210f)
        assertClose(2100f - right, left)
        assertClose(525f, left)
    }

    @Test
    fun visibleRectMatchesScroll() {
        val v = base.withContentBottom(5000f).scrollByPx(0f, 2000f)
        val r = v.visibleDocumentRect
        assertClose(200f, r.top)
        assertClose(350f, r.bottom)
        assertClose(0f, r.left)
        assertClose(210f, r.right)
    }

    @Test
    fun resizeKeepsScrollValid() {
        val v = base.scrollByPx(0f, 1e9f).withViewSize(2100f, 3000f)
        assertClose(0f, v.scrollYMm)
    }

    @Test
    fun rectIntersection() {
        val a = Rect(0f, 0f, 10f, 10f)
        assertTrue(a.intersects(Rect(5f, 5f, 15f, 15f)))
        assertTrue(!a.intersects(Rect(11f, 0f, 20f, 10f)))
        assertTrue(a.contains(10f, 0f))
    }
}
