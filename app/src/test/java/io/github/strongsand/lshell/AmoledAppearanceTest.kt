package io.github.strongsand.lshell

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class AmoledAppearanceTest {
    @Test fun selectedVariantSurvivesPersistenceAndLeavingAmoled() {
        val selected = ExperienceSettings(OrbitTheme.AMOLED, technical = true, amoledColors = AmoledColors.MONOCHROME)
        val saved = ExperienceSettingsCodec.encode(selected)
        assertEquals(selected, ExperienceSettingsCodec.decode(saved))
        for (theme in OrbitTheme.entries.filter { it != OrbitTheme.AMOLED }) {
            val other = ExperienceSettingsCodec.decode(ExperienceSettingsCodec.encode(selected.copy(theme = theme)))
            assertFalse(other.monochrome)
            assertTrue(other.technical)
            assertTrue(other.copy(theme = OrbitTheme.AMOLED).monochrome)
        }
    }

    @Test fun oldOrInvalidPreferencesKeepOtherSettingsAndDefaultToOriginal() {
        val old = mapOf("theme" to "AMOLED", "technical" to true, "haptics" to "EXPRESSIVE")
        val decoded = ExperienceSettingsCodec.decode(old)
        assertEquals(AmoledColors.ORIGINAL, decoded.amoledColors)
        assertTrue(decoded.technical)
        assertFalse(ExperienceSettingsCodec.encode(decoded).containsKey("haptics"))
        assertEquals(AmoledColors.ORIGINAL, ExperienceSettingsCodec.decode(old + ("amoled_colors" to "invalid")).amoledColors)
        assertEquals(ExperienceSettings(), ExperienceSettingsCodec.decode(emptyMap<String, Any>()))
    }

    @Test fun originalKeepsExistingAccentsWhileMonochromeHasOnlyNeutralRoles() {
        val base = darkColorScheme(primary = Color.Green, secondary = Color.Yellow, tertiary = Color.Cyan)
        val original = amoledPalette(base, AmoledColors.ORIGINAL)
        assertEquals(base.primary, original.primary)
        assertEquals(base.secondary, original.secondary)
        assertEquals(base.tertiary, original.tertiary)
        assertEquals(base.error, original.error)
        assertEquals(Color.Black, original.background)
        val mono = amoledPalette(base, AmoledColors.MONOCHROME)
        val roles = listOf(mono.primary, mono.onPrimary, mono.primaryContainer, mono.onPrimaryContainer,
            mono.secondary, mono.onSecondary, mono.secondaryContainer, mono.onSecondaryContainer,
            mono.tertiary, mono.onTertiary, mono.tertiaryContainer, mono.onTertiaryContainer,
            mono.error, mono.onError, mono.errorContainer, mono.onErrorContainer,
            mono.background, mono.onBackground, mono.surface, mono.onSurface, mono.onSurfaceVariant,
            mono.surfaceVariant, mono.surfaceDim, mono.surfaceBright, mono.surfaceContainerLowest,
            mono.surfaceContainerLow, mono.surfaceContainer, mono.surfaceContainerHigh, mono.surfaceContainerHighest,
            mono.outline, mono.outlineVariant, mono.inverseSurface, mono.inverseOnSurface, mono.inversePrimary,
            mono.primaryFixed, mono.primaryFixedDim, mono.onPrimaryFixed, mono.onPrimaryFixedVariant,
            mono.secondaryFixed, mono.secondaryFixedDim, mono.onSecondaryFixed, mono.onSecondaryFixedVariant,
            mono.tertiaryFixed, mono.tertiaryFixedDim, mono.onTertiaryFixed, mono.onTertiaryFixedVariant,
            mono.surfaceTint, mono.scrim)
        roles.forEach { assertEquals(it.red, it.green, .0001f); assertEquals(it.green, it.blue, .0001f) }
        assertEquals(Color.Black, mono.background)
        assertEquals(Color.White, mono.onSurface)
        // Distinct series luminances remain available in addition to line patterns.
        assertEquals(3, setOf(mono.primary, mono.secondary, mono.tertiary).size)
    }

    @Test fun foregroundPairsAndChartLinesMeetContrastTargets() {
        val p = amoledPalette(darkColorScheme(), AmoledColors.MONOCHROME)
        fun contrast(a: Color, b: Color): Float =
            (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
        listOf(p.onSurface to p.surface, p.onSurfaceVariant to p.surfaceContainerHighest,
            p.onPrimary to p.primary, p.onPrimaryContainer to p.primaryContainer,
            p.onSecondaryContainer to p.secondaryContainer, p.onErrorContainer to p.errorContainer)
            .forEach { (ink, background) -> assertTrue(contrast(ink, background) >= 4.5f) }
        listOf(p.primary, p.secondary, p.tertiary).forEach { assertTrue(contrast(it, p.surfaceContainerLow) >= 3f) }
    }

    @Test fun widgetsOnlyOverrideColorsForTheSelectedMonochromeTheme() {
        val original = 0xFF32BB66.toInt()
        val mono = ExperienceSettings(OrbitTheme.AMOLED, amoledColors = AmoledColors.MONOCHROME)
        assertEquals(0xFF000000.toInt(), widgetAppearanceColor(R.color.widget_background, mono, original))
        assertEquals(0xFFFFFFFF.toInt(), widgetAppearanceColor(R.color.widget_accent, mono, original))
        for (theme in OrbitTheme.entries.filter { it != OrbitTheme.AMOLED })
            assertEquals(original, widgetAppearanceColor(R.color.widget_accent, mono.copy(theme = theme), original))
        assertEquals(original, widgetAppearanceColor(R.color.widget_accent, mono.copy(amoledColors = AmoledColors.ORIGINAL), original))
    }
}
