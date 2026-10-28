package io.github.strongsand.lshell

import androidx.compose.ui.res.stringResource

import androidx.compose.ui.res.pluralStringResource

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.core.data.TelemetrySample
import io.github.strongsand.lshell.core.model.DishySnapshot
import io.github.strongsand.lshell.beacon.BeaconSampleSource
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SourcedTelemetry(
    val sample: TelemetrySample,
    val source: BeaconSampleSource,
    val supportMs: Long = 2_000
)

@Composable
internal fun TelemetryCharts(samples: List<TelemetrySample>, beaconHistoryRevision: Long? = null,
    editorScroll: CardEditorScroll, liveSnapshot: DishySnapshot? = null, livePowerWatts: Float? = null) {
    val context = LocalContext.current
    val arrangement = rememberCardArrangement("antenna", listOf("latency", "loss", "download", "upload", "power"))
    var minutes by rememberSaveable { mutableIntStateOf(10) }
    var chooseWindow by remember { mutableStateOf(false) }
    var stored by remember { mutableStateOf<List<SourcedTelemetry>>(emptyList()) }
    val newestSecond = samples.lastOrNull()?.timestamp?.div(60_000L)
    // A completed Beacon ACK updates this revision through BeaconState. Including it in the
    // effect key makes newly persisted Beacon rows appear without reopening the Antenna screen.
    LaunchedEffect(minutes, newestSecond, beaconHistoryRevision) {
        stored = withContext(Dispatchers.IO) {
            HistoryStore(context).use { store ->
                store.between(System.currentTimeMillis() - minutes * 60_000L, System.currentTimeMillis() + 1_000L)
                    .filter { it.kind == "reading" || it.kind == "terminal_history" || it.kind == "beacon_history" }
                    .map { entry -> SourcedTelemetry(
                        TelemetrySample(entry.timestamp, entry.downloadMbps, entry.uploadMbps,
                            entry.latencyMs, entry.dropPercent, entry.powerWatts),
                        entry.source, when (entry.kind) { "terminal_history" -> 1_000; "beacon_history" -> 5_000; else -> 2_000 }
                    ) }
            }
        }
    }
    val windowMs = minutes * 60_000L
    val recent = remember(samples, stored, minutes) {
        val live = samples.map { SourcedTelemetry(it, BeaconSampleSource.LOCAL_PHONE) }
        (stored + live)
            .filter { it.sample.timestamp >= System.currentTimeMillis() - windowMs }
            .distinctBy { it.sample.timestamp / 1_000L }.sortedBy { it.sample.timestamp }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SectionHeading(pluralStringResource(R.plurals.ui_last_minutes, minutes, minutes), stringResource(R.string.ui_tap_the_chart_to_explore_each_reading))
        }
        IconButton(onClick = hapticClick { chooseWindow = true }) {
            Icon(OrbitSymbols.Edit, contentDescription = androidx.compose.ui.res.stringResource(R.string.editorial_chart_period))
        }
    }
    Spacer(Modifier.height(12.dp))
    if (recent.size < 2) {
        Surface(shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text(if (recent.isEmpty()) pluralStringResource(R.plurals.ui_history_minutes_empty, minutes, minutes)
                else stringResource(R.string.ui_waiting_for_the_next_reading_to_draw_the_charts),
                Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val colors = MaterialTheme.colorScheme
    val energyColors = energyMetricColors()
    val hasBeacon = recent.any { it.source == BeaconSampleSource.BEACON }
    val hasLocal = recent.any { it.source == BeaconSampleSource.LOCAL_PHONE }
    if (hasBeacon) {
        ChartOriginLegend(showLocal = hasLocal)
        Spacer(Modifier.height(12.dp))
    }
    val packetLoss = recent.any { it.sample.dropPercent.isFinite() && it.sample.dropPercent > 0f }
    @Composable fun chartLabel(id: String) = stringResource(when (id) {
        "latency" -> R.string.editorial_pop_latency; "loss" -> R.string.traffic_loss
        "download" -> R.string.traffic_download; "upload" -> R.string.traffic_upload
        else -> R.string.editorial_power
    })
    val live = liveSnapshot?.takeIf { it.isOnline && !it.isFromCache }
    EditableCardGrid(arrangement, host = editorScroll, label = { chartLabel(it) }) { id, compact ->
        val title = chartLabel(id)
        when (id) {
            "latency" -> MiniChart(title, "ms", remember(recent) { recent.map { it.sample.latencyMs } }, recent, windowMs, minutes, colors.secondary, colors.secondaryContainer, compact, live?.latency?.popPingLatencyMs)
            "loss" -> MiniChart(title, "%", remember(recent) { recent.map { it.sample.dropPercent } }, recent, windowMs, minutes,
                if (packetLoss) colors.error else colors.tertiary, if (packetLoss) colors.errorContainer else colors.tertiaryContainer, compact, live?.latency?.popPingDropRate?.times(100f))
            "download" -> MiniChart(title, "Mbps", remember(recent) { recent.map { it.sample.downloadMbps } }, recent, windowMs, minutes, colors.primary, colors.primaryContainer, compact, live?.throughput?.downlinkMbps)
            "upload" -> MiniChart(title, "Mbps", remember(recent) { recent.map { it.sample.uploadMbps } }, recent, windowMs, minutes, colors.tertiary, colors.tertiaryContainer, compact, live?.throughput?.uplinkMbps)
            else -> if (recent.any { it.sample.powerWatts?.isFinite() == true }) {
                MiniChart(title, "W", remember(recent) { recent.map { it.sample.powerWatts ?: Float.NaN } }, recent, windowMs, minutes, energyColors.chartLine, energyColors.chartFill, compact, if (live != null) livePowerWatts else null)
            } else Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
                color = energyColors.container, contentColor = energyColors.onContainer) {
                Column(Modifier.padding(if (compact) 12.dp else DashDesign.inset), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(OrbitSymbols.Bolt, null)
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.editorial_no_power), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (chooseWindow) AlertDialog(
        onDismissRequest = { chooseWindow = false },
        icon = { Icon(OrbitSymbols.Edit, null) },
        title = { Text(androidx.compose.ui.res.stringResource(R.string.editorial_chart_period_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5, 10, 15, 30, 60, 180, 360, 720, 1440).forEach { option ->
                    val selected = option == minutes
                    Surface(onClick = hapticClick {
                        minutes = option
                        chooseWindow = false
                    }, color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = DashDesign.pill, modifier = Modifier.fillMaxWidth()) {
                        Text(if (option < 60) pluralStringResource(R.plurals.ui_minutes, option, option)
                            else pluralStringResource(R.plurals.ui_hours, option / 60, option / 60),
                            Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ChartOriginLegend(showLocal: Boolean) {
    Surface(shape = DashDesign.pill, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showLocal) OriginLegendItem(androidx.compose.ui.res.stringResource(R.string.traffic_local), MaterialTheme.colorScheme.primary)
            OriginLegendItem(androidx.compose.ui.res.stringResource(R.string.traffic_beacon), MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun OriginLegendItem(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(9.dp), shape = DashDesign.cookie, color = color) {}
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MiniChart(title: String, unit: String, values: List<Float>, samples: List<SourcedTelemetry>,
                      windowMs: Long, windowMinutes: Int, color: Color, fillColor: Color, compact: Boolean = false, liveValue: Float? = null) {
    val haptics = rememberDashHaptics()
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    val plotBackground = MaterialTheme.colorScheme.surfaceContainer
    var selectedTime by rememberSaveable { mutableStateOf<Long?>(null) }
    var chartWidth by remember { mutableFloatStateOf(1f) }
    val chartHeight = if (compact) 112.dp else 140.dp
    val localDensity = LocalDensity.current
    val profile = when (unit) { "ms" -> CardMetricProfile.LATENCY; "%" -> CardMetricProfile.LOSS
        "W" -> CardMetricProfile.ENERGY; else -> CardMetricProfile.THROUGHPUT }
    val style = remember(values, chartWidth, chartHeight, profile, localDensity.density) {
        CardVisualPolicy.style(values, chartWidth / localDensity.density, chartHeight.value, profile)
    }
    val chartInset = with(localDensity) { style.insetDp.dp.toPx() }
    val emphasis = remember(values, liveValue, profile) { CardVisualPolicy.emphasis(liveValue, values.dropLast(1), profile) }
    val tint = if (profile.adverse) MaterialTheme.colorScheme.error else color
    val base = if (LocalExperience.current.theme == OrbitTheme.AMOLED) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow
    val editing = LocalCardEditing.current
    val hasBeacon = remember(samples) { samples.any { it.source == BeaconSampleSource.BEACON } }
    val beaconLine = MaterialTheme.colorScheme.onSecondaryContainer
    val windowEnd = samples.last().sample.timestamp
    val windowStart = windowEnd - windowMs
    fun fractionAt(index: Int) = ((samples[index].sample.timestamp - windowStart).toFloat() / windowMs.toFloat()).coerceIn(0f, 1f)
    val peak = remember(values) { values.filter { it.isFinite() }.maxOrNull() }
    val ceiling = max(1f, peak ?: 0f) * 1.15f
    // Keep original observations for inspection; reduce only the drawing workload.
    // Restoring the renderer must not restore averaging away NULLs or bridging gaps.
    val inspectionSeries = remember(samples, values, windowMs) {
        val points = samples.mapIndexed { i, source -> MetricHistoryPoint(source.sample.timestamp,
            values[i].takeIf { it.isFinite() }?.let { value -> TrafficValue(value.toDouble(),
                if (source.source == BeaconSampleSource.BEACON) TrafficOrigin.BEACON else TrafficOrigin.LOCAL_PHONE, source.supportMs) }) }
        val series = MetricHistorySeries(windowStart, windowEnd + 1, points, 0, emptyList())
        series
    }
    val inspectionIndex = remember(inspectionSeries) { ChartSampleIndex(inspectionSeries) }
    val plot = remember(inspectionSeries, style.buckets) { MetricChartPresentation.reduce(inspectionSeries,
        MetricTimeViewport(inspectionSeries.start, inspectionSeries.end), style.buckets) }
    val validSegments = remember(plot) {
        val segments = mutableListOf<MutableList<Int>>()
        plot.indices.forEach { index ->
            if (index == 0 || !plot[index].connect) segments.add(mutableListOf())
            segments.last() += index
        }
        segments.filter { it.size >= 2 }
    }
    fun atOrAfter(time: Long): Int {
        val found = inspectionSeries.points.binarySearchBy(time) { it.time }
        return if (found >= 0) found else -found - 1
    }
    val selected = selectedTime?.let { time -> atOrAfter(time).takeIf { it < samples.size } }
        ?: values.lastIndex
    val gesture = remember { ChartDirectManipulation() }
    val density = LocalDensity.current.density
    val travelTarget = selectedTime?.takeIf { values[selected].isFinite() }?.let {
        val time = samples[selected].sample.timestamp
        inspectionIndex.segmentAt(time)?.let { segment ->
            ChartMotionTarget(time, Offset(fractionAt(selected), 1f - (values[selected] / ceiling).coerceIn(0f, 1f)), segment)
        }
    }
    val travel = rememberChartTravel(travelTarget, gesture.active,
        Triple(title, windowStart to windowEnd, ceiling), androidx.compose.ui.geometry.Size((chartWidth / density - 2 * style.insetDp).coerceAtLeast(1f), chartHeight.value - 2 * style.insetDp))
    val shownValue = values[selected]
    val shownTime = if (selectedTime != null || System.currentTimeMillis() - samples.last().sample.timestamp > 60_000L)
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(samples[selected].sample.timestamp)) else androidx.compose.ui.res.stringResource(R.string.traffic_now)
    val selectAt by rememberUpdatedState<(Float) -> Unit> { x ->
        val wanted = windowStart + (((x - chartInset) / (chartWidth - chartInset * 2).coerceAtLeast(1f)).coerceIn(0f, 1f) * windowMs).toLong()
        val right = atOrAfter(wanted).takeIf { it < samples.size } ?: -1
        val index = if (right <= 0) { if (right < 0) samples.lastIndex else 0 }
            else if (wanted - samples[right - 1].sample.timestamp < samples[right].sample.timestamp - wanted) right - 1 else right
        selectedTime = samples[index].sample.timestamp
    }
    val description = androidx.compose.ui.res.stringResource(R.string.editorial_graph_summary, title, shownTime,
        shownValue.takeIf { it.isFinite() }?.let { "${"%.2f".format(it)} $unit" } ?: androidx.compose.ui.res.stringResource(R.string.editorial_no_reading),
        peak?.let { "${"%.2f".format(it)} $unit" } ?: androidx.compose.ui.res.stringResource(R.string.editorial_no_reading))
    val previousReading = androidx.compose.ui.res.stringResource(R.string.editorial_previous_reading)
    val nextReading = androidx.compose.ui.res.stringResource(R.string.editorial_next_reading)
    val returnRecent = androidx.compose.ui.res.stringResource(R.string.editorial_return_recent)
    val passiveDrawing = remember(plot, validSegments, style, ceiling, windowStart, windowMs, color, fillColor, beaconLine, grid) {
        Modifier.drawWithCache {
            val inset = style.insetDp.dp.toPx()
            val width = (size.width - 2 * inset).coerceAtLeast(1f)
            val height = (size.height - 2 * inset).coerceAtLeast(1f)
            fun point(i: Int) = Offset(inset + ((plot[i].time - windowStart).toFloat() / windowMs).coerceIn(0f, 1f) * width,
                inset + height * (1f - (plot[i].value!!.toFloat() / ceiling).coerceIn(0f, 1f)))
            val areas = validSegments.map { segment -> Path().apply {
                val first = point(segment.first()); val last = point(segment.last())
                moveTo(first.x, inset + height); lineTo(first.x, first.y)
                segment.drop(1).forEach { i -> val p = point(i); lineTo(p.x, p.y) }
                lineTo(last.x, inset + height); close()
            } }
            val paths = mutableMapOf<Color, Path>()
            val dots = mutableListOf<Pair<Color, Offset>>()
            plot.forEachIndexed { i, p ->
                val tone = if (p.origin == TrafficOrigin.BEACON) beaconLine else color
                if (p.connect) paths.getOrPut(tone) { Path() }.apply {
                    val a = point(i - 1); val b = point(i); moveTo(a.x, a.y); lineTo(b.x, b.y)
                } else dots += tone to point(i)
            }
            val brush = Brush.verticalGradient(listOf(color.copy(alpha = style.fillAlpha), fillColor.copy(alpha = 0f)), endY = size.height)
            onDrawBehind {
                for (i in 0..2) {
                    val y = inset + height * i / 2f
                    drawLine(grid.copy(alpha = style.gridAlpha), Offset(inset, y), Offset(inset + width, y), .6.dp.toPx())
                }
                areas.forEach { drawPath(it, brush) }
                paths.forEach { (tone, path) -> drawPath(path, tone, style = Stroke(style.strokeDp.dp.toPx(), cap = StrokeCap.Round)) }
                dots.forEach { (tone, at) -> drawCircle(tone, (style.strokeDp * .65f).dp.toPx(), at) }
            }
        }
    }
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section, color = base) {
        MetricIntensityBody(emphasis, base, tint, listOf(MaterialTheme.colorScheme.onSurface, grid, color, beaconLine)) {
        Column(Modifier.padding(if (compact) 12.dp else DashDesign.inset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium, minLines = 1)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = if (compact) 1 else Int.MAX_VALUE,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.widthIn(max = 240.dp)) {
                    if (compact) {
                        Text(if (shownValue.isFinite()) "%.2f".format(shownValue) else "—",
                            style = MaterialTheme.typography.headlineSmall, color = color)
                        Text(unit, style = MaterialTheme.typography.labelLarge, color = color)
                    } else HomeMetricValue(if (shownValue.isFinite()) "%.2f".format(shownValue) else "—", unit, color)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(shownTime, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (samples[selected].source == BeaconSampleSource.BEACON || compact && hasBeacon)
                        Text(if (samples[selected].source == BeaconSampleSource.BEACON) stringResource(R.string.traffic_beacon) else "",
                            style = MaterialTheme.typography.labelMedium, color = beaconLine, minLines = 1)
                    Text(androidx.compose.ui.res.stringResource(R.string.editorial_peak_value, peak?.let { "%.2f".format(it) } ?: "—", unit), style = MaterialTheme.typography.labelLarge, color = color, minLines = 1)
                }
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(if (LocalExperience.current.theme == OrbitTheme.AMOLED) MaterialTheme.colorScheme.surface else plotBackground).padding(10.dp)) {
            Box {
            Canvas(
                Modifier.fillMaxWidth().height(chartHeight)
                    .onSizeChanged { chartWidth = it.width.toFloat() }
                    .semantics {
                        contentDescription = description
                        customActions = listOf(
                            CustomAccessibilityAction(previousReading) {
                                selectedTime = samples[(selected - 1).coerceAtLeast(0)].sample.timestamp; true
                            },
                            CustomAccessibilityAction(nextReading) {
                                selectedTime = samples[(selected + 1).coerceAtMost(samples.lastIndex)].sample.timestamp; true
                            },
                            CustomAccessibilityAction(returnRecent) { selectedTime = null; true }
                        )
                    }
                    .pointerInput(editing) { if (!editing) detectTapGestures { haptics.perform(); selectAt(it.x) } }
                    .pointerInput(editing) {
                        if (editing) return@pointerInput
                        try { detectHorizontalDragGestures(
                            onDragStart = { gesture.active = true; haptics.perform(); selectAt(it.x) },
                            onDragEnd = { gesture.active = false },
                            onDragCancel = { gesture.active = false },
                            onHorizontalDrag = { change, _ -> selectAt(change.position.x); change.consume() }
                        ) } finally { gesture.active = false }
                    }
                    .then(passiveDrawing)
            ) {}
            Canvas(Modifier.matchParentSize()) {
                val inset = style.insetDp.dp.toPx()
                val plotWidth = size.width - inset * 2
                val plotHeight = size.height - inset * 2
                fun xAt(index: Int) = inset + fractionAt(index) * plotWidth
                fun yAt(index: Int) = inset + plotHeight * (1f - (values[index] / ceiling).coerceIn(0f, 1f))
                val index = if (selectedTime != null) selected else values.lastIndex
                if (values[index].isFinite()) {
                    val point = Offset(xAt(index), yAt(index))
                    if (selectedTime != null) drawLine(grid.copy(alpha = 0.55f), Offset(point.x, inset),
                        Offset(point.x, size.height - inset), 1.dp.toPx())
                    val pointColor = if (samples[index].source == BeaconSampleSource.BEACON) beaconLine else color
                    drawCircle(pointColor.copy(alpha = 0.18f), (style.markerDp * 2f).dp.toPx(), point)
                    drawCircle(pointColor, style.markerDp.dp.toPx(), point)
                    drawCircle(plotBackground, (style.markerDp * .4f).dp.toPx(), point)
                }
            }
            Canvas(Modifier.matchParentSize()) {
                travelTarget?.let {
                    val inset = style.insetDp.dp.toPx()
                    val tint = if (samples[selected].source == BeaconSampleSource.BEACON) beaconLine else color
                    drawChartTravel(travel.value, it.position, tint, inset, inset,
                        size.width - 2 * inset, size.height - 2 * inset)
                }
            }
            }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (windowMinutes < 60) stringResource(R.string.ui_elapsed_minutes, windowMinutes)
                    else stringResource(R.string.ui_elapsed_hours, windowMinutes / 60),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.heightIn(min = (48 * localDensity.fontScale).dp), contentAlignment = Alignment.CenterEnd) {
                    if (selectedTime != null) TextButton(onClick = hapticClick { selectedTime = null },
                        modifier = Modifier.semantics { contentDescription = returnRecent },
                        contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.cards_latest)) }
                    else Text(stringResource(R.string.traffic_now), style = MaterialTheme.typography.labelSmall, color = grid)
                }
            }
        }
        }
    }
}
