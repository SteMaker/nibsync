package com.stemaker.nibsync.model

import kotlin.math.roundToInt

/**
 * The grid moved and new objects snap to, a setting of each device: none, the page's own lines
 * and dots ([PAGE], the default), or square cells of a fixed size on every page.
 */
data class SnapGrid(val mode: Mode = Mode.PAGE, val spacingMm: Float = 5f) {
    enum class Mode { OFF, PAGE, FIXED }

    /** The pattern points lie on: rows and columns as [BackgroundPattern] draws them. */
    fun pattern(background: BackgroundSpec): BackgroundSpec? = when (mode) {
        Mode.OFF -> null
        Mode.PAGE -> background.takeIf { BackgroundPattern.hasRows(it) }
        Mode.FIXED -> BackgroundSpec(BackgroundKind.DOTTED, spacingMm)
    }

    /**
     * ([x], [y]) mm moved to the nearest grid point on a page of [pageWidthMm] with [background].
     * Ruled paper has only rows: x stays.
     */
    fun snap(x: Float, y: Float, background: BackgroundSpec, pageWidthMm: Float): Pair<Float, Float> {
        val spec = pattern(background) ?: return x to y
        val s = spec.spacingMm
        val sy = maxOf(s, (y / s).roundToInt() * s)
        if (!BackgroundPattern.hasColumns(spec)) return x to sy
        // Columns are centred on the page (see BackgroundPattern.columns).
        val margin = (pageWidthMm - (pageWidthMm / s).toInt() * s) / 2
        val sx = margin + ((x - margin) / s).roundToInt() * s
        return sx.coerceIn(0f, pageWidthMm) to sy
    }

    /** How far to move something by ([dx], [dy]) so that its point ([x], [y]) lands on the grid. */
    fun snapMove(x: Float, y: Float, dx: Float, dy: Float, background: BackgroundSpec, pageWidthMm: Float): Pair<Float, Float> {
        val (sx, sy) = snap(x + dx, y + dy, background, pageWidthMm)
        return sx - x to sy - y
    }

    /** Stored in local settings as "off", "page" or the fixed size in mm. */
    fun toSetting(): String = when (mode) {
        Mode.OFF -> "off"
        Mode.PAGE -> "page"
        Mode.FIXED -> spacingMm.toString()
    }

    companion object {
        /**
         * The point of [elements] that snaps when they are moved: their top left corner, for text
         * on top where its first line is (as a new box is placed for a tap).
         */
        fun pointOf(elements: Collection<PageElement>): Pair<Float, Float>? {
            if (elements.isEmpty()) return null
            val left = elements.minOf { it.bounds.left }
            val first = elements.minBy { it.bounds.top }
            return left to first.bounds.top + if (first is TextBox) TextBox.FIRST_LINE_OFFSET_MM else 0f
        }

        /** Fixed sizes offered in the settings. */
        val FIXED_SIZES_MM = listOf(2.5f, 5f, 7f, 10f)

        fun fromSetting(value: String?): SnapGrid = when (value) {
            null, "page" -> SnapGrid()
            "off" -> SnapGrid(Mode.OFF)
            else -> value.toFloatOrNull()?.takeIf { it > 0f }?.let { SnapGrid(Mode.FIXED, it) } ?: SnapGrid()
        }
    }
}
