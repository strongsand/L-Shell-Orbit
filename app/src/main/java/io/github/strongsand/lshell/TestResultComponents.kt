package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Shared result surfaces only. No execution state, live sampling or new statistics. */
@Composable
internal fun TestResultBanner(label: String, title: String, detail: String?, positive: Boolean,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    foreground: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    icon: ImageVector = if (positive) OrbitSymbols.CheckCircle else OrbitSymbols.Info) {
    val background = if (positive) container else MaterialTheme.colorScheme.surfaceContainerLow
    val ink = if (positive) foreground else MaterialTheme.colorScheme.onSurface
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero,
        color = amoledCardColor(background), contentColor = amoledCardContent(ink)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, Modifier.size(28.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold)
            if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun TestResultRates(metrics: TestMetrics, averages: Boolean = false) {
    val palette = MaterialTheme.colorScheme
    TestResultPair(
        first = { modifier -> TestResultRate(stringResource(if (averages) R.string.test_average_download else R.string.traffic_download),
            metrics.download, OrbitSymbols.Download, palette.primaryContainer, palette.onPrimaryContainer, false, modifier) },
        second = { modifier -> TestResultRate(stringResource(if (averages) R.string.test_average_upload else R.string.traffic_upload),
            metrics.upload, OrbitSymbols.Upload, palette.secondaryContainer, palette.onSecondaryContainer, true, modifier) }
    )
}

@Composable
private fun TestResultRate(label: String, value: Float?, icon: ImageVector, container: Color,
    foreground: Color, upload: Boolean, modifier: Modifier) {
    val number = ConnectionTestPresentation.valid(value)?.let { String.format(Locale.getDefault(), "%.1f", it) }
    val spoken = number?.let { stringResource(R.string.test_rate_accessible, it) }
        ?: stringResource(R.string.test_metric_unavailable)
    Surface(modifier.clearAndSetSemantics { contentDescription = "$label: $spoken" },
        shape = if (upload) DashDesign.upload else DashDesign.download,
        color = amoledCardColor(container), contentColor = amoledCardContent(foreground)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(22.dp))
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(number ?: stringResource(R.string.test_metric_dash),
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.test_rate_unit), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
internal fun TestResultFacts(metrics: TestMetrics, duration: String?, averages: Boolean = false,
    latencyLabel: Int = R.string.test_latency) {
    val latency = ConnectionTestPresentation.valid(metrics.latency)?.let {
        stringResource(R.string.test_latency_value, String.format(Locale.getDefault(), "%.1f", it))
    } ?: stringResource(R.string.test_metric_unavailable)
    TestResultPair(
        first = { modifier -> TestResultFact(stringResource(if (averages) R.string.test_average_latency else latencyLabel),
            latency, OrbitSymbols.Speed, modifier) },
        second = { modifier -> TestResultFact(stringResource(R.string.test_total_duration),
            duration ?: stringResource(R.string.test_metric_unavailable), OrbitSymbols.Schedule, modifier) }
    )
}

@Composable
private fun TestResultFact(label: String, value: String, icon: ImageVector, modifier: Modifier) {
    Surface(modifier, shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        }
    }
}

/** Width divided by font scale avoids cramped columns at accessibility sizes. No fixed tile height. */
@Composable
private fun TestResultPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth / LocalDensity.current.fontScale < 320.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}
