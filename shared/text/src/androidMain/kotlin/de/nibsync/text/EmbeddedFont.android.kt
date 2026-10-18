package de.nibsync.text

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import de.nibsync.text.shared.R

private val fontResources = mapOf(
    "noto_sans_400_normal" to R.font.noto_sans_400_normal,
    "noto_sans_400_italic" to R.font.noto_sans_400_italic,
    "noto_sans_700_normal" to R.font.noto_sans_700_normal,
    "noto_sans_700_italic" to R.font.noto_sans_700_italic,
    "noto_serif_400_normal" to R.font.noto_serif_400_normal,
    "noto_serif_400_italic" to R.font.noto_serif_400_italic,
    "noto_serif_700_normal" to R.font.noto_serif_700_normal,
    "noto_serif_700_italic" to R.font.noto_serif_700_italic,
    "noto_sans_mono_400_normal" to R.font.noto_sans_mono_400_normal,
    "noto_sans_mono_700_normal" to R.font.noto_sans_mono_700_normal,
    "caveat_400_normal" to R.font.caveat_400_normal,
    "caveat_700_normal" to R.font.caveat_700_normal,
)

internal actual fun embeddedFont(name: String, weight: FontWeight, style: FontStyle): Font =
    Font(fontResources.getValue(name), weight, style)
