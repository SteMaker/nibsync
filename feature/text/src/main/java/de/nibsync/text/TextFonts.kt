package de.nibsync.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import de.nibsync.model.RichText
import de.nibsync.model.TextFont

/**
 * The embedded fonts (Noto Sans/Serif/Sans Mono, Caveat; latin subsets from Fontsource, SIL
 * OFL, see `feature/text/licenses`). Characters outside the subsets fall back to system fonts.
 * Mono and handwriting have no italic cut; Compose slants them synthetically.
 */
object TextFonts {
    private val sans = FontFamily(
        Font(R.font.noto_sans_400_normal, FontWeight.Normal),
        Font(R.font.noto_sans_400_italic, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.noto_sans_700_normal, FontWeight.Bold),
        Font(R.font.noto_sans_700_italic, FontWeight.Bold, FontStyle.Italic),
    )
    private val serif = FontFamily(
        Font(R.font.noto_serif_400_normal, FontWeight.Normal),
        Font(R.font.noto_serif_400_italic, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.noto_serif_700_normal, FontWeight.Bold),
        Font(R.font.noto_serif_700_italic, FontWeight.Bold, FontStyle.Italic),
    )
    private val mono = FontFamily(
        Font(R.font.noto_sans_mono_400_normal, FontWeight.Normal),
        Font(R.font.noto_sans_mono_700_normal, FontWeight.Bold),
    )
    private val handwriting = FontFamily(
        Font(R.font.caveat_400_normal, FontWeight.Normal),
        Font(R.font.caveat_700_normal, FontWeight.Bold),
    )

    fun family(font: TextFont): FontFamily = when (font) {
        TextFont.SANS -> sans
        TextFont.SERIF -> serif
        TextFont.MONO -> mono
        TextFont.HANDWRITING -> handwriting
    }
}

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
