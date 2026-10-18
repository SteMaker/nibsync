package com.stemaker.nibsync.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.stemaker.nibsync.model.RichText
import com.stemaker.nibsync.model.TextFont

/**
 * The embedded fonts (Noto Sans/Serif/Sans Mono, Caveat; latin subsets from Fontsource, SIL
 * OFL, see `shared/text/licenses`), the same files on every platform. Characters outside the
 * subsets fall back to system fonts. Mono and handwriting have no italic cut; Compose slants
 * them synthetically.
 */
object TextFonts {
    private val families = HashMap<TextFont, FontFamily>()

    fun family(font: TextFont): FontFamily = families.getOrPut(font) {
        val prefix = when (font) {
            TextFont.SANS -> "noto_sans"
            TextFont.SERIF -> "noto_serif"
            TextFont.MONO -> "noto_sans_mono"
            TextFont.HANDWRITING -> "caveat"
        }
        val italic = font == TextFont.SANS || font == TextFont.SERIF
        FontFamily(
            buildList {
                add(embeddedFont("${prefix}_400_normal", FontWeight.Normal, FontStyle.Normal))
                add(embeddedFont("${prefix}_700_normal", FontWeight.Bold, FontStyle.Normal))
                if (italic) {
                    add(embeddedFont("${prefix}_400_italic", FontWeight.Normal, FontStyle.Italic))
                    add(embeddedFont("${prefix}_700_italic", FontWeight.Bold, FontStyle.Italic))
                }
            },
        )
    }
}

/** One embedded font file by its base name (e.g. "noto_sans_400_normal"). */
internal expect fun embeddedFont(name: String, weight: FontWeight, style: FontStyle): Font

/**
 * Units of text layout. Font sizes are points (1 sp = 1 pt); a [Density] maps points to the
 * pixels of the target, so text scales with the page zoom like strokes do.
 */
object TextMetrics {
    /** Indentation per list level, in points (the editor's and the renderer's). */
    const val LIST_INDENT_PT = 20

    val TEXT_COLOR = Color(0xff202020)

    /** Text without explicit size or font marks. */
    val baseStyle = TextStyle(
        fontFamily = TextFonts.family(TextFont.SANS),
        fontSize = RichText.DEFAULT_SIZE_PT.sp,
        color = TEXT_COLOR,
    )

    /** A density for a page shown at [pxPerMm]: one point becomes `pxPerMm * MM_PER_PT` pixels. */
    fun densityFor(pxPerMm: Float): Density = Density(density = pxPerMm * RichText.MM_PER_PT, fontScale = 1f)
}
