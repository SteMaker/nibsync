package com.stemaker.nibsync.model

/**
 * Platform-neutral pen types. Each renderer (Android/Ink now, Windows later) maps a family to
 * its own brush implementation. Names are persisted, so never rename existing entries.
 */
enum class BrushFamily(val defaultLayer: Layer, val pressureSensitive: Boolean) {
    /** Constant-width round pen. */
    BALLPOINT(Layer.INK, pressureSensitive = false),

    /** Pressure- and speed-sensitive pen optimised for handwriting. */
    FOUNTAIN_PEN(Layer.INK, pressureSensitive = true),

    /** Translucent chisel-tip pen, drawn behind the ink. */
    HIGHLIGHTER(Layer.MARKER, pressureSensitive = false),
}

/**
 * Appearance of a stroke.
 *
 * @property colorArgb sRGB colour with alpha in the most significant byte.
 * @property sizeMm nominal stroke width in millimetres (document units).
 */
data class BrushSpec(
    val family: BrushFamily,
    val colorArgb: Int,
    val sizeMm: Float,
) {
    init {
        require(sizeMm > 0f && sizeMm.isFinite()) { "sizeMm must be positive, was $sizeMm" }
    }

    val alpha: Int get() = (colorArgb ushr 24) and 0xff
}

/** Kind of device that produced a stroke's input. Affects how some brushes react to pressure. */
enum class InputTool { UNKNOWN, STYLUS, TOUCH, MOUSE }
