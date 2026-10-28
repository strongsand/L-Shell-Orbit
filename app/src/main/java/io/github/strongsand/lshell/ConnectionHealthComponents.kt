@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.github.strongsand.lshell

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

@Composable internal fun HealthHero(ui: HealthReportUi) {
    val r = ui.report; val t = ui.total
    Card(shape = DashDesign.section, colors = CardDefaults.cardColors(containerColor = amoledCardColor(MaterialTheme.colorScheme.primaryContainer)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(OrbitSymbols.HealthAndSafety, null)
                Text(stringResource(R.string.health_connection), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            }
            Text(healthPeriodLabel(r.period), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(healthGradeLabel(r.grade)), style = MaterialTheme.typography.headlineMedium)
            ui.stability?.let { Text(stringResource(R.string.health_ui_stability_trend, stringResource(healthTrendLabel(it))), style = MaterialTheme.typography.titleMedium) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HealthBadge(stringResource(if (r.closed) R.string.health_ui_closed else R.string.health_ui_ongoing))
                HealthBadge(stringResource(R.string.health_ui_coverage_chip, healthDecimal(t.coverage, 0)))
                HealthBadge(stringResource(R.string.health_ui_confidence_chip, stringResource(healthConfidenceLabel(r.confidence))))
            }
            if (r.grade == HealthGrade.INSUFFICIENT)
                Text(stringResource(R.string.health_ui_insufficient_note), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.health_ui_days_observed, r.days.count { it.observedMs > 0 }, r.period.days().size), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.health_updated, Instant.ofEpochMilli(r.generatedAt).atZone(ZoneId.of(r.period.zone))
                    .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
@Composable internal fun HealthOverview(ui: HealthReportUi) {
    val t = ui.total
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.health_ui_overview), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        val tiles: List<@Composable () -> Unit> = listOf(
            { HealthMetric(OrbitSymbols.CheckCircle, R.string.health_availability, healthValue(t.availability, "%", digits = 1), stringResource(R.string.health_ui_observed_only)) },
            { HealthMetric(OrbitSymbols.Timeline, R.string.health_latency, healthValue(t.metric("latency").percentile(.5), "ms", true), stringResource(R.string.reports_median)) },
            { HealthMetric(OrbitSymbols.Wifi, R.string.health_loss, healthValue(t.metric("loss").mean, "%"), stringResource(R.string.reports_mean)) },
            { HealthMetric(OrbitSymbols.Speed, R.string.health_ui_speed, "",
                stringResource(if (ui.scheduledDownload == null && ui.scheduledUpload == null) R.string.reports_few_tests else R.string.reports_scheduled_unit)) {
                    if (ui.scheduledDownload == null && ui.scheduledUpload == null) Text(stringResource(R.string.health_missing), style = MaterialTheme.typography.titleMedium)
                    else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DirectionalMetric(true, ui.scheduledDownload?.let { healthDecimal(it, 0) } ?: stringResource(R.string.health_missing), style = MaterialTheme.typography.titleMedium)
                        DirectionalMetric(false, ui.scheduledUpload?.let { healthDecimal(it, 0) } ?: stringResource(R.string.health_missing), style = MaterialTheme.typography.titleMedium)
                    }
                } }
        )
        ReportSummaryGrid(tiles)
    }
}
@Composable private fun HealthMetric(icon: ImageVector?, label: Int, display: String, note: String? = null, valueContent: (@Composable () -> Unit)? = null) {
    Surface(shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            }
            if (valueContent != null) valueContent() else Text(display, style = MaterialTheme.typography.titleMedium)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable internal fun HealthComparisonCard(ui: HealthReportUi) {
    HealthCard(if (ui.changes.isEmpty()) R.string.health_ui_no_comparison else R.string.health_comparison) {
        if (ui.primaryChanges.isEmpty()) Text(stringResource(if (ui.changes.isEmpty()) R.string.health_ui_no_comparison_note else R.string.reports_extra_changes), style = MaterialTheme.typography.bodyMedium)
        else ui.primaryChanges.forEach { HealthChangeRow(it) }
    }
}
@Composable internal fun HealthChangeRow(change: HealthChange) {
    val delta = change.delta ?: return
    val direction = stringResource(when { delta > 0 -> R.string.health_ui_increased; delta < 0 -> R.string.health_ui_decreased; else -> R.string.health_ui_unchanged })
    val unit = when (change.metric) { "download", "upload", "offline" -> "%"; "latency" -> "ms"; "loss", "availability" -> stringResource(R.string.health_pp); "power" -> "W"; else -> "" }
    val trend = if (change.metric == "power") stringResource(R.string.health_ui_neutral_change) else stringResource(healthTrendLabel(change.trend))
    val spokenUnit = when (change.metric) {
        "download", "upload", "offline" -> stringResource(R.string.reports_unit_percent)
        "latency" -> stringResource(R.string.reports_unit_ms)
        "loss", "availability" -> stringResource(R.string.reports_unit_pp)
        "power" -> stringResource(R.string.reports_unit_watts)
        else -> ""
    }
    val spoken = stringResource(R.string.reports_change_spoken, stringResource(healthMetricLabel(change.metric)), direction, healthDecimal(abs(delta)), spokenUnit, trend)
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(healthMetricLabel(change.metric)), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.health_ui_change_value, direction, healthDecimal(abs(delta)), unit, trend), style = MaterialTheme.typography.titleMedium)
    }
}
@Composable internal fun HealthInsights(ui: HealthReportUi) {
    HealthCard(R.string.health_insights) {
        ui.insights.forEach { key -> when (key) {
            "smart_bias" -> Text(stringResource(R.string.health_smart_bias), style = MaterialTheme.typography.bodyMedium)
            "latency_streak" -> Text(stringResource(R.string.health_latency_streak))
            else -> ui.changes.firstOrNull { it.metric == key }?.let { HealthChangeRow(it) }
        } }
        if (ui.insights.size < 5) ui.bestLatencyWindow?.let { Text(stringResource(R.string.health_best_latency, it.window * 6, (it.window + 1) * 6)) }
    }
}
@Composable internal fun HealthDisclosure(title: Int, key: String, icon: ImageVector? = null,
    summary: @Composable ColumnScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    ReportDisclosure(stringResource(title), "$key:$title", icon, summary, content = content)
}
@Composable internal fun HealthCard(title: Int, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = DashDesign.section, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth().animateContentSize(if (LocalOrbitMotion.current) spring(dampingRatio = .82f, stiffness = 380f) else snap())) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}
@Composable internal fun HealthBadge(text: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}
@Composable internal fun healthValue(number: Double?, unit: String, approximate: Boolean = false, digits: Int = 1): String =
    if (number != null && number.isFinite()) stringResource(if (approximate) R.string.health_approx_value else R.string.health_value, healthDecimal(number, digits), unit)
    else stringResource(R.string.health_missing)
