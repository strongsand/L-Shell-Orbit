package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.beacon.BeaconSampleSource

/** Synthetic presentation only: no storage, equipment, network or service access. */
@Composable
private fun AntennaPreview(dark: Boolean = false, amoled: Boolean = false, count: Int = 3,
    expanded: Boolean = false, partial: Boolean = false, field: Boolean = false, percent: Float? = 0f) {
    CompositionLocalProvider(LocalOrbitDark provides dark, LocalOrbitMotion provides false,
        LocalExperience provides ExperienceSettings(theme = if (amoled) OrbitTheme.AMOLED else if (dark) OrbitTheme.DARK else OrbitTheme.LIGHT)) {
        LShellTheme(darkTheme = dark) {
            Surface {
                if (field) Box(Modifier.padding(16.dp)) {
                    AntennaFieldOfViewContent(percent,
                        stringResource(if (percent == null) R.string.rich_view_wait else if (percent == 0f) R.string.rich_view_clear else R.string.rich_view_blocked),
                        stringResource(if (percent == null) R.string.rich_view_no_data else if (percent == 0f) R.string.rich_view_good else R.string.rich_view_move))
                } else LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(stringResource(R.string.function_history), style = MaterialTheme.typography.headlineSmall) }
                    if (partial) item { Text(stringResource(R.string.antenna_partial)) }
                    if (count == 0) item { Text(stringResource(R.string.antenna_empty_timeline)) }
                    items((0 until count).toList(), key = { it }) { index ->
                        TemporalEventRow(1_800_000_000_000L + index * 60_000L,
                            stringResource(R.string.editorial_alerts),
                            stringResource(R.string.antenna_partial), initiallyExpanded = expanded)
                    }
                }
            }
        }
    }
}

@Preview(name = "Many events", locale = "pt-rBR", heightDp = 800)
@Composable private fun ManyEvents() = AntennaPreview(count = 40)
@Preview(name = "Few events", locale = "en")
@Composable private fun FewEvents() = AntennaPreview(count = 2)
@Preview(name = "Empty", locale = "es")
@Composable private fun NoEvents() = AntennaPreview(count = 0)
@Preview(name = "Expanded / partial", locale = "pt-rBR")
@Composable private fun ExpandedEvent() = AntennaPreview(count = 1, expanded = true, partial = true)
@Preview(name = "200% font / narrow", fontScale = 2f, widthDp = 320, heightDp = 800)
@Composable private fun LargeFont() = AntennaPreview(count = 2, expanded = true, partial = true)
@Preview(name = "150% font", fontScale = 1.5f, widthDp = 360)
@Composable private fun MediumFont() = AntennaPreview(count = 2)
@Preview(name = "Zero / light", locale = "pt-rBR")
@Composable private fun ZeroLight() = AntennaPreview(field = true)
@Preview(name = "Zero / dark", locale = "pt-rBR")
@Composable private fun ZeroDark() = AntennaPreview(field = true, dark = true)
@Preview(name = "Intermediate / AMOLED")
@Composable private fun Intermediate() = AntennaPreview(field = true, percent = 25f, dark = true, amoled = true)
@Preview(name = "100%")
@Composable private fun Full() = AntennaPreview(field = true, percent = 100f)
@Preview(name = "Missing / dark")
@Composable private fun Missing() = AntennaPreview(field = true, percent = null, dark = true)
@Preview(name = "Field / 200%", fontScale = 2f, widthDp = 320)
@Composable private fun LargeField() = AntennaPreview(field = true)

