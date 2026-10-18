package de.nibsync.model

/** ISO 216 A-series paper sizes in portrait orientation, in millimetres. */
enum class PaperSize(val shortSideMm: Float, val longSideMm: Float) {
    A0(841f, 1189f),
    A1(594f, 841f),
    A2(420f, 594f),
    A3(297f, 420f),
    A4(210f, 297f),
    A5(148f, 210f),
    A6(105f, 148f),
}

enum class Orientation { PORTRAIT, LANDSCAPE }

/**
 * Format of a note document. The width is fixed by the paper size; the document is endless
 * vertically. [pageHeightMm] is only used to show optional page breaks.
 */
data class PageFormat(
    val paper: PaperSize = PaperSize.A4,
    val orientation: Orientation = Orientation.PORTRAIT,
) {
    val widthMm: Float
        get() = if (orientation == Orientation.PORTRAIT) paper.shortSideMm else paper.longSideMm

    val pageHeightMm: Float
        get() = if (orientation == Orientation.PORTRAIT) paper.longSideMm else paper.shortSideMm
}

/** Background pattern of a document, drawn behind all strokes. See [BackgroundPattern]. */
enum class BackgroundKind { BLANK, LINED, GRID, DOTTED }

data class BackgroundSpec(
    val kind: BackgroundKind = BackgroundKind.BLANK,
    val spacingMm: Float = 7f,
) {
    init {
        require(spacingMm > 0f) { "spacingMm must be positive" }
    }

    companion object {
        /** Spacings offered in the UI; any positive value is valid in files. */
        val SPACINGS_MM = listOf(5f, 7f, 10f)

        /** Usual spacing per kind: ruled paper 7 mm, squared and dotted paper 5 mm. */
        fun of(kind: BackgroundKind): BackgroundSpec =
            BackgroundSpec(kind, if (kind == BackgroundKind.LINED || kind == BackgroundKind.BLANK) 7f else 5f)
    }
}

/** Descriptive data of a document that is not made of strokes. */
data class DocumentMeta(
    val id: DocumentId,
    val title: String,
    val format: PageFormat = PageFormat(),
    val background: BackgroundSpec = BackgroundSpec(),
    val createdAtEpochMs: Long,
)
