package com.stemaker.nibsync.model

/** The three widths (fine, medium, broad) the tool bar offers per pen; changeable in the settings. */
object PenWidths {
    val DEFAULTS: Map<BrushFamily, List<Float>> = mapOf(
        BrushFamily.BALLPOINT to listOf(0.35f, 0.5f, 0.8f),
        BrushFamily.FOUNTAIN_PEN to listOf(0.5f, 0.8f, 1.2f),
        BrushFamily.HIGHLIGHTER to listOf(3f, 5f, 8f),
    )

    const val COUNT = 3

    /** Widths the settings accept, in mm. */
    fun range(family: BrushFamily): ClosedFloatingPointRange<Float> =
        if (family == BrushFamily.HIGHLIGHTER) 1f..20f else 0.1f..5f

    /** [text] as a width for [family] ("0,7" works too), or null if it is no number in [range]. */
    fun parse(family: BrushFamily, text: String): Float? =
        text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it in range(family) }?.let { Quantization.quantizePosition(it) }

    fun format(mm: Float): String = Quantization.quantizePosition(mm).toString().removeSuffix(".0")
}

/**
 * Pen choices kept on the device between app runs: the widths set in the settings, the last
 * writing pen (ballpoint or fountain pen; the highlighter is only used in between) and which of
 * the three widths each pen had. Stored as plain strings by each app.
 */
data class PenPreferences(
    val widths: Map<BrushFamily, List<Float>> = PenWidths.DEFAULTS,
    val lastPen: BrushFamily? = null,
    val widthIndex: Map<BrushFamily, Int> = emptyMap(),
) {
    fun widths(family: BrushFamily): List<Float> = widths[family] ?: PenWidths.DEFAULTS.getValue(family)

    /** The width [family] starts with: the remembered one, else the medium width. */
    fun startWidth(family: BrushFamily): Float = widths(family)[widthIndex[family]?.coerceIn(0, PenWidths.COUNT - 1) ?: 1]

    /** Remembers that [family] writes with [sizeMm] (one of its widths), and, if it writes text, that it was used last. */
    fun used(family: BrushFamily, sizeMm: Float): PenPreferences {
        val index = widths(family).indexOf(sizeMm)
        return copy(
            lastPen = if (family == BrushFamily.HIGHLIGHTER) lastPen else family,
            widthIndex = if (index < 0) widthIndex else widthIndex + (family to index),
        )
    }

    /** Key/value pairs for the app's settings store. */
    fun toStrings(): Map<String, String> = buildMap {
        for (family in BrushFamily.entries) {
            put(widthsKey(family), widths(family).joinToString(",") { PenWidths.format(it) })
            widthIndex[family]?.let { put(indexKey(family), it.toString()) }
        }
        lastPen?.let { put(LAST_PEN, it.name) }
    }

    companion object {
        private const val LAST_PEN = "pen.last"
        private fun widthsKey(family: BrushFamily) = "pen.${family.name}.widths"
        private fun indexKey(family: BrushFamily) = "pen.${family.name}.width"

        val KEYS: List<String> = listOf(LAST_PEN) + BrushFamily.entries.flatMap { listOf(widthsKey(it), indexKey(it)) }

        /** Reads what [toStrings] wrote; missing or broken values fall back to the defaults. */
        fun fromStrings(get: (String) -> String?): PenPreferences {
            val widths = BrushFamily.entries.associateWith { family ->
                get(widthsKey(family))?.split(',')?.map { PenWidths.parse(family, it) }
                    ?.takeIf { list -> list.size == PenWidths.COUNT && list.all { it != null } }?.map { it!! }
                    ?: PenWidths.DEFAULTS.getValue(family)
            }
            val index = BrushFamily.entries.mapNotNull { family ->
                get(indexKey(family))?.toIntOrNull()?.takeIf { it in 0 until PenWidths.COUNT }?.let { family to it }
            }.toMap()
            val last = get(LAST_PEN)?.let { name -> BrushFamily.entries.firstOrNull { it.name == name && it != BrushFamily.HIGHLIGHTER } }
            return PenPreferences(widths, last, index)
        }
    }
}
