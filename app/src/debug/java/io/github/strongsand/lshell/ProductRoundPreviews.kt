package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Only synthetic values; production content, no databases, services or networking. */
@Composable private fun ProductPreview(dark: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOrbitMotion provides false, LocalOrbitDark provides dark) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) { Surface { content() } }
    }
}
@Composable private fun EventPreview(count: Int = 3, expanded: Boolean = false) = ProductPreview {
    val events = List(count) { i -> HistoryOccurrence("synthetic:$i", 1800000000000 + i * 60000L,
        "Conexão restaurada", "Falha durou 12 s", OccurrenceCategory.CONNECTION) }
    LazyColumn(Modifier.padding(16.dp)) {
        item { Text(stringResource(R.string.function_history), style = MaterialTheme.typography.headlineSmall) }
        if (events.isEmpty()) item { Text(stringResource(R.string.product_no_events)) }
        items(events, key = { it.key }) { OccurrenceRow(it, expanded) }
    }
}
@Preview(name = "History empty", locale = "pt-rBR") @Composable private fun EmptyHistory() = EventPreview(0)
@Preview(name = "History few", locale = "en") @Composable private fun FewHistory() = EventPreview(3)
@Preview(name = "History dense", heightDp = 800) @Composable private fun DenseHistory() = EventPreview(70)
@Preview(name = "Grouped actual event / expanded", locale = "pt-rBR", fontScale = 2f, widthDp = 320) @Composable private fun ExpandedHistory() = EventPreview(2, true)

@Preview(name = "Hub", locale = "pt-rBR", heightDp = 850) @Composable private fun Hub() = ProductPreview { SpeedTestHubContent(ConnectionTestState()) }
@Preview(name = "Hub active", locale = "en", heightDp = 900) @Composable private fun HubActive() = ProductPreview { SpeedTestHubContent(ConnectionTestState(id = 1, phase = ConnectionTestPhase.RUNNING)) }
@Preview(name = "Hub with old result", locale = "es", fontScale = 1.5f, heightDp = 1000) @Composable private fun HubOldResult() = ProductPreview { SpeedTestHubContent(ConnectionTestState(id = 1, phase = ConnectionTestPhase.COMPLETED)) }

@Composable private fun AutoPreview(smart: Boolean = false, enabled: Boolean = true, used: Int = 3) = ProductPreview {
    AutomaticTestsContent(smart, AutomaticTestSettings(scheduled = enabled, smart = enabled, intervalHours = 6, smartDailyLimit = 4, cooldownHours = 6),
        used, used.coerceAtMost(4), when { !enabled -> AutomaticScreenStatus.DISABLED;
            used == 8 -> AutomaticScreenStatus.DAILY_LIMIT_REACHED;
            smart -> AutomaticScreenStatus.WAITING; else -> AutomaticScreenStatus.SCHEDULED },
        1_800_000_000_000L, 1_800_000_000_000L, 20_833L, null, null, true)
}
@Preview(name = "Automation off", locale = "pt-rBR", heightDp = 800) @Composable private fun AutoOff() = AutoPreview(enabled = false)
@Preview(name = "Automation on", locale = "en", heightDp = 800) @Composable private fun AutoOn() = AutoPreview(used = 0)
@Preview(name = "Shared budget partial", locale = "pt-rBR", fontScale = 1.5f, heightDp = 1000) @Composable private fun AutoPartial() = AutoPreview()
@Preview(name = "Shared budget exhausted", locale = "es", heightDp = 800) @Composable private fun AutoFull() = AutoPreview(used = 8)
@Preview(name = "Smart waiting", locale = "pt-rBR", fontScale = 2f, widthDp = 320, heightDp = 1200) @Composable private fun SmartIdle() = AutoPreview(smart = true)

private fun previewTraffic(dense: Boolean): TrafficView {
    val rows = List(if (dense) 3000 else 60) { i -> HistoryEntry(1800000000000 + i * 1000L, "terminal_history", "",
        downloadMbps = if (i % 30 in 12..16) Float.NaN else 12f + i % 11,
        uploadMbps = 1f + i % 3, latencyMs = 20f + i % 5, dropPercent = if (i % 30 == 0) 5f else 0f) }
    val a = TrafficSessionAnalyzer.analyze(rows, rows.first().timestamp, rows.last().timestamp + 1000)
    return TrafficView(a, TrafficMetric.entries.associateWith { TrafficChartPresentation.series(a, it) })
}
@Composable private fun MonitorPreview(metric: TrafficMetric = TrafficMetric.DOWNLOAD, both: Boolean = false, dense: Boolean = false, selected: Boolean = false) = ProductPreview {
    val data = remember(dense) { previewTraffic(dense) }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(12.dp)) {
        item { TrafficChart(data, metric, initialSelectedTime = if (selected) data.analysis.start + 1000 else null, onEvent = {}) }
        if (both) item { TrafficChart(data, TrafficMetric.UPLOAD, onEvent = {}) }
    }
}
@Preview(name = "Monitor download / gaps", locale = "pt-rBR", heightDp = 900) @Composable private fun MonitorDown() = MonitorPreview()
@Preview(name = "Monitor upload", locale = "en", heightDp = 800) @Composable private fun MonitorUp() = MonitorPreview(TrafficMetric.UPLOAD)
@Preview(name = "Monitor both", heightDp = 1200) @Composable private fun MonitorBoth() = MonitorPreview(both = true)
@Preview(name = "Monitor selected original sample", locale = "pt-rBR", heightDp = 1000) @Composable private fun MonitorSelected() = MonitorPreview(selected = true)
@Preview(name = "Monitor dense", heightDp = 800) @Composable private fun MonitorDense() = MonitorPreview(dense = true)

@Preview(name = "Field 75%", locale = "pt-rBR") @Composable private fun Field75() = ProductPreview { AntennaFieldOfViewContent(75f, stringResource(R.string.rich_view_critical), stringResource(R.string.rich_view_move)) }
@Preview(name = "Active obstruction / valid zero") @Composable private fun FieldActiveZero() = ProductPreview { AntennaFieldOfViewContent(0f, stringResource(R.string.rich_view_active), stringResource(R.string.rich_view_block), true) }
@Preview(name = "Field synthetic dark Monet surface", locale = "pt-rBR") @Composable private fun FieldMonet() {
    ProductPreview {
        // Deliberately reproduces a light primaryContainer on a dark surface.
        MaterialTheme(colorScheme = darkColorScheme(surfaceContainerLow = Color(0xff16131d), onSurface = Color(0xffe8e0ed),
            onSurfaceVariant = Color(0xffcec3d4), primaryContainer = Color(0xffb8a1d8), onPrimaryContainer = Color(0xff25143b))) {
            AntennaFieldOfViewContent(0f, stringResource(R.string.rich_view_clear), stringResource(R.string.rich_view_good))
        }
    }
}
