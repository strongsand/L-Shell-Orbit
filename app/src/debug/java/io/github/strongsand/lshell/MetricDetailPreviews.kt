package io.github.strongsand.lshell

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Synthetic charts only: previews never open a DB, controller, worker or network connection. */
@Composable private fun MetricPreview(mode: String, dark: Boolean = false) {
    val start = 1_800_000_000_000L
    val rows = if (mode == "empty") emptyList() else (0 until if (mode == "dense") 3600 else 300).map {
        HistoryEntry(start + it * 1_000, "terminal_history", "", powerWatts = when {
            mode == "gaps" && it in 100..170 -> null
            mode == "outlier" && it == 150 -> 10_000f
            else -> 60f + (it % 17)
        })
    }
    val series = MetricHistorySeriesBuilder.build(rows, HistoricalMetric.POWER, start,
        start + if (mode == "dense") 3_600_000 else 300_000)
    CompositionLocalProvider(LocalOrbitDark provides dark, LocalOrbitMotion provides false) {
        LShellTheme(darkTheme = dark) { Surface { androidx.compose.foundation.layout.Box(Modifier.padding(12.dp)) {
            MetricDetailChart(series, "Synthetic power", "W")
        } } }
    }
}
@Preview(widthDp = 360, heightDp = 820) @Composable private fun MetricNormalPreview() = MetricPreview("normal")
@Preview(widthDp = 360, heightDp = 820) @Composable private fun MetricGapsPreview() = MetricPreview("gaps", true)
@Preview(widthDp = 360, heightDp = 820) @Composable private fun MetricOutlierPreview() = MetricPreview("outlier")
@Preview(widthDp = 320, heightDp = 1000, fontScale = 2f) @Composable private fun MetricDenseLargeFontPreview() = MetricPreview("dense", true)
@Preview(widthDp = 360, heightDp = 600) @Composable private fun MetricEmptyPreview() = MetricPreview("empty")
