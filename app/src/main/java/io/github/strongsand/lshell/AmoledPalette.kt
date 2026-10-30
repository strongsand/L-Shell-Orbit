package io.github.strongsand.lshell

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** Explicit roles, with no full-screen filter: camera and real images keep their colors. */
internal fun amoledPalette(base: ColorScheme, colors: AmoledColors): ColorScheme {
    val original = base.copy(
        background = Color.Black, onBackground = Color(0xFFF2F2F2),
        surface = Color.Black, onSurface = Color(0xFFF2F2F2),
        surfaceDim = Color.Black, surfaceBright = Color(0xFF181818),
        surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF080808),
        surfaceContainer = Color(0xFF0A0A0A), surfaceContainerHigh = Color(0xFF101010),
        surfaceContainerHighest = Color(0xFF181818),
        surfaceVariant = Color(0xFF101010), onSurfaceVariant = Color(0xFFBDBDBD),
        outline = Color(0xFF666666), outlineVariant = Color(0xFF242424), surfaceTint = Color.Black
    )
    if (colors == AmoledColors.ORIGINAL) return original
    return original.copy(
        primary = Color.White, onPrimary = Color.Black,
        primaryContainer = Color(0xFF161616), onPrimaryContainer = Color.White,
        inversePrimary = Color.Black,
        secondary = Color(0xFFBDBDBD), onSecondary = Color.Black,
        secondaryContainer = Color(0xFF1F1F1F), onSecondaryContainer = Color.White,
        tertiary = Color(0xFF858585), onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF242424), onTertiaryContainer = Color.White,
        onBackground = Color.White, onSurface = Color.White,
        inverseSurface = Color(0xFFE6E6E6), inverseOnSurface = Color(0xFF101010),
        error = Color.White, onError = Color.Black,
        errorContainer = Color(0xFF242424), onErrorContainer = Color.White,
        outline = Color(0xFF888888), outlineVariant = Color(0xFF444444), scrim = Color.Black,
        primaryFixed = Color(0xFFE6E6E6), primaryFixedDim = Color(0xFFCCCCCC),
        onPrimaryFixed = Color.Black, onPrimaryFixedVariant = Color(0xFF242424),
        secondaryFixed = Color(0xFFE6E6E6), secondaryFixedDim = Color(0xFFCCCCCC),
        onSecondaryFixed = Color.Black, onSecondaryFixedVariant = Color(0xFF242424),
        tertiaryFixed = Color(0xFFE6E6E6), tertiaryFixedDim = Color(0xFFCCCCCC),
        onTertiaryFixed = Color.Black, onTertiaryFixedVariant = Color(0xFF242424)
    )
}
