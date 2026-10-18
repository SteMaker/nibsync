package de.nibsync.text

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

// The font files are on the classpath (see build.gradle.kts).
internal actual fun embeddedFont(name: String, weight: FontWeight, style: FontStyle): Font =
    androidx.compose.ui.text.platform.Font("$name.ttf", weight, style)
