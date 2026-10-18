package com.stemaker.nibsync.model

import kotlin.math.ceil

/**
 * Where the lines and dots of a [BackgroundSpec] go, in document millimetres. Platform
 * renderers only draw what this returns, so every device shows the same paper.
 *
 * Rows (horizontal lines, dot rows) start one spacing below the top edge. Columns (vertical
 * grid lines, dot columns) are centred on the page, so the margins left and right are equal.
 */
object BackgroundPattern {
    /** Upper limit of rows or columns returned for one call; beyond that the pattern is noise. */
    const val MAX_LINES = 4000

    /** True if the pattern has horizontal lines or dot rows (everything but BLANK). */
    fun hasRows(spec: BackgroundSpec): Boolean = spec.kind != BackgroundKind.BLANK

    /** True if the pattern has columns (GRID, DOTTED). */
    fun hasColumns(spec: BackgroundSpec): Boolean =
        spec.kind == BackgroundKind.GRID || spec.kind == BackgroundKind.DOTTED

    /** True if rows and columns are drawn as dots at their crossings instead of lines. */
    fun isDotted(spec: BackgroundSpec): Boolean = spec.kind == BackgroundKind.DOTTED

    /** y positions of rows within [topMm]..[bottomMm] (inclusive), top to bottom. */
    fun rows(spec: BackgroundSpec, topMm: Float, bottomMm: Float): List<Float> {
        if (!hasRows(spec) || bottomMm < topMm) return emptyList()
        val s = spec.spacingMm
        val first = maxOf(1, ceil(topMm / s).toInt())
        val out = ArrayList<Float>()
        var k = first
        while (out.size < MAX_LINES) {
            val y = k * s
            if (y > bottomMm) break
            out += y
            k++
        }
        return out
    }

    /** x positions of columns across a page of [pageWidthMm], left to right, strictly inside it. */
    fun columns(spec: BackgroundSpec, pageWidthMm: Float): List<Float> {
        if (!hasColumns(spec) || pageWidthMm <= 0f) return emptyList()
        val s = spec.spacingMm
        val count = (pageWidthMm / s).toInt().coerceAtMost(MAX_LINES)
        val margin = (pageWidthMm - count * s) / 2
        // With no remainder the outer lines would sit on the page edges: leave those out.
        return (0..count).map { margin + it * s }.filter { it > EDGE_MM && it < pageWidthMm - EDGE_MM }
    }

    private const val EDGE_MM = 0.01f
}
