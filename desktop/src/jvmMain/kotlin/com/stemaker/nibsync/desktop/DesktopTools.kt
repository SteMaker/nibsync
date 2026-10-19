package com.stemaker.nibsync.desktop

import com.stemaker.nibsync.document.erase.EraserMode
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec

/** What a mouse or pen drag does on the page. Typing comes first on the PC: [TEXT] is the default. */
enum class DesktopTool { TEXT, PEN, ERASER, LASSO, TODO }

/** The same choices as the Android tool bar (feature/canvas PenPalette). */
object DesktopPalette {
    val inkColors = listOf(0xff000000, 0xff1a3fa8, 0xffc62828, 0xff2e7d32, 0xff6a1b9a, 0xffef6c00).map { it.toInt() }
    val highlighterColors = listOf(0x80ffeb3b, 0x8076ff03, 0x80ff4081, 0x8040c4ff, 0x80ffab40, 0x80b388ff).map { it.toInt() }
    val eraserSizes = listOf(2f, 5f, 12f)
    const val OBJECT_ERASER_DIAMETER_MM = 2f

    fun colors(family: BrushFamily) = if (family == BrushFamily.HIGHLIGHTER) highlighterColors else inkColors

    fun sizes(family: BrushFamily) = when (family) {
        BrushFamily.BALLPOINT -> listOf(0.35f, 0.5f, 0.8f)
        BrushFamily.FOUNTAIN_PEN -> listOf(0.5f, 0.8f, 1.2f)
        BrushFamily.HIGHLIGHTER -> listOf(3f, 5f, 8f)
    }
}

data class PenChoice(val colorArgb: Int, val sizeMm: Float)

/** Tool bar state; every pen type keeps its own colour and width. */
data class DesktopToolState(
    val tool: DesktopTool = DesktopTool.TEXT,
    val pen: BrushFamily = BrushFamily.BALLPOINT,
    val pens: Map<BrushFamily, PenChoice> = BrushFamily.entries.associateWith {
        PenChoice(DesktopPalette.colors(it).first(), DesktopPalette.sizes(it)[1])
    },
    val eraserMode: EraserMode = EraserMode.OBJECT,
    val eraserSizeMm: Float = DesktopPalette.eraserSizes[1],
    /** New to-dos are handwritten (true) or typed. */
    val todoInk: Boolean = false,
) {
    val choice: PenChoice get() = pens.getValue(pen)
    val brush: BrushSpec get() = BrushSpec(pen, choice.colorArgb, choice.sizeMm)
    val eraserRadiusMm: Float
        get() = (if (eraserMode == EraserMode.OBJECT) DesktopPalette.OBJECT_ERASER_DIAMETER_MM else eraserSizeMm) / 2
}
