package com.stemaker.nibsync.desktop

import com.stemaker.nibsync.document.erase.EraserMode
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.PenPreferences
import com.stemaker.nibsync.model.PenWidths

/** What a mouse or pen drag does on the page. Typing comes first on the PC: [TEXT] is the default. */
enum class DesktopTool { TEXT, PEN, ERASER, LASSO, TODO }

/** The same choices as the Android tool bar (feature/canvas PenPalette). */
object DesktopPalette {
    val inkColors = listOf(0xff000000, 0xff1a3fa8, 0xffc62828, 0xff2e7d32, 0xff6a1b9a, 0xffef6c00).map { it.toInt() }
    val highlighterColors = listOf(0x80ffeb3b, 0x8076ff03, 0x80ff4081, 0x8040c4ff, 0x80ffab40, 0x80b388ff).map { it.toInt() }
    val eraserSizes = listOf(2f, 5f, 12f)
    const val OBJECT_ERASER_DIAMETER_MM = 2f

    fun colors(family: BrushFamily) = if (family == BrushFamily.HIGHLIGHTER) highlighterColors else inkColors
}

data class PenChoice(val colorArgb: Int, val sizeMm: Float)

/** Tool bar state; every pen type keeps its own colour and width. */
data class DesktopToolState(
    val tool: DesktopTool = DesktopTool.TEXT,
    val pen: BrushFamily = BrushFamily.BALLPOINT,
    val pens: Map<BrushFamily, PenChoice> = BrushFamily.entries.associateWith {
        PenChoice(DesktopPalette.colors(it).first(), PenWidths.DEFAULTS.getValue(it)[1])
    },
    /** Three widths per pen (fine, medium, broad), as set in the settings. */
    val widths: Map<BrushFamily, List<Float>> = PenWidths.DEFAULTS,
    val eraserMode: EraserMode = EraserMode.OBJECT,
    val eraserSizeMm: Float = DesktopPalette.eraserSizes[1],
    /** New to-dos are handwritten (true) or typed. */
    val todoInk: Boolean = false,
) {
    val choice: PenChoice get() = pens.getValue(pen)
    val brush: BrushSpec get() = BrushSpec(pen, choice.colorArgb, choice.sizeMm)
    val eraserRadiusMm: Float
        get() = (if (eraserMode == EraserMode.OBJECT) DesktopPalette.OBJECT_ERASER_DIAMETER_MM else eraserSizeMm) / 2

    fun sizes(family: BrushFamily): List<Float> = widths[family] ?: PenWidths.DEFAULTS.getValue(family)

    /** New widths from the settings; every pen keeps its position (fine, medium, broad). */
    fun withWidths(newWidths: Map<BrushFamily, List<Float>>): DesktopToolState {
        if (newWidths == widths) return this
        val next = copy(widths = newWidths)
        return next.copy(
            pens = pens.mapValues { (family, choice) ->
                val index = sizes(family).indexOf(choice.sizeMm).takeIf { it >= 0 } ?: 1
                choice.copy(sizeMm = next.sizes(family)[index])
            },
        )
    }

    companion object {
        /** The tools as left last time: the last pen with each pen's remembered width (typing stays the default tool). */
        fun from(prefs: PenPreferences) = DesktopToolState(
            pen = prefs.lastPen ?: BrushFamily.BALLPOINT,
            pens = BrushFamily.entries.associateWith { PenChoice(DesktopPalette.colors(it).first(), prefs.startWidth(it)) },
            widths = prefs.widths,
        )
    }
}
