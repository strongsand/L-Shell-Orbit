package io.github.strongsand.lshell

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun DailyReportsScreen(modifier: Modifier = Modifier, initialEnd: Long? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    var reports by remember { mutableStateOf<List<DailyReport>>(emptyList()) }
    var selectedEnd by rememberSaveable(initialEnd) { mutableStateOf(initialEnd) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedEnd, retry) {
        loading = true; failed = false
        val result = readUiList { withContext(Dispatchers.IO) {
            val store = DailyReportStore(context)
            selectedEnd?.let { end ->
                store.refreshIfStale(end) { start, reportEnd ->
                    DailyReportGenerator.generate(context, start, reportEnd)
                }
            }
            store.all()
        } }
        result.fold(onSuccess = { reports = it }, onFailure = { failed = true })
        loading = false
    }
    val motion = LocalOrbitMotion.current
    val selected = reports.firstOrNull { it.end == selectedEnd }
    androidx.activity.compose.BackHandler(selectedEnd != null) { selectedEnd = null }
    if (failed || !loading && selectedEnd != null && selected == null) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = hapticClick { if (selectedEnd != null) selectedEnd = null else onBack() }) {
                    Icon(OrbitSymbols.ArrowBack, stringResource(R.string.health_back))
                }
                Text(stringResource(R.string.health_unavailable), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            }
            Text(stringResource(R.string.health_load_error))
            TextButton(onClick = hapticClick { loading = true; failed = false; retry++ }) { Text(stringResource(R.string.health_retry)) }
        }
        return
    }
    androidx.compose.animation.AnimatedContent(selected, contentKey = { it?.end },
        transitionSpec = {
            if (motion) fadeIn(tween(160)) togetherWith fadeOut(tween(80))
            else (EnterTransition.None togetherWith ExitTransition.None).using(SizeTransform { _, _ -> snap() })
        }, label = "report detail") { selected ->
        androidx.compose.runtime.CompositionLocalProvider(LocalOrbitVisibility provides this) {
    if (loading && selectedEnd != null && selected == null) {
        ReportEmpty(androidx.compose.ui.res.stringResource(R.string.editorial_report_updating), loading = true)
    } else if (selectedEnd != null && selected != null) {
        DailyReportDetail(selected, reports.getOrNull(reports.indexOf(selected) + 1), modifier) {
            selectedEnd = null
        }
    } else {
        LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = hapticClick(action = onBack)) {
                        Icon(OrbitSymbols.ArrowBack, contentDescription = androidx.compose.ui.res.stringResource(R.string.editorial_back))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(androidx.compose.ui.res.stringResource(R.string.function_reports), style = MaterialTheme.typography.headlineLarge)
                        Text(androidx.compose.ui.res.stringResource(R.string.editorial_report_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (loading) item { ReportEmpty(androidx.compose.ui.res.stringResource(R.string.editorial_report_loading), loading = true) }
            else if (reports.isEmpty()) item {
                ReportEmpty(androidx.compose.ui.res.stringResource(R.string.editorial_report_first))
            }
            items(reports, key = { it.end }) { report ->
                val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Surface(onClick = hapticClick { selectedEnd = report.end }, interactionSource = interaction,
                    modifier = Modifier.fillMaxWidth().orbitSharedBounds("report:${report.end}").pressMotion(interaction), shape = DashDesign.section,
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                            Surface(shape = DashDesign.cookie,
                                color = if (report.interruptions.isEmpty()) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.tertiaryContainer) {
                                Icon(if (report.interruptions.isEmpty()) OrbitSymbols.CheckCircle else OrbitSymbols.CloudOff,
                                    null, Modifier.padding(13.dp))
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(day(report.end), style = MaterialTheme.typography.titleLarge)
                            Text(telemetryLabel(report.summary), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(report.availabilityPercent?.let { androidx.compose.ui.res.stringResource(R.string.editorial_availability, number(it, 2)) }
                                ?: androidx.compose.ui.res.stringResource(R.string.editorial_availability_none), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
        }
    }

}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DailyReportDetail(report: DailyReport, previous: DailyReport?, modifier: Modifier, onBack: () -> Unit) {
    val outages = remember(report) { DailyInterruptionUi.from(report) }
    var outagesExpanded by rememberSaveable(report.start, report.end) { mutableStateOf(false) }
    var selectedOutage by rememberSaveable(report.start, report.end) { mutableStateOf<Int?>(null) }
    val shownExpanded = outagesExpanded

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = hapticClick(action = onBack), modifier = Modifier.padding(top = 6.dp)) {
                    Icon(OrbitSymbols.ArrowBack, contentDescription = stringResource(R.string.editorial_back_reports))
                }
                Spacer(Modifier.weight(1f))
                ReportExportAction(report)
            }
            Surface(Modifier.fillMaxWidth().orbitSharedBounds("report:${report.end}"), shape = DashDesign.hero,
                color = amoledCardColor(MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(OrbitSymbols.Assessment, null, Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(androidx.compose.ui.res.stringResource(R.string.editorial_report_title), style = MaterialTheme.typography.headlineMedium)
                    Text(telemetryLabel(report.summary), style = MaterialTheme.typography.titleMedium)
                    Text("${dateTime(report.start)} → ${dateTime(report.end)}",
                        style = MaterialTheme.typography.bodySmall)
                    if (report.createdAt - report.end > 15 * 60_000L)
                        Text(stringResource(R.string.reports_generated_late, healthDuration(report.createdAt - report.end)),
                            style = MaterialTheme.typography.labelMedium)
                    report.availabilityPercent?.let {
                        Text("${number(it, 2)}%", style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold)
                        Text(androidx.compose.ui.res.stringResource(R.string.editorial_availability_note))
                    }
                }
            }
        }
        item {
            ReportSection(androidx.compose.ui.res.stringResource(R.string.editorial_report_summary)) {
                MetricRow(androidx.compose.ui.res.stringResource(R.string.editorial_monitored_time), duration(report.monitoredMs), androidx.compose.ui.res.stringResource(R.string.report_access_available), duration(report.onlineMs))
                MetricRow(androidx.compose.ui.res.stringResource(R.string.editorial_offline_time), duration(report.offlineMs), androidx.compose.ui.res.stringResource(R.string.editorial_outages), report.interruptions.size.toString())
                MetricRow(stringResource(R.string.editorial_alerts), report.alerts.size.toString(), stringResource(R.string.report_samples), report.sampleCount.toString())
                outages.longest?.let { longest ->
                    Text(stringResource(R.string.reports_longest_at, healthDuration(longest.durationMs), time(longest.start)), style = MaterialTheme.typography.titleMedium)
                }
                if (report.monitoredMs < report.end - report.start)
                    Text(androidx.compose.ui.res.stringResource(R.string.editorial_report_gap_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (previous != null) item { ComparisonCard(report, previous) }
        item("outages") {
            if (outages.count == 0) ReportSection(stringResource(R.string.reports_access_interruptions)) {
                Text(stringResource(R.string.reports_no_interruptions))
            } else ReportDisclosure(stringResource(R.string.reports_access_interruptions), "daily:${report.start}:${report.end}:outages",
                icon = OrbitSymbols.CloudOff, expanded = shownExpanded, onExpandedChange = { outagesExpanded = it }, summary = {
                    ReportSummaryGrid(listOf(
                        { ReportSummaryMetric(stringResource(R.string.editorial_outages), outages.count.toString()) },
                        { ReportSummaryMetric(stringResource(R.string.editorial_offline_time), healthDuration(outages.offlineMs)) },
                        { ReportSummaryMetric(stringResource(R.string.density_longest), outages.longest?.let { healthDuration(it.durationMs) } ?: "—") },
                        { ReportSummaryMetric(stringResource(R.string.density_latest), outages.rows.lastOrNull()?.let { time(it.start) } ?: "—") }
                    ), minimumWidthDp = 280f)
                })
        }
        if (shownExpanded) itemsIndexed(outages.rows, key = { index, row -> "outage:${row.start}:${row.end}:$index" }) { index, row ->
            val clock = java.time.Instant.ofEpochMilli(row.start).atZone(java.time.ZoneId.systemDefault())
            val spoken = stringResource(R.string.reports_outage_spoken, clock.hour, clock.minute, reportSpokenDuration(row.durationMs))
            Surface(onClick = hapticClick { selectedOutage = index }, shape = DashDesign.pill,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = spoken }) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(OrbitSymbols.Schedule, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(time(row.start), style = MaterialTheme.typography.labelLarge)
                        Text(healthDuration(row.durationMs), style = MaterialTheme.typography.titleSmall)
                    }
                    Icon(OrbitSymbols.KeyboardArrowRight, null)
                }
            }
        }
        item {
            ReportSection(androidx.compose.ui.res.stringResource(R.string.report_observed_traffic)) {
                Text(androidx.compose.ui.res.stringResource(R.string.report_traffic_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                MetricStats(androidx.compose.ui.res.stringResource(R.string.report_active_download), report.trafficDownload, "Mbps")
                MetricStats(androidx.compose.ui.res.stringResource(R.string.report_active_upload), report.trafficUpload, "Mbps")
                ReportChart(report.trafficChart, androidx.compose.ui.res.stringResource(R.string.report_download_traffic), unit = "Mbps")
            }
        }
        item {
            ReportSection(androidx.compose.ui.res.stringResource(R.string.traffic_latency)) {
                MetricStats(androidx.compose.ui.res.stringResource(R.string.editorial_pop_latency), report.latency, "ms", showP95 = true)
                Text(androidx.compose.ui.res.stringResource(R.string.report_high_latency_time, duration(report.latencyAbove100Ms)))
                ReportChart(report.latencyChart, androidx.compose.ui.res.stringResource(R.string.traffic_latency), unit = "ms")
            }
        }
        item {
            ReportSection(androidx.compose.ui.res.stringResource(R.string.traffic_loss)) {
                MetricStats(androidx.compose.ui.res.stringResource(R.string.traffic_loss), report.packetLoss, "%")
                MetricRow(androidx.compose.ui.res.stringResource(R.string.report_loss_periods), report.packetLossPeriods.toString(),
                    androidx.compose.ui.res.stringResource(R.string.report_total_time), duration(report.packetLossDurationMs))
                ReportChart(report.lossChart, androidx.compose.ui.res.stringResource(R.string.traffic_loss), unit = "%")
                EventTimelineGraph(report.timeline.filter { it.title == "Perda elevada de pacotes" },
                    report.start, report.end, androidx.compose.ui.res.stringResource(R.string.report_loss_events))
            }
        }
        item {
            ReportSection(androidx.compose.ui.res.stringResource(R.string.report_fov)) {
                MetricStats(androidx.compose.ui.res.stringResource(R.string.editorial_obstruction), report.obstruction, "%")
                if (report.obstruction?.maximum?.let { it < 1f } == true)
                    Text(androidx.compose.ui.res.stringResource(R.string.report_clear_view))
                ReportChart(report.obstructionChart, androidx.compose.ui.res.stringResource(R.string.editorial_obstruction), unit = "%")
            }
        }
        item {
            ReportDisclosure(stringResource(R.string.report_hardware), "daily:${report.end}:hardware", summary = {
                Text(stringResource(R.string.reports_event_count, report.hardwareEvents.size), style = MaterialTheme.typography.labelLarge)
            }) {
                if (report.hardwareEvents.isEmpty()) Text(androidx.compose.ui.res.stringResource(R.string.report_no_hardware_events))
                report.hardwareEvents.forEach { Text("${time(it.timestamp)} — ${telemetryLabel(it.title)} ${telemetryLabel(it.detail)}") }
                Text(androidx.compose.ui.res.stringResource(R.string.report_temperature_power_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (report.alerts.isNotEmpty()) item {
            ReportDisclosure(stringResource(R.string.report_alerts), "daily:${report.end}:alerts", summary = {
                Text(stringResource(R.string.reports_event_count, report.alerts.size), style = MaterialTheme.typography.labelLarge)
            }) {
                if (report.alerts.isEmpty()) Text(androidx.compose.ui.res.stringResource(R.string.report_no_alerts))
                else {
                    EventTimelineGraph(report.alerts, report.start, report.end, androidx.compose.ui.res.stringResource(R.string.report_alert_distribution))
                    EventBreakdown(report.alerts)
                }
            }
        }
        if (report.timeline.isNotEmpty()) item {
            ReportDisclosure(stringResource(R.string.function_timeline), "daily:${report.end}:timeline", summary = {
                Text(stringResource(R.string.reports_event_count, report.timeline.size), style = MaterialTheme.typography.labelLarge)
            }) {
                if (report.timeline.isEmpty()) Text(androidx.compose.ui.res.stringResource(R.string.report_no_events))
                else {
                    EventTimelineGraph(report.timeline, report.start, report.end, androidx.compose.ui.res.stringResource(R.string.report_events_period))
                    Text(androidx.compose.ui.res.stringResource(R.string.report_highlights), style = MaterialTheme.typography.titleMedium)
                    report.timeline.sortedWith(compareByDescending<ReportMoment> {
                        eventPriority(it.title)
                    }.thenByDescending { it.timestamp }).take(3).forEach { moment ->
                        MetricRow(time(moment.timestamp), telemetryLabel(moment.title), androidx.compose.ui.res.stringResource(R.string.editorial_details),
                            telemetryLabel(moment.detail).ifBlank { androidx.compose.ui.res.stringResource(R.string.report_recorded) })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    selectedOutage?.let { index -> outages.rows.getOrNull(index)?.let { row ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { selectedOutage = null },
            title = { Text(stringResource(R.string.reports_access_interruptions)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.health_ui_labeled_value, stringResource(R.string.reports_start), dateTime(row.start)))
                    Text(stringResource(R.string.health_ui_labeled_value, stringResource(if (row.resolved) R.string.reports_end else R.string.reports_observed_until), dateTime(row.end)))
                    Text(healthDuration(row.durationMs))
                    Text(telemetryLabel(row.reason))
                    Text(stringResource(if (row.resolved) R.string.editorial_restored else R.string.report_no_recovery))
                }
            }, confirmButton = { androidx.compose.material3.TextButton(onClick = hapticClick { selectedOutage = null }) { Text(stringResource(R.string.traffic_back)) } })
    } }

}

@Composable
private fun ComparisonCard(current: DailyReport, previous: DailyReport) {
    ReportSection(androidx.compose.ui.res.stringResource(R.string.report_comparison)) {
        ComparisonRow(androidx.compose.ui.res.stringResource(R.string.report_availability), current.availabilityPercent, previous.availabilityPercent, "%")
        ComparisonRow(androidx.compose.ui.res.stringResource(R.string.editorial_mean_latency), current.latency?.average, previous.latency?.average, " ms", lowerIsBetter = true)
        MetricRow(androidx.compose.ui.res.stringResource(R.string.editorial_outages), current.interruptions.size.toString(), androidx.compose.ui.res.stringResource(R.string.report_previous), previous.interruptions.size.toString())
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ComparisonRow(label: String, current: Float?, previous: Float?, unit: String, lowerIsBetter: Boolean = false) {
    if (current == null || previous == null) {
        Text(androidx.compose.ui.res.stringResource(R.string.editorial_compare_none, label), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val delta = current - previous
    val favorable = if (lowerIsBetter) delta <= 0f else delta >= 0f
    val changeDescription = when {
        delta > 0f -> androidx.compose.ui.res.stringResource(R.string.editorial_increased, number(kotlin.math.abs(delta), 1) + unit)
        delta < 0f -> androidx.compose.ui.res.stringResource(R.string.editorial_decreased, number(kotlin.math.abs(delta), 1) + unit)
        else -> androidx.compose.ui.res.stringResource(R.string.editorial_unchanged)
    }
    val tint = if (favorable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    FlowRow(Modifier.clearAndSetSemantics { contentDescription = "$label, ${number(current, 1)}$unit. $changeDescription" },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label  ${number(current, 1)}$unit", color = tint, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(when { delta > 0f -> OrbitSymbols.TrendingUp; delta < 0f -> OrbitSymbols.TrendingDown; else -> OrbitSymbols.DragHandle },
                null, Modifier.size(20.dp), tint = tint)
            Text("${number(kotlin.math.abs(delta), 1)}$unit", color = tint, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ReportSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = DashDesign.section,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun MetricStats(title: String, metric: ReportMetric?, unit: String, showP95: Boolean = false) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (metric == null) Text(androidx.compose.ui.res.stringResource(R.string.report_no_samples), color = MaterialTheme.colorScheme.onSurfaceVariant)
    else {
        MetricRow(androidx.compose.ui.res.stringResource(R.string.traffic_mean), "${number(metric.average)} $unit", androidx.compose.ui.res.stringResource(R.string.traffic_median), "${number(metric.median)} $unit")
        MetricRow(androidx.compose.ui.res.stringResource(R.string.report_minimum), androidx.compose.ui.res.stringResource(R.string.editorial_value_at, "${number(metric.minimum)} $unit", time(metric.minimumAt)),
            androidx.compose.ui.res.stringResource(R.string.report_maximum), androidx.compose.ui.res.stringResource(R.string.editorial_value_at, "${number(metric.maximum)} $unit", time(metric.maximumAt)))
        if (showP95 && metric.count >= 20)
            Text("p95: ${number(metric.p95)} $unit", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun MetricRow(leftLabel: String, leftValue: String, rightLabel: String, rightValue: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth / LocalDensity.current.fontScale < 320.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCell(leftLabel, leftValue, Modifier.fillMaxWidth())
                MetricCell(rightLabel, rightValue, Modifier.fillMaxWidth())
            }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCell(leftLabel, leftValue, Modifier.weight(1f))
            MetricCell(rightLabel, rightValue, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = DashDesign.pill, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun ReportChart(values: List<ReportChartBucket>, description: String, showAnnotations: Boolean = true, unit: String = "", zone: String? = null) {
    val localLine = MaterialTheme.colorScheme.primary
    val beaconLine = MaterialTheme.colorScheme.tertiary
    val mixedLine = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val samples = remember(values) { reportChartSamples(values) }
    val scale = remember(values) { reportChartScale(values.map { it.value }) }
    val sampleCount = remember(samples) { samples.sumOf { it.size } }
    if (samples.isEmpty() || scale == null) {
        Text(androidx.compose.ui.res.stringResource(R.string.editorial_chart_empty), style = MaterialTheme.typography.bodySmall)
        return
    }
    val coverageForVoice = remember(values) { reportChartCoverage(values) }
    if (sampleCount < 3) {
        Text(stringResource(R.string.health_ui_chart_few), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.editorial_report_coverage, description, coverageForVoice.filled, coverageForVoice.total, number(coverageForVoice.percent)), style = MaterialTheme.typography.labelSmall)
        samples.flatten().forEach { sample ->
            Text(stringResource(R.string.health_ui_labeled_value, time(sample.bucket.start), number(sample.bucket.value!!)), style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val chartDescription = androidx.compose.ui.res.stringResource(R.string.editorial_report_coverage, description, coverageForVoice.filled, coverageForVoice.total, number(coverageForVoice.percent))
    val start = values.first().start
    val end = values.last().end
    val series = remember(values) { reportInspectionSeries(values) }
    val index = remember(series) { ChartSampleIndex(series) }
    val byTime = remember(values) { values.associateBy { it.start } }
    var probe by rememberSaveable(description, start, end) { mutableStateOf<Long?>(null) }
    val selected = remember(index, probe) { probe?.let(index::containing) }
    val bucket = selected?.let { byTime[it.time] }
    val feedback = remember(description) { MetricInspectionFeedback() }
    val haptics = rememberDashHaptics()
    val pulse = rememberChartPulse(selected?.time)
    val motion = LocalOrbitMotion.current
    val gesture = remember { ChartDirectManipulation() }
    val pixelDensity = androidx.compose.ui.platform.LocalDensity.current.density
    var plotSize by remember { mutableStateOf(Size(1f, 1f)) }
    val travelTarget = selected?.measurement?.let { m ->
        index.segmentAt(selected.time)?.let { segment ->
            val fraction = if (scale.constant) .5f else ((m.value.toFloat() - scale.minimum) / scale.range).coerceIn(0f, 1f)
            ChartMotionTarget(selected.time, Offset(chartTimeFraction(selected.time, start, end), 1f - fraction), segment)
        }
    }
    val travel = rememberChartTravel(travelTarget, gesture.active, Triple(description, start to end, scale), plotSize)
    val gap = stringResource(R.string.metric_detail_gap)
    val selectionText = if (bucket != null) trafficPeriodDate(bucket.start, bucket.end, zone) + " · " +
        trafficTime(bucket.start, zone, true) + "–" + trafficTime(bucket.end, zone, true) + " · " +
        trafficValue(bucket.value?.toDouble(), unit) + " · " + trafficOrigin(selected!!.measurement!!.origin)
        else probe?.let { trafficTime(it, zone, true) + " · " + gap } ?: chartDescription
    fun inspect(time: Long) {
        probe = time.coerceIn(start, maxOf(start, end - 1))
        if (feedback.sample(index.containing(probe!!)?.time, android.os.SystemClock.uptimeMillis())) haptics.perform(DashFeedback.DRAG_SNAP)
    }
    fun step(forward: Boolean): Boolean {
        val point = index.adjacent(selected?.time ?: probe, forward) ?: return false
        inspect(point.time); return true
    }
    fun close(): Boolean { probe = null; feedback.sample(null, android.os.SystemClock.uptimeMillis()); return true }
    val latestSelect by androidx.compose.runtime.rememberUpdatedState<(Float) -> Unit>({ fraction ->
        inspect(start + ((end - start) * fraction.toDouble()).toLong())
    })
    val inset = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.toPx() }
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section, color = background) {
      Box {
        val drawing = remember(values, samples, scale, localLine, beaconLine, mixedLine, grid, background) {
            Modifier.fillMaxWidth().height(132.dp).clip(DashDesign.section).drawWithCache {
                val margin = 14.dp.toPx().coerceAtMost(size.width / 4f)
                val top = 14.dp.toPx().coerceAtMost(size.height / 4f)
                val bottom = size.height - top
                fun position(sample: ReportChartSample): Offset {
                    val fraction = if (scale.constant) .5f else ((sample.bucket.value!! - scale.minimum) / scale.range).coerceIn(0f, 1f)
                    return Offset(margin + chartTimeFraction(sample.bucket.start, start, end) * (size.width - margin * 2), bottom - fraction * (bottom - top))
                }
                fun tint(origin: ReportBucketOrigin) = when (origin) {
                    ReportBucketOrigin.BEACON -> beaconLine; ReportBucketOrigin.MIXED -> mixedLine; else -> localLine
                }
                val areas = mutableListOf<Pair<Color, Path>>()
                val lines = mutableMapOf<Color, Path>()
                val dots = mutableListOf<Pair<Color, Offset>>()
                samples.forEach { segment ->
                    val points = segment.map(::position)
                    if (points.size == 1) dots += tint(segment.first().bucket.origin) to points.first()
                    else {
                        val color = tint(if (segment.any { it.bucket.origin == ReportBucketOrigin.MIXED }) ReportBucketOrigin.MIXED else segment.first().bucket.origin)
                        areas += color to Path().apply {
                            moveTo(points.first().x, bottom); points.forEach { lineTo(it.x, it.y) }
                            lineTo(points.last().x, bottom); close()
                        }
                        points.zipWithNext().forEachIndexed { i, (from, to) ->
                            val a = segment[i].bucket.origin; val b = segment[i + 1].bucket.origin
                            val edge = if (a == ReportBucketOrigin.MIXED || b == ReportBucketOrigin.MIXED) ReportBucketOrigin.MIXED else b
                            lines.getOrPut(tint(edge)) { Path() }.apply { moveTo(from.x, from.y); lineTo(to.x, to.y) }
                        }
                        if (sampleCount <= 8) points.forEachIndexed { i, point -> dots += tint(segment[i].bucket.origin) to point }
                    }
                }
                onDrawBehind {
                    repeat(3) { i -> val y = top + (bottom - top) * (i + 1) / 4f
                        drawLine(grid.copy(alpha = .32f), Offset(margin, y), Offset(size.width - margin, y), .5.dp.toPx()) }
                    areas.forEach { (color, path) -> drawPath(path, Brush.verticalGradient(0f to color.copy(alpha = .10f), 1f to Color.Transparent, startY = top, endY = bottom)) }
                    lines.forEach { (color, path) -> drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)) }
                    dots.forEach { (color, point) -> drawCircle(color, 2.5.dp.toPx(), point) }
                }
            }
        }
        Box(drawing)
        Canvas(Modifier.fillMaxWidth().height(132.dp).onSizeChanged { plotSize = Size((it.width / pixelDensity - 28f).coerceAtLeast(1f), (it.height / pixelDensity - 28f).coerceAtLeast(1f)) }.clip(DashDesign.section)
            .chartInspectionAccess(chartDescription, selectionText, { if (probe == null) step(true) else true }, ::step, ::close)
            .chartScrubbing(description, inset, gesture) { latestSelect(it) }) {
            val x = inset + chartTimeFraction(selected?.time ?: probe ?: start, start, end) * (size.width - 2 * inset)
            if (probe != null) drawLine(grid.copy(alpha = .7f), Offset(x, inset), Offset(x, size.height - inset), 1.dp.toPx())
            selected?.measurement?.let { m ->
                val fraction = if (scale.constant) .5f else ((m.value.toFloat() - scale.minimum) / scale.range).coerceIn(0f, 1f)
                val tint = when (m.origin) { TrafficOrigin.BEACON -> beaconLine; TrafficOrigin.MIXED -> mixedLine; else -> localLine }
                drawChartMarker(Offset(x, size.height - inset - fraction * (size.height - inset * 2)), tint, background,
                    if (motion) pulse.value else 0f)
                travelTarget?.let { drawChartTravel(travel.value, it.position, tint,
                    inset, inset, size.width - 2 * inset, size.height - 2 * inset) }
            }
        }
      }
    }
    ChartInspectionPanel(probe != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MetricDetailValueText(selectionText, MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.TextButton(onClick = hapticClick { close() }) { Text(stringResource(R.string.traffic_close_inspection)) }
        }
    }
    if (!showAnnotations) return
    val coverage = remember(values) { reportChartCoverage(values) }
    Text(androidx.compose.ui.res.stringResource(R.string.editorial_report_coverage, description, coverage.filled, coverage.total, number(coverage.percent)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val origins = values.filter { it.value?.isFinite() == true }.map { it.origin }.toSet()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ReportBucketOrigin.LOCAL in origins) ReportOriginLegend(androidx.compose.ui.res.stringResource(R.string.traffic_local), localLine)
        if (ReportBucketOrigin.BEACON in origins) ReportOriginLegend("L-Shell Beacon", beaconLine)
        if (ReportBucketOrigin.MIXED in origins) ReportOriginLegend(androidx.compose.ui.res.stringResource(R.string.traffic_mixed), mixedLine)
    }
    if (coverage.filled < coverage.total) Text(androidx.compose.ui.res.stringResource(R.string.editorial_chart_gaps),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ReportOriginLegend(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EventTimelineGraph(events: List<ReportMoment>, start: Long, end: Long, description: String) {
    if (events.isEmpty()) {
        Text("Nenhuma ocorrência neste período.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val timelineGrid = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val slots = (maxWidth.value / 14f).toInt().coerceIn(8, 48)
        val buckets = remember(events, start, end, slots) {
            aggregateReportTimeline(events, start, end, slots)
        }
        val compactLabels = maxWidth < 300.dp
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(Modifier.fillMaxWidth(), shape = DashDesign.section, color = track) {
                Canvas(Modifier.fillMaxWidth().height(104.dp).clip(DashDesign.section)) {
                    val left = 14.dp.toPx().coerceAtMost(size.width / 4f)
                    val right = (size.width - left).coerceAtLeast(left)
                    val center = size.height / 2f
                    val width = (right - left).coerceAtLeast(1f)
                    clipRect(0f, 0f, size.width, size.height) {
                        drawLine(timelineGrid.copy(alpha = .55f),
                            Offset(left, center), Offset(right, center), 7.dp.toPx(), StrokeCap.Round)
                        buckets.forEach { bucket ->
                            val x = left + bucket.fraction * width
                            val emphasis = (bucket.count - 1).coerceIn(0, 6)
                            val markerHeight = (18f + emphasis * 2.5f).dp.toPx()
                            val color = when (bucket.tone) {
                                3 -> error
                                2 -> tertiary
                                1 -> secondary
                                else -> primary
                            }
                            drawLine(color.copy(alpha = .58f),
                                Offset(x, center - markerHeight), Offset(x, center + markerHeight),
                                (2f + emphasis * .25f).dp.toPx(), StrokeCap.Round)
                            drawCircle(track, (5f + emphasis * .35f).dp.toPx(), Offset(x, center))
                            drawCircle(color, (3.5f + emphasis * .30f).dp.toPx(), Offset(x, center))
                        }
                    }
                }
            }
            val periodLabel = "${time(start)} — ${time(end)}"
            val countLabel = if (buckets.size < events.size)
                androidx.compose.ui.res.stringResource(R.string.editorial_report_event_groups, events.size, buckets.size)
            else androidx.compose.ui.res.stringResource(R.string.editorial_report_events, events.size)
            if (compactLabels) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(periodLabel, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(countLabel, style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(periodLabel, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(countLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    Text(description, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal data class ReportChartScale(
    val minimum: Float,
    val maximum: Float,
    val constant: Boolean
) { val range: Float get() = (maximum - minimum).coerceAtLeast(Float.MIN_VALUE) }

internal data class ReportChartSample(val index: Int, val bucket: ReportChartBucket)

internal fun reportChartScale(values: List<Float?>): ReportChartScale? {
    val finite = values.mapNotNull { it?.takeIf { sample -> sample.isFinite() } }
    if (finite.isEmpty()) return null
    val dataMin = finite.min()
    val dataMax = finite.max()
    val rawRange = dataMax - dataMin
    val epsilon = max(max(abs(dataMin), abs(dataMax)) * .0001f, .0001f)
    val constant = rawRange <= epsilon
    val referenceRange = if (constant) max(abs(dataMax) * .12f, 1f) else rawRange
    val padding = referenceRange * .10f
    var lower = dataMin - padding
    var upper = dataMax + padding
    if (finite.all { it >= 0f }) lower = lower.coerceAtLeast(0f)
    if (upper - lower <= epsilon) upper = lower + referenceRange.coerceAtLeast(epsilon)
    return ReportChartScale(lower, upper, constant)
}

internal fun reportChartSamples(values: List<ReportChartBucket>): List<List<ReportChartSample>> {
    val segments = mutableListOf<MutableList<ReportChartSample>>()
    values.forEachIndexed { index, bucket ->
        if (bucket.value?.isFinite() != true) return@forEachIndexed
        if (index == 0 || values[index - 1].value?.isFinite() != true || values[index - 1].end < bucket.start) segments.add(mutableListOf())
        segments.last() += ReportChartSample(index, bucket)
    }
    return segments
}

internal data class ReportTimelineBucket(val fraction: Float, val count: Int, val tone: Int)

internal fun aggregateReportTimeline(
    events: List<ReportMoment>,
    start: Long,
    end: Long,
    slots: Int
): List<ReportTimelineBucket> {
    if (events.isEmpty()) return emptyList()
    val safeSlots = slots.coerceAtLeast(1)
    val span = (end - start).coerceAtLeast(1L)
    return events.groupBy { event ->
        val fraction = ((event.timestamp - start).toDouble() / span).coerceIn(0.0, 1.0)
        (fraction * safeSlots).toInt().coerceIn(0, safeSlots - 1)
    }.toSortedMap().map { (_, grouped) ->
        val fraction = grouped.map { event ->
            ((event.timestamp - start).toDouble() / span).coerceIn(0.0, 1.0)
        }.average().toFloat()
        ReportTimelineBucket(fraction, grouped.size, grouped.maxOf(::reportTimelineTone))
    }
}

private fun reportTimelineTone(event: ReportMoment): Int = HistoryEventCodec.timelineTone(event.title)

@Composable
private fun EventBreakdown(events: List<ReportMoment>) {
    val grouped = events.groupBy { HistoryEventCodec.parse(it.title).semanticKey }
        .values.map { it.first().title to it.size }.sortedByDescending { it.second }
    val maximum = grouped.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    grouped.take(5).forEach { (title, count) ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(telemetryLabel(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(count.toString(), style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(progress = { count.toFloat() / maximum },
                modifier = Modifier.fillMaxWidth().height(8.dp), trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        }
    }
    if (grouped.size > 5) Text(androidx.compose.ui.res.pluralStringResource(
        R.plurals.ui_more_event_types, grouped.size - 5, grouped.size - 5),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun eventPriority(title: String): Int = HistoryEventCodec.priority(title)

@Composable
private fun ReportEmpty(message: String, loading: Boolean = false) {
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

private fun day(value: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(value))
private fun time(value: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(value))
private fun dateTime(value: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
private fun number(value: Float, decimals: Int = 1) = String.format(Locale.getDefault(), "%.${decimals}f", value)
private fun duration(ms: Long): String = when {
    ms < 60_000L -> "${ms / 1_000L} s"
    ms < 3_600_000L -> "${ms / 60_000L} min ${ms / 1_000L % 60L} s"
    else -> "${ms / 3_600_000L} h ${ms / 60_000L % 60L} min"
}
