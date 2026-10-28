package io.github.strongsand.lshell.ar

import androidx.compose.runtime.Composable
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import io.github.strongsand.lshell.R

@Composable
internal fun generationLabel(generation: SatelliteGeneration): String = stringResource(when (generation) {
    SatelliteGeneration.UNKNOWN -> R.string.satellite_generation_unknown
    SatelliteGeneration.V1 -> R.string.satellite_generation_v1
    SatelliteGeneration.V1_5 -> R.string.satellite_generation_v1_5
    SatelliteGeneration.V2_MINI -> R.string.satellite_generation_v2
    SatelliteGeneration.V2_MINI_D2C -> R.string.satellite_generation_d2c
    SatelliteGeneration.V3 -> R.string.satellite_generation_v3
})

/** Theme-derived accents; the textual family label remains the authoritative legend. */
internal fun SatelliteGeneration.visualColor(scheme: ColorScheme): Color = when (this) {
    SatelliteGeneration.UNKNOWN -> scheme.onSurfaceVariant
    SatelliteGeneration.V1 -> scheme.primary
    SatelliteGeneration.V1_5 -> scheme.secondary
    SatelliteGeneration.V2_MINI -> scheme.tertiary
    SatelliteGeneration.V2_MINI_D2C -> lerp(scheme.primary, scheme.tertiary, 0.5f)
    SatelliteGeneration.V3 -> lerp(scheme.tertiary, scheme.onSurface, 0.4f)
}
