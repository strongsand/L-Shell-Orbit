@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable internal fun HealthSpeed(ui: HealthReportUi) {
    HealthDisclosure(R.string.health_ui_speed, ui.report.period.key, OrbitSymbols.Speed, summary = {
        val groups = ui.tests.filter { it.origin in setOf(TestOrigin.SCHEDULED, TestOrigin.EXTREME) }
        if (groups.isEmpty()) Text(stringResource(R.string.reports_other_tests))
        groups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(healthOriginLabel(group.origin)), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.reports_completed_tests, group.succeeded), style = MaterialTheme.typography.labelMedium)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DirectionalMetric(true, healthValue(group.download.percentile(.5), "Mb/s", true), style = MaterialTheme.typography.titleMedium)
                    DirectionalMetric(false, healthValue(group.upload.percentile(.5), "Mb/s", true), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }) {
        ui.tests.filter { it.origin != TestOrigin.UNKNOWN }.forEach { group ->
            Text(stringResource(healthOriginLabel(group.origin)), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            if (group.origin == TestOrigin.SMART) Text(stringResource(R.string.health_ui_smart_potential), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.health_test_counts, group.count, group.succeeded, group.failed, group.interrupted), style = MaterialTheme.typography.bodySmall)
            HealthValue(R.string.health_download_median, group.download.percentile(.5), "Mb/s", true)
            HealthValue(R.string.health_upload_median, group.upload.percentile(.5), "Mb/s", true)
        }
        if (ui.tests.any { it.origin == TestOrigin.SCHEDULED }) {
            HealthDailyChart(ui.report, R.string.health_download) { day -> day.tests.filter { it.origin == TestOrigin.SCHEDULED }.fold(HealthDistribution()) { a, g -> a.merge(g.download) }.percentile(.5) }
            HealthDailyChart(ui.report, R.string.health_upload) { day -> day.tests.filter { it.origin == TestOrigin.SCHEDULED }.fold(HealthDistribution()) { a, g -> a.merge(g.upload) }.percentile(.5) }
        }
    }
}
@Composable internal fun HealthQuality(ui: HealthReportUi) {
    val t = ui.total
    HealthDisclosure(R.string.health_ui_quality, ui.report.period.key, OrbitSymbols.Timeline, summary = {
        ReportSummaryGrid(listOf(
            { ReportSummaryMetric(stringResource(R.string.health_latency), healthValue(t.metric("latency").percentile(.5), "ms", true)) },
            { ReportSummaryMetric(stringResource(R.string.health_loss), healthValue(t.metric("loss").mean, "%")) },
            { ReportSummaryMetric(stringResource(R.string.editorial_outages), if (t.stateMs > 0) t.outages.toString() else stringResource(R.string.health_missing)) },
            { ReportSummaryMetric(stringResource(R.string.editorial_offline_time), if (t.stateMs > 0) healthDuration(t.offlineMs) else stringResource(R.string.health_missing)) }
        ), minimumWidthDp = 280f)
    }) {
        HealthValue(R.string.health_availability, t.availability, "%", digits = 2)
        Text(stringResource(R.string.health_ui_state_coverage, healthDecimal(if (t.duration > 0) t.stateMs * 100.0 / t.duration else 0.0)), style = MaterialTheme.typography.bodySmall)
        HealthValue(R.string.health_ui_typical_latency, t.metric("latency").percentile(.5), "ms", true)
        HealthValue(R.string.health_p90, t.metric("latency").percentile(.9, 10), "ms", true)
        HealthValue(R.string.health_p95, t.metric("latency").percentile(.95, 20), "ms", true)
        HealthValue(R.string.health_ui_max_latency, t.metric("latency").maximum, "ms")
        HealthValue(R.string.health_loss, t.metric("loss").mean, "%")
        if (t.lossHighMs > 0) Text(stringResource(R.string.health_ui_loss_high, healthDuration(t.lossHighMs)))
        if (t.outages > 0) Text(stringResource(R.string.health_ui_longest_interruption, healthDuration(t.longestOutageMs)))
        if (t.metric("latency").count > 0) HealthDailyChart(ui.report, R.string.health_latency) { it.metric("latency").percentile(.5) }
        if (t.metric("loss").count > 0) HealthDailyChart(ui.report, R.string.health_loss) { it.metric("loss").mean }
        if (t.stateMs > 0) {
            HealthDailyChart(ui.report, R.string.health_availability) { it.availability }
            if (t.outages > 0) HealthDailyChart(ui.report, R.string.health_outages) { if (it.stateMs > 0) it.outages.toDouble() else null }
        }
    }
}
@Composable internal fun HealthEnergy(ui: HealthReportUi) {
    val t = ui.total
    HealthDisclosure(R.string.health_energy, ui.report.period.key, OrbitSymbols.Bolt, summary = {
        ReportSummaryGrid(listOf(
            { ReportSummaryMetric(stringResource(R.string.health_ui_power), healthValue(t.powerMean, "W")) },
            { ReportSummaryMetric(stringResource(R.string.health_ui_energy), healthValue(t.wattHours, "Wh")) }
        ), minimumWidthDp = 280f)
        if (t.powerCoveredMs > 0) Text(stringResource(R.string.health_ui_power_time, healthDuration(t.powerCoveredMs)), style = MaterialTheme.typography.labelMedium)
        if (ui.relevantEvents.isNotEmpty()) Text(stringResource(R.string.reports_relevant_events, ui.relevantEvents.values.sum()), style = MaterialTheme.typography.labelMedium)
    }) {
        if (t.powerCoveredMs > 0) {
            Text(stringResource(R.string.health_ui_power_time, healthDuration(t.powerCoveredMs)))
            HealthDailyChart(ui.report, R.string.health_ui_power) { it.powerMean }
        }
        t.eventCounts.toSortedMap().forEach { (kind, count) -> Text(stringResource(R.string.health_event_count, stringResource(healthEventLabel(kind)), count), style = MaterialTheme.typography.bodySmall) }
        Text(stringResource(R.string.health_ui_unavailable_hardware), style = MaterialTheme.typography.bodySmall)
    }
}
@Composable internal fun HealthConfidenceCard(ui: HealthReportUi) {
    val r = ui.report; val t = ui.total
    HealthDisclosure(R.string.health_confidence, r.period.key, summary = {
        Text(stringResource(healthConfidenceLabel(r.confidence)), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HealthBadge(stringResource(R.string.health_ui_coverage_chip, healthDecimal(t.coverage, 0)))
            HealthBadge(stringResource(R.string.health_ui_qualified_days, r.days.count { it.observedMs >= it.duration * .4 }, r.days.size))
            HealthBadge(stringResource(R.string.health_ui_comparable_count, r.comparableTests))
        }
    }) {
        Text(stringResource(if (HealthReportUiPolicy.lowCoverage(t.coverage)) R.string.health_ui_low_coverage else R.string.health_ui_confidence_basis), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.health_ui_gap, healthDuration(t.longestGapMs)))
        Text(stringResource(R.string.health_confidence_factors, healthDecimal(t.coverage), r.days.count { it.observedMs >= it.duration * .4 }, r.days.size,
            t.metric("latency").count, r.comparableTests, healthDuration(t.longestGapMs)), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.health_previous_quality, r.previousCoverage?.let { "${healthDecimal(it)}%" } ?: stringResource(R.string.health_missing)), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.health_confidence_speed), style = MaterialTheme.typography.bodySmall)
    }
}
@Composable internal fun HealthMethodology(ui: HealthReportUi) {
    val r = ui.report; val t = ui.total
    HealthCard(R.string.health_method) {
        Text(stringResource(R.string.health_ui_interpretation), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.traffic_local), color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.health_ui_beacon), color = MaterialTheme.colorScheme.tertiary)
            Text(stringResource(R.string.traffic_mixed), color = MaterialTheme.colorScheme.secondary)
        }
        Text(stringResource(R.string.health_source, t.localValues, t.beaconValues), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.health_ui_timezone, r.period.zone), style = MaterialTheme.typography.bodySmall)
        ui.changes.forEach { HealthChangeRow(it) }
        Text(stringResource(R.string.health_comparable_tests, r.comparableTests), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.health_method_text), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.health_method_thresholds), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.health_method_versions, r.algorithmVersion, r.schemaVersion), style = MaterialTheme.typography.bodySmall)
        if (t.resolutionPreserved) Text(stringResource(R.string.health_preserved))
        t.stats.forEach { (metric, d) ->
            Text(stringResource(healthMetricLabel(metric)), style = MaterialTheme.typography.labelLarge)
            if (d.count > 0) Text(stringResource(R.string.health_stats, d.count, healthDecimal(d.minimum!!), healthDecimal(d.maximum!!), healthDecimal(d.mean!!),
                when (metric) { "latency" -> "ms"; "loss" -> "%"; "power" -> "W"; else -> "Mb/s" }), style = MaterialTheme.typography.bodySmall)
        }
        ui.tests.firstOrNull { it.origin == TestOrigin.UNKNOWN }?.let { group ->
            Text(stringResource(R.string.health_origin_unknown), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.health_test_counts, group.count, group.succeeded, group.failed, group.interrupted))
            HealthValue(R.string.health_download_median, group.download.percentile(.5), "Mb/s", true)
            HealthValue(R.string.health_upload_median, group.upload.percentile(.5), "Mb/s", true)
        }
        if (t.hours.isNotEmpty()) {
            Text(stringResource(R.string.health_hours), style = MaterialTheme.typography.titleSmall)
            t.hours.filter { it.coveredMs > 0 }.sortedBy { it.window }.forEach { h -> Text(stringResource(R.string.health_hour_values, h.window * 6,
                (h.window + 1) * 6, h.days.size, h.latency.percentile(.5)?.let { healthDecimal(it) } ?: stringResource(R.string.health_missing),
                h.loss.mean?.let { healthDecimal(it) } ?: stringResource(R.string.health_missing)), style = MaterialTheme.typography.bodySmall) }
        }
        if (t.reconnects > 0) Text(stringResource(R.string.health_reconnects, t.reconnects))
        if (t.metric("download").count > 0 || t.metric("upload").count > 0) {
            Text(stringResource(R.string.health_utilization_note), style = MaterialTheme.typography.bodySmall)
            HealthDailyChart(r, R.string.health_download) { it.metric("download").mean }
            HealthDailyChart(r, R.string.health_upload) { it.metric("upload").mean }
        }
    }
}
@Composable internal fun HealthDailyChart(report: HealthReport, title: Int, value: (HealthDay) -> Double?) {
    val coverage = remember(report) { report.total.coverage }
    val buckets = remember(report, title) { report.days.map { d ->
        val n = value(d)?.takeIf { it.isFinite() }
        ReportChartBucket(d.start, d.end, n?.toFloat(), if (n == null) 0 else 1, if (d.localValues > 0) 1 else 0, if (d.beaconValues > 0) 1 else 0)
    } }
    val values = remember(buckets) { buckets.map { it.value?.toDouble() } }
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
    if (HealthReportUiPolicy.showChart(values)) {
        ReportChart(buckets, stringResource(title), showAnnotations = false,
            unit = when (title) { R.string.health_download, R.string.health_upload -> "Mb/s"; R.string.health_latency -> "ms";
                R.string.health_loss, R.string.health_availability -> "%"; R.string.health_ui_power -> "W"; else -> "" },
            zone = report.period.zone)
        if (HealthReportUiPolicy.lowCoverage(coverage)) Text(stringResource(R.string.health_ui_chart_partial), style = MaterialTheme.typography.labelSmall)
    } else Text(stringResource(R.string.health_ui_chart_few), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.health_ui_chart_days, HealthReportUiPolicy.chartPoints(values), report.period.days().size), style = MaterialTheme.typography.labelSmall)
    var expanded by rememberSaveable(report.period.key, title) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.health_hide else R.string.health_ui_chart_values)) }
    if (expanded) report.days.forEachIndexed { i, d ->
        Text(stringResource(R.string.health_ui_labeled_value, Instant.ofEpochMilli(d.start).atZone(ZoneId.of(report.period.zone)).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)), buckets[i].value?.toDouble()?.let { healthDecimal(it) } ?: stringResource(R.string.health_missing)), style = MaterialTheme.typography.bodySmall)
    }
}
