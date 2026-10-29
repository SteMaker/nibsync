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
fun NibSyncTheme(
    dark: Boolean = isSystemInDarkTheme(),
    palette: NibSyncPalette = NibSyncPalette.GREY,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalNibSyncColors provides palette.extras(dark)) {
        MaterialTheme(colorScheme = palette.scheme(dark), content = content)
    }
}

/** Light, dark, or as the system is set. Persisted by name: never rename. */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    @Composable
    fun isDark(): Boolean = when (this) {
        SYSTEM -> isSystemInDarkTheme()
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun of(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * Colour schemes to choose from (persisted by name: never rename): [GREY] is the neutral
 * default; the others tint the greys towards an accent ([accent] for buttons and active tools,
 * [neutral] for surfaces), [BLUE] and [RED] with more colour ([strength]).
 */
enum class NibSyncPalette(val accent: Color?, private val neutral: Color?, private val strength: Float = 1f) {
    GREY(null, null),
    SLATE(Color(0xff3f5a75), Color(0xff5d6b78)),
    PETROL(Color(0xff2f6670), Color(0xff5b7275)),
    SAGE(Color(0xff47664f), Color(0xff6b7a6c)),
    WARM(Color(0xff6b5a4a), Color(0xff8a7560)),
    INK(Color(0xff3d4f8f), Color(0xff626a85)),
    BLUE(Color(0xff1f5bb5), Color(0xff3f6db0), strength = 2.2f),
    RED(Color(0xffb23a3a), Color(0xffa45a52), strength = 2.2f),
    ;

    fun scheme(dark: Boolean): ColorScheme {
        val a = accent ?: return if (dark) DarkScheme else LightScheme
        val n = neutral!!
        return if (dark) tintedDark(a, n, strength) else tintedLight(a, n, strength)
    }

    /** Page surroundings, the desk tinted like the surfaces. */
    fun extras(dark: Boolean): NibSyncColors {
        val base = if (dark) DarkExtras else LightExtras
        val n = neutral ?: return base
        return base.copy(desk = mix(base.desk, n, (if (dark) 0.12f else 0.1f) * strength))
    }

    companion object {
        fun of(name: String?): NibSyncPalette = entries.firstOrNull { it.name == name } ?: GREY
    }
}

private fun mix(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

private fun tintedLight(accent: Color, neutral: Color, strength: Float): ColorScheme {
    val white = Color.White
    fun tint(t: Float) = mix(white, neutral, (t * strength).coerceAtMost(0.6f))
    val ink = mix(Color(0xff1b1d1f), neutral, 0.15f)
    return LightScheme.copy(
        primary = accent,
        onPrimary = white,
        primaryContainer = mix(white, accent, 0.16f),
        onPrimaryContainer = mix(accent, Color.Black, 0.55f),
        secondary = mix(accent, Color(0xff5b6268), 0.55f),
        secondaryContainer = tint(0.16f),
        onSecondaryContainer = mix(Color(0xff191c1f), neutral, 0.2f),
        background = tint(0.05f),
        onBackground = ink,
        surface = tint(0.05f),
        onSurface = ink,
        surfaceVariant = tint(0.18f),
        onSurfaceVariant = mix(Color(0xff44494e), neutral, 0.3f),
        surfaceTint = accent,
        surfaceBright = tint(0.05f),
        surfaceDim = tint(0.2f),
        surfaceContainerLowest = white,
        surfaceContainerLow = tint(0.07f),
        surfaceContainer = tint(0.1f),
        surfaceContainerHigh = tint(0.13f),
        surfaceContainerHighest = tint(0.16f),
        outline = mix(Color(0xff74797e), neutral, 0.3f),
        outlineVariant = tint(0.3f),
        inversePrimary = mix(white, accent, 0.45f),
    )
}

private fun tintedDark(accent: Color, neutral: Color, strength: Float): ColorScheme {
    val black = Color(0xff101214)
    fun tint(t: Float) = mix(black, neutral, (t * strength).coerceAtMost(0.5f))
    val light = mix(Color.White, accent, 0.45f)
    return DarkScheme.copy(
        primary = light,
        onPrimary = mix(accent, Color.Black, 0.6f),
        primaryContainer = mix(black, accent, 0.45f),
        onPrimaryContainer = mix(Color.White, accent, 0.15f),
        secondaryContainer = tint(0.3f),
        background = tint(0.04f),
        surface = tint(0.04f),
        surfaceVariant = tint(0.3f),
        surfaceTint = light,
        surfaceDim = tint(0.04f),
        surfaceBright = tint(0.25f),
        surfaceContainerLowest = black,
        surfaceContainerLow = tint(0.08f),
        surfaceContainer = tint(0.11f),
        surfaceContainerHigh = tint(0.16f),
        surfaceContainerHighest = tint(0.21f),
        inversePrimary = accent,
    )
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
