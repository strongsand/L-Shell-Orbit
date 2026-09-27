package com.hurricane.lshell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hurricane.lshell.core.data.TelemetrySample
import com.hurricane.lshell.beacon.BeaconSampleSource
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

private data class SourcedTelemetry(
    val sample: TelemetrySample,
    val source: BeaconSampleSource
)

@Composable
fun TelemetryCharts(samples: List<TelemetrySample>, beaconHistoryRevision: Long? = null) {
    val context = LocalContext.current
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
                        entry.source
                    ) }
            }
        }
    }
    val windowMs = minutes * 60_000L
    val recent = remember(samples, stored, minutes) {
        val live = samples.map { SourcedTelemetry(it, BeaconSampleSource.LOCAL_PHONE) }
        downsampleTelemetry((stored + live)
            .filter { it.sample.timestamp >= System.currentTimeMillis() - windowMs }
            .distinctBy { it.sample.timestamp / 1_000L }.sortedBy { it.sample.timestamp })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SectionHeading("Últimos $minutes minutos", "Toque no gráfico para explorar cada leitura")
        }
        IconButton(onClick = hapticClick { chooseWindow = true }) {
            Icon(Icons.Rounded.Edit, contentDescription = "Alterar período dos gráficos")
        }
    }
    Spacer(Modifier.height(12.dp))
    if (recent.size < 2) {
        Surface(shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text(if (recent.isEmpty()) "Sem histórico de $minutes minutos. Aguardando leituras da antena…"
                else "Aguardando a próxima leitura para desenhar os gráficos…",
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
    MiniChart("Latência POP", "ms", recent.map { it.sample.latencyMs }, recent, windowMs, minutes,
        colors.secondary, colors.secondaryContainer)
    Spacer(Modifier.height(12.dp))
    MiniChart("Perda de pacotes", "%", recent.map { it.sample.dropPercent }, recent, windowMs, minutes,
        if (packetLoss) colors.error else colors.tertiary,
        if (packetLoss) colors.errorContainer else colors.tertiaryContainer)
    Spacer(Modifier.height(12.dp))
    MiniChart("Download", "Mbps", recent.map { it.sample.downloadMbps }, recent, windowMs, minutes,
        colors.primary, colors.primaryContainer)
    Spacer(Modifier.height(12.dp))
    MiniChart("Upload", "Mbps", recent.map { it.sample.uploadMbps }, recent, windowMs, minutes,
        colors.tertiary, colors.tertiaryContainer)
    Spacer(Modifier.height(12.dp))
    val powerSamples = recent.filter { it.sample.powerWatts?.let { watts -> watts.isFinite() && watts > 0f } == true }
    if (powerSamples.size >= 2) {
        MiniChart("Consumo de energia", "W", powerSamples.map { it.sample.powerWatts!! }, powerSamples,
            windowMs, minutes, energyColors.chartLine, energyColors.chartFill)
    } else {
        Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
            color = energyColors.container, contentColor = energyColors.onContainer) {
            Row(Modifier.padding(DashDesign.inset), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Bolt, null)
                Column {
                    Text("Consumo de energia", style = MaterialTheme.typography.titleMedium)
                    Text("Métrica não disponível neste firmware", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (chooseWindow) AlertDialog(
        onDismissRequest = { chooseWindow = false },
        icon = { Icon(Icons.Rounded.Edit, null) },
        title = { Text("Período dos gráficos") },
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
                        Text(if (option < 60) "$option minutos" else if (option == 60) "1 hora"
                            else if (option < 1440) "${option / 60} horas" else "24 horas",
                            Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
                    }
                }
            }
        },
        confirmButton = {}
    )
}

private fun downsampleTelemetry(source: List<SourcedTelemetry>, limit: Int = 720): List<SourcedTelemetry> {
    if (source.size <= limit) return source
    val chunk = kotlin.math.ceil(source.size.toDouble() / limit).toInt()
    return source.chunked(chunk).map { values ->
        val sourceType = if (values.count { it.source == BeaconSampleSource.BEACON } >= values.size / 2f)
            BeaconSampleSource.BEACON else BeaconSampleSource.LOCAL_PHONE
        SourcedTelemetry(TelemetrySample(values.last().sample.timestamp,
            values.map { it.sample.downloadMbps }.finiteAverageOrMissing(),
            values.map { it.sample.uploadMbps }.finiteAverageOrMissing(),
            values.map { it.sample.latencyMs }.finiteAverageOrMissing(),
            values.map { it.sample.dropPercent }.finiteAverageOrMissing(),
            values.mapNotNull { it.sample.powerWatts?.takeIf { watts -> watts.isFinite() && watts > 0f } }
                .takeIf { it.isNotEmpty() }?.average()?.toFloat()), sourceType)
    }
}

private fun List<Float>.finiteAverageOrMissing(): Float =
    filter { it.isFinite() }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: Float.NaN

@Composable
private fun ChartOriginLegend(showLocal: Boolean) {
    Surface(shape = DashDesign.pill, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showLocal) OriginLegendItem("Este dispositivo", MaterialTheme.colorScheme.primary)
            OriginLegendItem("Sincronizado do Beacon", MaterialTheme.colorScheme.onSecondaryContainer)
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
                      windowMs: Long, windowMinutes: Int, color: Color, fillColor: Color) {
    val haptics = rememberDashHaptics()
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    val plotBackground = MaterialTheme.colorScheme.surfaceContainer
    var selectedTime by remember { mutableStateOf<Long?>(null) }
    var chartWidth by remember { mutableFloatStateOf(1f) }
    val chartInset = with(LocalDensity.current) { 6.dp.toPx() }
    val beaconLine = MaterialTheme.colorScheme.onSecondaryContainer
    val windowEnd = samples.last().sample.timestamp
    val windowStart = windowEnd - windowMs
    fun fractionAt(index: Int) = ((samples[index].sample.timestamp - windowStart).toFloat() / windowMs.toFloat()).coerceIn(0f, 1f)
    val peak = values.filter { it.isFinite() }.maxOrNull() ?: 0f
    val ceiling = max(1f, peak) * 1.15f
    val selected = selectedTime?.let { time -> samples.indexOfFirst { it.sample.timestamp >= time }.takeIf { it >= 0 } }
        ?: values.lastIndex
    val shownValue = values[selected]
    val shownTime = if (selectedTime != null || System.currentTimeMillis() - samples.last().sample.timestamp > 60_000L)
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(samples[selected].sample.timestamp)) else "Agora"
    val selectAt by rememberUpdatedState<(Float) -> Unit> { x ->
        val wanted = windowStart + (((x - chartInset) / (chartWidth - chartInset * 2).coerceAtLeast(1f)).coerceIn(0f, 1f) * windowMs).toLong()
        val right = samples.indexOfFirst { it.sample.timestamp >= wanted }
        val index = if (right <= 0) { if (right < 0) samples.lastIndex else 0 }
            else if (wanted - samples[right - 1].sample.timestamp < samples[right].sample.timestamp - wanted) right - 1 else right
        selectedTime = samples[index].sample.timestamp
    }
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.widthIn(min = 110.dp, max = 240.dp)) {
                    MetricValue(if (shownValue.isFinite()) "%.2f".format(shownValue) else "—", unit, color)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(shownTime, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (samples[selected].source == BeaconSampleSource.BEACON)
                        Text("Via Beacon", style = MaterialTheme.typography.labelMedium, color = beaconLine)
                    Text("Pico ${"%.2f".format(peak)} $unit", style = MaterialTheme.typography.labelLarge, color = color)
                    if (selectedTime != null) TextButton(onClick = hapticClick { selectedTime = null },
                        contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Voltar ao vivo") }
                }
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(plotBackground).padding(10.dp)) {
            Canvas(
                Modifier.fillMaxWidth().height(120.dp)
                    .onSizeChanged { chartWidth = it.width.toFloat() }
                    .semantics {
                        contentDescription = "$title, $shownTime, ${"%.2f".format(shownValue)} $unit. Pico ${"%.2f".format(peak)} $unit."
                        customActions = listOf(
                            CustomAccessibilityAction("Leitura anterior") {
                                selectedTime = samples[(selected - 1).coerceAtLeast(0)].sample.timestamp; true
                            },
                            CustomAccessibilityAction("Próxima leitura") {
                                selectedTime = samples[(selected + 1).coerceAtMost(samples.lastIndex)].sample.timestamp; true
                            },
                            CustomAccessibilityAction("Voltar ao vivo") { selectedTime = null; true }
                        )
                    }
                    .pointerInput(Unit) { detectTapGestures { haptics.perform(); selectAt(it.x) } }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { haptics.perform(); selectAt(it.x) },
                            onHorizontalDrag = { change, _ -> selectAt(change.position.x); change.consume() }
                        )
                    }
            ) {
                val inset = 6.dp.toPx()
                val plotWidth = size.width - inset * 2
                val plotHeight = size.height - inset * 2
                fun xAt(index: Int) = inset + fractionAt(index) * plotWidth
                fun yAt(index: Int) = inset + plotHeight * (1f - (values[index] / ceiling).coerceIn(0f, 1f))
                for (fraction in listOf(0f, 0.5f, 1f)) {
                    val y = inset + plotHeight * fraction
                    drawLine(grid.copy(alpha = 0.22f), Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx())
                }
                val validSegments = mutableListOf<MutableList<Int>>()
                values.indices.forEach { index ->
                    if (!values[index].isFinite()) return@forEach
                    if (index == 0 || !values[index - 1].isFinite()) validSegments.add(mutableListOf())
                    validSegments.last() += index
                }
                validSegments.filter { it.size >= 2 }.forEach { segment ->
                    val area = Path().apply {
                        moveTo(xAt(segment.first()), inset + plotHeight)
                        lineTo(xAt(segment.first()), yAt(segment.first()))
                        segment.drop(1).forEach { index -> lineTo(xAt(index), yAt(index)) }
                        lineTo(xAt(segment.last()), inset + plotHeight)
                        close()
                    }
                    drawPath(area, Brush.verticalGradient(
                        listOf(color.copy(alpha = 0.24f), fillColor.copy(alpha = 0.04f)), endY = size.height))
                }
                values.indices.drop(1).forEach { index ->
                    if (!values[index - 1].isFinite() || !values[index].isFinite()) return@forEach
                    val segmentColor = if (samples[index].source == BeaconSampleSource.BEACON) beaconLine else color
                    drawLine(segmentColor, Offset(xAt(index - 1), yAt(index - 1)), Offset(xAt(index), yAt(index)),
                        3.dp.toPx(), StrokeCap.Round)
                }
                val index = if (selectedTime != null) selected else values.lastIndex
                if (values[index].isFinite()) {
                    val point = Offset(xAt(index), yAt(index))
                    if (selectedTime != null) drawLine(grid.copy(alpha = 0.55f), Offset(point.x, inset),
                        Offset(point.x, size.height - inset), 1.dp.toPx())
                    val pointColor = if (samples[index].source == BeaconSampleSource.BEACON) beaconLine else color
                    drawCircle(pointColor.copy(alpha = 0.18f), 10.dp.toPx(), point)
                    drawCircle(pointColor, 5.dp.toPx(), point)
                    drawCircle(plotBackground, 2.dp.toPx(), point)
                }
            }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (windowMinutes < 60) "−$windowMinutes min" else "−${windowMinutes / 60} h",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Agora", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