@Composable internal fun HealthValue(label: Int, number: Double?, unit: String, approximate: Boolean = false, digits: Int = 1) {
    Text(stringResource(R.string.health_ui_labeled_value, stringResource(label), healthValue(number, unit, approximate, digits)), style = MaterialTheme.typography.bodyLarge)
}
internal fun healthPeriodLabel(period: HealthPeriod) = HealthReportUiPolicy.periodLabel(period, Locale.getDefault())
internal fun healthDecimal(number: Double, digits: Int = 1) = String.format(Locale.getDefault(), "%.${digits}f", number)
@Composable internal fun healthDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return when { seconds >= 3600 -> stringResource(R.string.health_ui_duration_h, seconds / 3600, seconds / 60 % 60)
        seconds >= 60 -> stringResource(R.string.health_ui_duration_m, seconds / 60, seconds % 60)
        else -> stringResource(R.string.health_ui_duration_s, seconds) }
}
internal fun healthOriginLabel(origin: TestOrigin) = when (origin) {
    TestOrigin.MANUAL -> R.string.health_manual; TestOrigin.EXTREME -> R.string.health_extreme
    TestOrigin.SCHEDULED -> R.string.health_scheduled; TestOrigin.SMART -> R.string.health_smart; TestOrigin.UNKNOWN -> R.string.health_origin_unknown
}
internal fun healthGradeLabel(grade: HealthGrade) = when (grade) {
    HealthGrade.GOOD -> R.string.health_good; HealthGrade.FAIR -> R.string.health_fair
    HealthGrade.POOR -> R.string.health_poor; HealthGrade.INSUFFICIENT -> R.string.health_insufficient
}
internal fun healthTrendLabel(trend: HealthTrend) = when (trend) {
    HealthTrend.IMPROVING -> R.string.health_improving; HealthTrend.STABLE -> R.string.health_stable
    HealthTrend.WORSENING -> R.string.health_worsening; HealthTrend.INSUFFICIENT -> R.string.health_insufficient
}
internal fun healthConfidenceLabel(confidence: HealthConfidence) = when (confidence) {
    HealthConfidence.LOW -> R.string.health_low; HealthConfidence.MEDIUM -> R.string.health_medium; HealthConfidence.HIGH -> R.string.health_high
}
internal fun healthMetricLabel(key: String) = when (key) {
    "download" -> R.string.health_download; "upload" -> R.string.health_upload; "latency" -> R.string.health_latency
    "loss" -> R.string.health_loss; "availability" -> R.string.health_availability; "outages" -> R.string.health_outages
    "offline" -> R.string.health_offline; "power" -> R.string.health_ui_power; else -> R.string.health_stability
}
internal fun healthEventLabel(key: String) = when (key) {
    "DOWNLINK" -> R.string.health_event_downlink; "LOSS" -> R.string.health_loss
    "OBSTRUCTION" -> R.string.health_event_obstruction; "UNREACHABLE" -> R.string.health_event_unreachable
    "RECONNECT" -> R.string.health_event_reconnect; "STATE" -> R.string.health_event_state; else -> R.string.health_event_alert
}
