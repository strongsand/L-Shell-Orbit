package io.github.strongsand.lshell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date
import kotlin.math.abs

/** Uses the dedicated metrics' interaction engine; statistics and original samples are unchanged. */
@Composable
internal fun TrafficChart(data: TrafficView, metric: TrafficMetric, zone: String? = null,
    initialSelectedTime: Long? = null, onEvent: (TrafficEvent) -> Unit) {
    val a = data.analysis
    val series = data.series.getValue(metric)
    val grouped = remember(a) { TrafficSessionAnalyzer.groupedEvents(a.events, a.start, a.end) }
    val markers = remember(grouped) { grouped.map { it.start } }
    MetricDetailChart(series, trafficMetricName(metric), trafficUnit(metric), zone, markers, initialSelectedTime) { selected, time, close ->
        val point = selected?.let { TrafficChartPresentation.exactPoint(a.points, it.time) }
        if (point != null) TrafficPointDetails(point, metric, zone,
            a.events.filter { point.time >= it.start && (point.time < it.end || point.time == it.start && it.start == it.end) }, onEvent, close)
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(trafficTime(time, zone, true) + " · " + stringResource(R.string.metric_detail_gap),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = hapticClick(action = close)) { Icon(OrbitSymbols.Close, stringResource(R.string.traffic_close_inspection)) }
        }
    }
}

