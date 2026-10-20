package com.stemaker.nibsync.canvas

import com.stemaker.nibsync.document.erase.EraserMode
import com.stemaker.nibsync.model.BrushFamily
import com.stemaker.nibsync.model.BrushSpec
import com.stemaker.nibsync.model.PenPreferences
import com.stemaker.nibsync.model.PenWidths

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

    /** Pixel eraser diameters in millimetres. */
    val eraserSizes: List<Float> = listOf(2f, 5f, 12f)

    /** The object eraser only needs to touch a stroke; a small fixed tip is the most precise. */
    const val OBJECT_ERASER_DIAMETER_MM: Float = 2f
}

data class PenSettings(val colorArgb: Int, val sizeMm: Float)

/** What the pen does on the page. */
enum class Tool { PEN, ERASER, LASSO, TEXT, TODO }

/** Tool bar state. Every pen type remembers its own colour and width. */
data class ToolState(
    val activePen: BrushFamily = BrushFamily.FOUNTAIN_PEN,
    val pens: Map<BrushFamily, PenSettings> = BrushFamily.entries.associateWith {
        PenSettings(PenPalette.colors(it).first(), PenWidths.DEFAULTS.getValue(it)[1])
    },
    /** Three widths per pen in millimetres (fine, medium, broad), as set in the settings. */
    val widths: Map<BrushFamily, List<Float>> = PenWidths.DEFAULTS,
    /** The pen's current job; [activePen] is kept while erasing or selecting, for switching back. */
    val tool: Tool = Tool.PEN,
    val eraserMode: EraserMode = EraserMode.OBJECT,
    val eraserSizeMm: Float = PenPalette.eraserSizes[1],
    /** New to-dos are handwritten (true) or typed. */
    val todoInk: Boolean = true,
) {
    val eraserRadiusMm: Float
        get() = (if (eraserMode == EraserMode.OBJECT) PenPalette.OBJECT_ERASER_DIAMETER_MM else eraserSizeMm) / 2

    val eraserActive: Boolean get() = tool == Tool.ERASER
    val lassoActive: Boolean get() = tool == Tool.LASSO
    val textActive: Boolean get() = tool == Tool.TEXT
    val todoActive: Boolean get() = tool == Tool.TODO

    fun withEraser(mode: EraserMode = eraserMode) = copy(tool = Tool.ERASER, eraserMode = mode)

    fun withLasso() = copy(tool = Tool.LASSO)

    fun withText() = copy(tool = Tool.TEXT)

    /** The to-do tool; to-dos are written with a pen, so the highlighter gives way to the ballpoint. */
    fun withTodo(ink: Boolean = todoInk) =
        copy(tool = Tool.TODO, todoInk = ink, activePen = if (activePen == BrushFamily.HIGHLIGHTER) BrushFamily.BALLPOINT else activePen)

    fun withEraserSize(mm: Float) = copy(eraserSizeMm = mm)

    val activeSettings: PenSettings get() = pens.getValue(activePen)

    val activeBrush: BrushSpec
        get() = BrushSpec(activePen, activeSettings.colorArgb, activeSettings.sizeMm)

    fun withPen(pen: BrushFamily) = copy(activePen = pen, tool = Tool.PEN)

    fun withColor(argb: Int) = copy(pens = pens + (activePen to activeSettings.copy(colorArgb = argb)))

    fun withSize(mm: Float) = copy(pens = pens + (activePen to activeSettings.copy(sizeMm = mm)))

    fun sizes(family: BrushFamily): List<Float> = widths[family] ?: PenWidths.DEFAULTS.getValue(family)

    /** New widths from the settings; every pen keeps its position (fine, medium, broad). */
    fun withWidths(newWidths: Map<BrushFamily, List<Float>>): ToolState {
        if (newWidths == widths) return this
        val next = copy(widths = newWidths)
        return next.copy(
            pens = pens.mapValues { (family, settings) ->
                val index = sizes(family).indexOf(settings.sizeMm).takeIf { it >= 0 } ?: 1
                settings.copy(sizeMm = next.sizes(family)[index])
            },
        )
    }

    companion object {
        /** The tools as left last time: the last pen with each pen's remembered width. */
        fun from(prefs: PenPreferences) = ToolState(
            activePen = prefs.lastPen ?: BrushFamily.FOUNTAIN_PEN,
            pens = BrushFamily.entries.associateWith { PenSettings(PenPalette.colors(it).first(), prefs.startWidth(it)) },
            widths = prefs.widths,
        )
    }
}
