package de.nibsync.canvas

import de.nibsync.document.erase.EraserMode
import de.nibsync.model.BrushFamily
import de.nibsync.model.BrushSpec

/** Choices offered in the tool bar for each pen type. */
object PenPalette {
    val inkColors: List<Int> = listOf(
        0xff000000.toInt(), // black
        0xff1a3fa8.toInt(), // blue
        0xffc62828.toInt(), // red
        0xff2e7d32.toInt(), // green
        0xff6a1b9a.toInt(), // purple
        0xffef6c00.toInt(), // orange
    )

    /** Highlighter colours are translucent so the paper shows through. */
    val highlighterColors: List<Int> = listOf(
        0x80ffeb3b.toInt(), // yellow
        0x8076ff03.toInt(), // green
        0x80ff4081.toInt(), // pink
        0x8040c4ff.toInt(), // blue
        0x80ffab40.toInt(), // orange
        0x80b388ff.toInt(), // violet
    )

    fun colors(family: BrushFamily): List<Int> =
        if (family == BrushFamily.HIGHLIGHTER) highlighterColors else inkColors

    /** Three widths per pen in millimetres: fine, medium, broad. */
    /** Pixel eraser diameters in millimetres. */
    val eraserSizes: List<Float> = listOf(2f, 5f, 12f)

    /** The object eraser only needs to touch a stroke; a small fixed tip is the most precise. */
    const val OBJECT_ERASER_DIAMETER_MM: Float = 2f

    fun sizes(family: BrushFamily): List<Float> = when (family) {
        BrushFamily.BALLPOINT -> listOf(0.35f, 0.5f, 0.8f)
        BrushFamily.FOUNTAIN_PEN -> listOf(0.5f, 0.8f, 1.2f)
        BrushFamily.HIGHLIGHTER -> listOf(3f, 5f, 8f)
    }
}

data class PenSettings(val colorArgb: Int, val sizeMm: Float)

/** Tool bar state. Every pen type remembers its own colour and width. */
data class ToolState(
    val activePen: BrushFamily = BrushFamily.FOUNTAIN_PEN,
    val pens: Map<BrushFamily, PenSettings> = BrushFamily.entries.associateWith {
        PenSettings(PenPalette.colors(it).first(), PenPalette.sizes(it)[1])
    },
    val drawWithFinger: Boolean = false,
    /** When true the pen erases instead of writing; [activePen] is kept for switching back. */
    val eraserActive: Boolean = false,
    val eraserMode: EraserMode = EraserMode.OBJECT,
    val eraserSizeMm: Float = PenPalette.eraserSizes[1],
) {
    val eraserRadiusMm: Float
        get() = (if (eraserMode == EraserMode.OBJECT) PenPalette.OBJECT_ERASER_DIAMETER_MM else eraserSizeMm) / 2

    fun withEraser(mode: EraserMode = eraserMode) = copy(eraserActive = true, eraserMode = mode)

    fun withEraserSize(mm: Float) = copy(eraserSizeMm = mm)

    val activeSettings: PenSettings get() = pens.getValue(activePen)

    val activeBrush: BrushSpec
        get() = BrushSpec(activePen, activeSettings.colorArgb, activeSettings.sizeMm)

    fun withPen(pen: BrushFamily) = copy(activePen = pen, eraserActive = false)

    fun withColor(argb: Int) = copy(pens = pens + (activePen to activeSettings.copy(colorArgb = argb)))

    fun withSize(mm: Float) = copy(pens = pens + (activePen to activeSettings.copy(sizeMm = mm)))
}
