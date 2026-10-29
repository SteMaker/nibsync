package com.stemaker.nibsync.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's theme on both platforms: neutral, slightly cool greys, so the colours that matter
 * are the user's ink. Light or dark follows the system; the paper stays white in both.
 */
@Composable
fun NibSyncTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNibSyncColors provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

/** Colours of the page surroundings that have no Material slot. */
@Immutable
data class NibSyncColors(
    /** Behind the paper. */
    val desk: Color,
    /** Soft shadow under the paper's edges. */
    val pageShadow: Color,
    /** Frame and handles of a selection on the paper (the paper is always light). */
    val selection: Color,
    /** "Open to-dos" mark in the library. */
    val todo: Color,
)

object NibSyncTheme {
    val colors: NibSyncColors
        @Composable @ReadOnlyComposable
        get() = LocalNibSyncColors.current
}

private val LocalNibSyncColors = staticCompositionLocalOf { LightExtras }

private val LightExtras = NibSyncColors(
    desk = Color(0xffdcdfe2),
    pageShadow = Color(0x26000000),
    selection = Color(0xff4f6b80),
    todo = Color(0xffc62828),
)

private val DarkExtras = NibSyncColors(
    desk = Color(0xff2a2d30),
    pageShadow = Color(0x66000000),
    selection = Color(0xff4f6b80),
    todo = Color(0xffef9a9a),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xff3c4247),
    onPrimary = Color(0xffffffff),
    primaryContainer = Color(0xffdde1e4),
    onPrimaryContainer = Color(0xff1a1d20),
    secondary = Color(0xff5b6268),
    onSecondary = Color(0xffffffff),
    secondaryContainer = Color(0xffe2e5e8),
    onSecondaryContainer = Color(0xff191c1f),
    tertiary = Color(0xff4f6b80),
    onTertiary = Color(0xffffffff),
    tertiaryContainer = Color(0xffd5e3ee),
    onTertiaryContainer = Color(0xff0b1d2a),
    background = Color(0xfff7f8f9),
    onBackground = Color(0xff1b1d1f),
    surface = Color(0xfff7f8f9),
    onSurface = Color(0xff1b1d1f),
    surfaceVariant = Color(0xffe1e4e7),
    onSurfaceVariant = Color(0xff44494e),
    surfaceTint = Color(0xff3c4247),
    surfaceBright = Color(0xfff7f8f9),
    surfaceDim = Color(0xffd8dadd),
    surfaceContainerLowest = Color(0xffffffff),
    surfaceContainerLow = Color(0xfff2f3f5),
    surfaceContainer = Color(0xffeceef0),
    surfaceContainerHigh = Color(0xffe6e8eb),
    surfaceContainerHighest = Color(0xffe0e3e6),
    inverseSurface = Color(0xff2f3133),
    inverseOnSurface = Color(0xfff0f1f2),
    inversePrimary = Color(0xffc3c8cd),
    outline = Color(0xff74797e),
    outlineVariant = Color(0xffc4c8cc),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xffc5cad0),
    onPrimary = Color(0xff2a2f33),
    primaryContainer = Color(0xff42484e),
    onPrimaryContainer = Color(0xffe1e4e8),
    secondary = Color(0xffbfc5cb),
    onSecondary = Color(0xff293036),
    secondaryContainer = Color(0xff3e444a),
    onSecondaryContainer = Color(0xffdce1e6),
    tertiary = Color(0xffa9c7dc),
    onTertiary = Color(0xff123446),
    tertiaryContainer = Color(0xff2d4b5e),
    onTertiaryContainer = Color(0xffd5e3ee),
    background = Color(0xff121416),
    onBackground = Color(0xffe2e3e5),
    surface = Color(0xff121416),
    onSurface = Color(0xffe2e3e5),
    surfaceVariant = Color(0xff3f4448),
    onSurfaceVariant = Color(0xffc3c7cb),
    surfaceTint = Color(0xffc5cad0),
    surfaceBright = Color(0xff383a3d),
    surfaceDim = Color(0xff121416),
    surfaceContainerLowest = Color(0xff0d0f10),
    surfaceContainerLow = Color(0xff1a1c1e),
    surfaceContainer = Color(0xff1e2022),
    surfaceContainerHigh = Color(0xff282a2d),
    surfaceContainerHighest = Color(0xff333538),
    inverseSurface = Color(0xffe2e3e5),
    inverseOnSurface = Color(0xff2f3133),
    inversePrimary = Color(0xff3c4247),
    outline = Color(0xff8d9196),
    outlineVariant = Color(0xff43474b),
)
