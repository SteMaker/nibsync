package com.stemaker.nibsync.model.geometry

import kotlin.math.max
import kotlin.math.min

/**
 * Maps the endless page (document units: millimetres) onto a view (pixels).
 *
 * At [zoom] 1 the page width exactly fills the view width less [sideMarginPx] on both sides
 * (where the desk shows). Zooming in allows horizontal
 * panning; zooming out centres the page horizontally. Vertically the page is endless: the
 * scrollable height is the written content plus [extraBottomFraction] of a screen, so the
 * page grows while writing near its end.
 *
 * Instances are immutable; every operation returns a new, clamped viewport.
 */
data class Viewport(
    val pageWidthMm: Float,
    val viewWidthPx: Float,
    val viewHeightPx: Float,
    val zoom: Float = 1f,
    /** Document x (mm) shown at the left view edge; only relevant when zoomed in. */
    val scrollXMm: Float = 0f,
    /** Document y (mm) shown at the top view edge. */
    val scrollYMm: Float = 0f,
    /** Lowest document y (mm) that holds content. */
    val contentBottomMm: Float = 0f,
    /** Minimum document height (mm), typically one page. */
    val minPageHeightMm: Float = 0f,
    val minZoom: Float = 0.5f,
    val maxZoom: Float = 4f,
    val extraBottomFraction: Float = 0.5f,
    /**
     * Pixels at the bottom of the view covered by something else (the on-screen keyboard):
     * the document can scroll that much further, so its end can still be seen.
     */
    val bottomInsetPx: Float = 0f,
    /** Desk left and right of the page at [zoom] 1. */
    val sideMarginPx: Float = 0f,
) {
    init {
        require(pageWidthMm > 0f) { "pageWidthMm must be positive" }
        require(minZoom in 0f..maxZoom && minZoom > 0f) { "invalid zoom range" }
    }

    val hasSize: Boolean get() = viewWidthPx > 0f && viewHeightPx > 0f

    /** Pixels per millimetre. */
    val scale: Float
        get() = if (viewWidthPx > 0f) max(1f, viewWidthPx - 2 * sideMarginPx) / pageWidthMm * zoom else 1f

    val visibleWidthMm: Float get() = viewWidthPx / scale
    val visibleHeightMm: Float get() = viewHeightPx / scale

    /** Total scrollable document height in millimetres. */
    val documentHeightMm: Float
        get() = max(max(minPageHeightMm, contentBottomMm + visibleHeightMm * extraBottomFraction), visibleHeightMm)

    private val maxScrollYMm: Float get() = max(0f, documentHeightMm - visibleHeightMm + bottomInsetPx / scale)

    private val maxScrollXMm: Float get() = max(0f, pageWidthMm - visibleWidthMm)

    /** Horizontal pixel offset that centres the page when it is narrower than the view. */
    private val centeringOffsetPx: Float
        get() = if (visibleWidthMm > pageWidthMm) (viewWidthPx - pageWidthMm * scale) / 2f else 0f

    /** Document (mm) to view (px). */
    val documentToView: ScaleTranslate
        get() = ScaleTranslate(scale, centeringOffsetPx - scrollXMm * scale, -scrollYMm * scale)

    /** View (px) to document (mm). */
    val viewToDocument: ScaleTranslate get() = documentToView.inverse()

    /** Visible part of the document in millimetres. */
    val visibleDocumentRect: Rect
        get() = viewToDocument.mapRect(Rect(0f, 0f, viewWidthPx, viewHeightPx))

    fun withViewSize(widthPx: Float, heightPx: Float): Viewport =
        copy(viewWidthPx = widthPx, viewHeightPx = heightPx).clamped()

    fun withContentBottom(bottomMm: Float): Viewport = copy(contentBottomMm = bottomMm).clamped()

    fun withBottomInset(px: Float): Viewport = copy(bottomInsetPx = max(0f, px)).clamped()

    /**
     * Scrolled as little as needed so that document y [topMm] to [bottomMm] is visible above
     * [bottomInsetPx]; if it does not fit, its bottom is shown (where one types).
     */
    fun revealing(topMm: Float, bottomMm: Float): Viewport {
        if (!hasSize) return this
        val usable = max(0f, viewHeightPx - bottomInsetPx) / scale
        var sy = scrollYMm
        if (topMm < sy) sy = topMm
        if (bottomMm > sy + usable) sy = bottomMm - usable
        return copy(scrollYMm = sy).clamped()
    }

    /** Moves the content by the given pixel distances (positive dy scrolls further down). */
    fun scrollByPx(dxPx: Float, dyPx: Float): Viewport =
        copy(scrollXMm = scrollXMm + dxPx / scale, scrollYMm = scrollYMm + dyPx / scale).clamped()

    /** Zooms by [factor] keeping the document point under the view pixel ([focusX], [focusY]) fixed. */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float): Viewport {
        if (!hasSize) return this
        val docX = viewToDocument.mapX(focusX)
        val docY = viewToDocument.mapY(focusY)
        val zoomed = copy(zoom = (zoom * factor).coerceIn(minZoom, maxZoom))
        // Solve for the scroll that maps (docX, docY) back to the focus point.
        val unscrolled = zoomed.copy(scrollXMm = 0f, scrollYMm = 0f)
        val noScroll = unscrolled.documentToView
        return zoomed.copy(
            scrollXMm = (noScroll.mapX(docX) - focusX) / zoomed.scale,
            scrollYMm = (noScroll.mapY(docY) - focusY) / zoomed.scale,
        ).clamped()
    }

    fun clamped(): Viewport {
        val z = zoom.coerceIn(minZoom, maxZoom)
        if (z != zoom) return copy(zoom = z).clamped()
        val sx = min(max(scrollXMm, 0f), maxScrollXMm)
        val sy = min(max(scrollYMm, 0f), maxScrollYMm)
        return if (sx == scrollXMm && sy == scrollYMm) this else copy(scrollXMm = sx, scrollYMm = sy)
    }
}