@Composable
internal fun TrafficPowerChart(data: TrafficView, zone: String? = null,
    initialSelectedTime: Long? = null, onEvent: (TrafficEvent) -> Unit) {
    val a = data.analysis
    val grouped = remember(a) { TrafficSessionAnalyzer.groupedEvents(a.events, a.start, a.end) }
    val markers = remember(grouped) { grouped.map { it.start } }
    MetricDetailChart(data.power, stringResource(R.string.editorial_dish_power), "W", zone, markers, initialSelectedTime) { selected, time, close ->
        val point = selected?.let { TrafficChartPresentation.exactPoint(a.points, it.time) }
        if (point != null) TrafficPointDetails(point, null, zone,
            a.events.filter { point.time >= it.start && (point.time < it.end || point.time == it.start && it.start == it.end) }, onEvent, close, powerSelected = true)
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(trafficTime(time, zone, true) + " · " + stringResource(R.string.metric_detail_gap),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = hapticClick(action = close)) { Icon(OrbitSymbols.Close, stringResource(R.string.traffic_close_inspection)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrafficPointDetails(point: TrafficPoint,metric: TrafficMetric?,zone: String?,
    events: List<TrafficEvent>,onEvent: (TrafficEvent) -> Unit,close: () -> Unit,
    powerSelected: Boolean = false) {
    val palette = MaterialTheme.colorScheme
    val origin = TrafficSessionAnalyzer.origin(
        TrafficMetric.entries.mapNotNull { point.metric(it)?.origin } + listOfNotNull(point.power?.origin)
    )
    Surface(Modifier.fillMaxWidth(),shape = DashDesign.section,color = palette.surfaceContainerHigh) {
        Column(Modifier.padding(start = 12.dp,end = 8.dp,bottom = 12.dp)
            .semantics(mergeDescendants = true) {},verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trafficTime(point.time,zone,true),Modifier.weight(1f),style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = hapticClick(action = close)) { Icon(OrbitSymbols.Close,stringResource(R.string.traffic_close_inspection)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TrafficMetric.entries.forEach { field ->
                    val value = point.metric(field)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(trafficMetricName(field),style = MaterialTheme.typography.labelSmall,
                            color = if (field == metric) palette.primary else palette.onSurfaceVariant)
                        Text(trafficValue(value?.value,trafficUnit(field)),style = MaterialTheme.typography.titleSmall)
                        if (origin == TrafficOrigin.MIXED && value != null)
                            Text(trafficOrigin(value.origin),style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (powerSelected || point.power != null) {
                    val value = point.power
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.editorial_dish_power),style = MaterialTheme.typography.labelSmall,
                            color = if (powerSelected) palette.primary else palette.onSurfaceVariant)
                        Text(trafficValue(value?.value,"W"),style = MaterialTheme.typography.titleSmall)
                        if (origin == TrafficOrigin.MIXED && value != null)
                            Text(trafficOrigin(value.origin),style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            TrafficSourceBadge(origin)
            val missingSelected = if (powerSelected) point.power == null else metric != null && point.metric(metric) == null
            if (missingSelected) Text(stringResource(R.string.traffic_point_missing),
                style = MaterialTheme.typography.bodySmall,color = palette.onSurfaceVariant)
            if (events.isNotEmpty()) TextButton(onClick = hapticClick {
                val first = events.first()
                onEvent(if (events.size == 1) first else first.copy(end = events.maxOf { it.end },
                    observations = events.sumOf { it.observations },members = events))
            }) {
                Text(if (events.size == 1) trafficEventName(events.first())
                    else stringResource(R.string.traffic_event_count,events.size))
            }
        }
    }
}

@Composable
internal fun TrafficSourceBadge(origin: TrafficOrigin) {
    val colors = MaterialTheme.colorScheme
    val color = when (origin) { TrafficOrigin.BEACON -> colors.tertiaryContainer; TrafficOrigin.MIXED -> colors.secondaryContainer; else -> colors.surfaceContainerHigh }
    val description = stringResource(R.string.traffic_source_caption,trafficOrigin(origin))
    Surface(Modifier.semantics(mergeDescendants = true) { contentDescription = description },shape = DashDesign.pill,color = color) {
        Row(Modifier.padding(horizontal = 8.dp,vertical = 4.dp),verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val tint = when (origin) { TrafficOrigin.BEACON -> colors.tertiary; TrafficOrigin.MIXED -> colors.secondary;
                TrafficOrigin.UNKNOWN -> colors.outline; else -> colors.primary }
            Canvas(Modifier.size(5.dp)) { drawCircle(tint) }
            Text(trafficOrigin(origin),style = MaterialTheme.typography.labelSmall,color = contentColorFor(color))
        }
    }
}

@Composable
internal fun TrafficStats(s: TrafficSummary) {
    val values = listOf(
        stringResource(R.string.traffic_mean) to trafficValue(s.mean,"Mbps"),
        stringResource(R.string.traffic_median) to trafficValue(s.median,"Mbps"),
        stringResource(R.string.traffic_peak) to trafficValue(s.peak,"Mbps"),
        stringResource(R.string.traffic_short_sustained,"30\u00a0s") to trafficValue(s.sustained30s,"Mbps"),
        stringResource(R.string.traffic_short_sustained,"1\u00a0min") to trafficValue(s.sustained1m,"Mbps"),
        stringResource(R.string.traffic_short_sustained,"5\u00a0min") to trafficValue(s.sustained5m,"Mbps"),
        stringResource(R.string.traffic_bytes) to (s.estimatedBytes?.let(::trafficBytes) ?: "—"),
        stringResource(R.string.traffic_short_active) to trafficShortDuration(s.activeMs))
    TrafficStatGrid(values)
}

@Composable
internal fun TrafficPowerStats(series: MetricHistorySeries) {
    val sorted = remember(series) { series.values.sorted() }
    val values = listOf(
        stringResource(R.string.traffic_mean) to trafficValue(sorted.takeIf { it.isNotEmpty() }?.average(), "W"),
        stringResource(R.string.traffic_median) to trafficValue(TrafficSessionAnalyzer.median(sorted), "W"),
        stringResource(R.string.traffic_peak) to trafficValue(sorted.lastOrNull(), "W"),
        stringResource(R.string.traffic_minimum) to trafficValue(sorted.firstOrNull { it > 0 }, "W"))
    TrafficStatGrid(values)
}

@Composable
internal fun TrafficStatGrid(values: List<Pair<String,String>>) {
    val fontScale = LocalDensity.current.fontScale
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.padding(12.dp)) {
            val columns = if (HealthReportUiPolicy.twoColumns(maxWidth.value, fontScale, 300f)) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                values.chunked(columns).forEach { group ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        group.forEach { (label,value) -> Column(Modifier.weight(1f).semantics(mergeDescendants = true) {},
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(label,style = MaterialTheme.typography.labelMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value,style = MaterialTheme.typography.titleMedium)
                        } }
                        if (group.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun TrafficEventSection(a: TrafficAnalysis,zone: String?,onEvent: (TrafficEvent) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val grouped = remember(a) { TrafficSessionAnalyzer.groupedEvents(a.events,a.start,a.end).sortedByDescending { it.start } }
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(stringResource(R.string.traffic_events), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.traffic_event_count,a.events.size),style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val shown = if (expanded) grouped else grouped.take(3)
    shown.forEach { event ->
        Surface(onClick = hapticClick { onEvent(event) },color = MaterialTheme.colorScheme.surface,
            shape = DashDesign.section,modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Row(Modifier.padding(vertical = 10.dp,horizontal = 4.dp),horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(trafficTime(event.start,zone),style = MaterialTheme.typography.labelMedium)
                Text(trafficEventName(event),Modifier.weight(1f),style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (grouped.isEmpty()) Text(stringResource(R.string.traffic_no_events),style = MaterialTheme.typography.bodySmall)
    if (grouped.size > 3) TextButton(onClick = hapticClick { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.traffic_show_less else R.string.traffic_see_all))
    }
    Text(stringResource(R.string.traffic_outages,a.interruptionCount,trafficShortDuration(a.downtimeMs)),
        style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun TrafficTechnical(a: TrafficAnalysis,metrics: List<TrafficMetric>,power: MetricHistorySeries? = null) {
    SectionHeading(stringResource(R.string.traffic_technical))
    Text(stringResource(R.string.traffic_samples,a.points.size,a.averageIntervalMs?.let { androidx.compose.ui.res.stringResource(R.string.editorial_seconds, trafficNumber(it / 1000)) } ?: androidx.compose.ui.res.stringResource(R.string.editorial_not_calculated)))
    Text(stringResource(R.string.traffic_gap_count,a.gaps.size))
    metrics.forEach { metric ->
        val s = a.summary(metric)
        Text(trafficMetricName(metric),style = MaterialTheme.typography.titleSmall)
        InfoRow(stringResource(R.string.traffic_minimum),trafficValue(s.usefulMinimum,"Mbps"))
        Text(stringResource(R.string.traffic_distribution),style = MaterialTheme.typography.labelMedium)
        s.aboveThresholdMs.forEach { (threshold,duration) -> Text(stringResource(R.string.traffic_above,
            trafficNumber(threshold),trafficNumber(if (s.coveredMs > 0) duration * 100.0 / s.coveredMs else 0.0)),
            style = MaterialTheme.typography.bodySmall) }
    }
    power?.let { series ->
        val minimum = series.values.filter { it > 0 }.minOrNull()
        Text(stringResource(R.string.editorial_dish_power), style = MaterialTheme.typography.titleSmall)
        InfoRow(stringResource(R.string.traffic_minimum), trafficValue(minimum, "W"))
    }
}

@Composable
internal fun TrafficEventDialog(event: TrafficEvent,zone: String?,dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss,title = { Text(trafficEventName(event)) },text = {
        LazyColumn(Modifier.heightIn(max = 360.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(event.members.ifEmpty { listOf(event) }) {
                Text(trafficEventName(it),style = MaterialTheme.typography.titleSmall)
                Text(trafficDate(it.start,zone) + " • " + trafficShortDuration(it.end - it.start))
                if (it.kind == TrafficEventKind.ALERT) it.detail?.let { detail -> Text(telemetryLabel(detail)) }
            }
            item { Text(stringResource(R.string.traffic_observations,event.observations)) }
        }
    },confirmButton = { TextButton(onClick = hapticClick(action = dismiss)) { Text(stringResource(R.string.traffic_back)) } })
}
@Composable
internal fun trafficOrigin(origin: TrafficOrigin) = stringResource(when (origin) {
    TrafficOrigin.LOCAL_PHONE -> R.string.traffic_local; TrafficOrigin.BEACON -> R.string.traffic_beacon
    TrafficOrigin.MIXED -> R.string.traffic_mixed; TrafficOrigin.UNKNOWN -> R.string.traffic_unknown })
@Composable
internal fun trafficMetricName(metric: TrafficMetric) = stringResource(when (metric) {
    TrafficMetric.DOWNLOAD -> R.string.traffic_download; TrafficMetric.UPLOAD -> R.string.traffic_upload
    TrafficMetric.LATENCY -> R.string.traffic_latency; TrafficMetric.LOSS -> R.string.traffic_loss })
@Composable
internal fun trafficEventName(event: TrafficEvent) = stringResource(if (event.members.map { it.kind }.distinct().size > 1)
    R.string.traffic_event_group else when (event.kind) {
    TrafficEventKind.DOWNLINK -> R.string.traffic_event_downlink; TrafficEventKind.LOSS -> R.string.traffic_event_loss
    TrafficEventKind.OBSTRUCTION -> R.string.traffic_event_obstruction; TrafficEventKind.UNREACHABLE -> R.string.traffic_event_unreachable
    TrafficEventKind.RECONNECT -> R.string.traffic_event_reconnect; TrafficEventKind.STATE -> R.string.traffic_event_state
    TrafficEventKind.ALERT -> R.string.traffic_event_alert })
internal fun trafficUnit(metric: TrafficMetric) = if (metric == TrafficMetric.LATENCY) "ms" else if (metric == TrafficMetric.LOSS) "%" else "Mbps"
@Composable
internal fun trafficShortDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return listOfNotNull(
        (seconds / 3600).takeIf { it > 0 }?.let { stringResource(R.string.traffic_hours,it.toInt()) },
        (seconds / 60 % 60).takeIf { it > 0 }?.let { stringResource(R.string.traffic_minutes,it.toInt()) },
        (seconds % 60).takeIf { it > 0 || seconds == 0L }?.let { stringResource(R.string.traffic_seconds,it.toInt()) }
    ).joinToString(" ")
}
internal fun trafficDate(time: Long,zone: String? = null): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).apply {
        if (zone != null) timeZone = java.util.TimeZone.getTimeZone(ZoneId.of(zone))
    }.format(Date(time))
