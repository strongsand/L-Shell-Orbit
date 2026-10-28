package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Entirely synthetic: no database, network, services or equipment identifiers. */
@Composable
private fun ChartSystemPreview(sparse: Boolean = false, dark: Boolean = false, amoled: Boolean = false) {
    val start = 1_800_000_000_000L
    val points = remember(sparse) {
        (0 until if (sparse) 4 else 3_600).map { i ->
            val time = start + i * if (sparse) 900_000L else 1_000L
            MetricHistoryPoint(time, if ((!sparse && i in 1_000..1_400) || (sparse && i == 1)) null else
                TrafficValue((i % 70).toDouble(), when (i % 3) {
                    0 -> TrafficOrigin.LOCAL_PHONE; 1 -> TrafficOrigin.BEACON; else -> TrafficOrigin.MIXED
                }, if (sparse) 1_000L else 1_000L))
        }
    }
    val series = MetricHistorySeries(start, start + 3_600_000L, points, 0, emptyList())
    CompositionLocalProvider(LocalOrbitDark provides dark, LocalOrbitMotion provides false,
        LocalExperience provides ExperienceSettings(theme = if (amoled) OrbitTheme.AMOLED else if (dark) OrbitTheme.DARK else OrbitTheme.LIGHT)) {
        LShellTheme(darkTheme = dark) {
            Surface {
                Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompactHistoryChart(series, "Synthetic download", "Mbps")
                    CompactHistoryChart(series.copy(points = points.map { it.copy(measurement = it.measurement?.copy(value = it.measurement.value / 5)) }), "Synthetic upload", "Mbps")
                    ReportChart((0 until 8).map { i -> ReportChartBucket(start + i * 60_000, start + (i + 1) * 60_000,
                        if (i == 3) null else (i * 2).toFloat(), if (i == 3) 0 else 2, if (i % 2 == 0) 2 else 1, if (i % 2 == 0) 0 else 1) }, "Synthetic intervals", unit = "ms")
                }
            }
        }
    }
}

@Preview(name = "Dense multi-source light", widthDp = 360, heightDp = 900)
@Composable private fun ChartsDensePreview() = ChartSystemPreview()
@Preview(name = "Sparse gaps dark", widthDp = 320, heightDp = 900)
@Composable private fun ChartsSparsePreview() = ChartSystemPreview(sparse = true, dark = true)
@Preview(name = "AMOLED reduced motion", widthDp = 360, heightDp = 900)
@Composable private fun ChartsAmoledPreview() = ChartSystemPreview(dark = true, amoled = true)
@Preview(name = "Large font reduced motion", widthDp = 320, heightDp = 1200, fontScale = 2f)
@Composable private fun ChartsLargeFontPreview() = ChartSystemPreview(sparse = true)
