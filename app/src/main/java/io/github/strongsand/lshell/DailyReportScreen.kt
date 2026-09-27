package io.github.strongsand.lshell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

@Composable
fun DailyReportsScreen(modifier: Modifier = Modifier, initialEnd: Long? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    var reports by remember { mutableStateOf<List<DailyReport>>(emptyList()) }
    var selectedEnd by remember(initialEnd) { mutableStateOf(initialEnd) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(selectedEnd) {
        loading = true
        reports = withContext(Dispatchers.IO) {
            val store = DailyReportStore(context)
            selectedEnd?.let { end ->
                store.refreshIfStale(end) { start, reportEnd ->
                    DailyReportGenerator.generate(context, start, reportEnd)
                }
            }
            store.all()
        }
        loading = false
    }
    val selected = reports.firstOrNull { it.end == selectedEnd }
    if (loading && selectedEnd != null) {
        ReportEmpty("Atualizando relatório com os dados disponíveis…")
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
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Voltar")
                    }
                    Column {
                        Text("Relatórios", style = MaterialTheme.typography.headlineLarge)
                        Text("A história da sua conexão, salva localmente",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (loading) item { ReportEmpty("Carregando relatórios…") }
            else if (reports.isEmpty()) item {
                ReportEmpty("O primeiro relatório aparecerá depois do horário configurado. Mantenha o monitoramento ativo para obter um período mais completo.")
            }
            items(reports, key = { it.end }) { report ->
                val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Surface(onClick = hapticClick { selectedEnd = report.end }, interactionSource = interaction,
                    modifier = Modifier.fillMaxWidth().pressMotion(interaction), shape = DashDesign.section,
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                            Surface(shape = DashDesign.cookie,
                                color = if (report.interruptions.isEmpty()) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.tertiaryContainer) {
                                Icon(if (report.interruptions.isEmpty()) Icons.Rounded.CheckCircle else Icons.Rounded.CloudOff,
                                    null, Modifier.padding(13.dp))
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(day(report.end), style = MaterialTheme.typography.titleLarge)
                            Text(report.summary, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(report.availabilityPercent?.let { "${number(it, 2)}% disponível" }
                                ?: "Disponibilidade indisponível", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun DailyReportDetail(report: DailyReport, previous: DailyReport?, modifier: Modifier, onBack: () -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            IconButton(onClick = hapticClick(action = onBack), modifier = Modifier.padding(top = 6.dp)) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Voltar aos relatórios")
            }
            Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero,
                color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Assessment, null, Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Sua Starlink hoje", style = MaterialTheme.typography.headlineMedium)
                    Text(report.summary, style = MaterialTheme.typography.titleMedium)
                    Text("${dateTime(report.start)} → ${dateTime(report.end)}",
                        style = MaterialTheme.typography.bodySmall)
                    if (report.createdAt - report.end > 15 * 60_000L)
                        Text("Gerado com ${duration(report.createdAt - report.end)} de atraso",
                            style = MaterialTheme.typography.labelMedium)
                    report.availabilityPercent?.let {
                        Text("${number(it, 2)}%", style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold)
                        Text("disponibilidade aproximada no período monitorado")
                    }
                }
            }
        }
        item {
            ReportSection("Resumo do período") {
                MetricRow("Tempo monitorado", duration(report.monitoredMs), "Online", duration(report.onlineMs))
                MetricRow("Offline detectado", duration(report.offlineMs), "Falhas", report.interruptions.size.toString())
                MetricRow("Alertas", report.alerts.size.toString(), "Amostras", report.sampleCount.toString())
                if (report.monitoredMs < report.end - report.start)
                    Text("Existem lacunas sem monitoramento. Elas não foram classificadas como tempo offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (previous != null) item { ComparisonCard(report, previous) }
        item {
            ReportSection("Falhas de conexão") {
                if (report.interruptions.isEmpty()) Text("Nenhuma interrupção registrada.")
                report.interruptions.forEach { interruption ->
                    MetricRow(time(interruption.start), interruption.reason,
                        if (interruption.resolved) "Recuperada" else "Sem recuperação registrada",
                        duration(interruption.durationMs))
                }
                report.interruptions.maxByOrNull { it.durationMs }?.let {
                    Text("Maior interrupção: ${duration(it.durationMs)}",
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        item {
            ReportSection("Tráfego observado") {
                Text("Estes números representam uso instantâneo da conexão, não a capacidade medida por um teste de velocidade.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                MetricStats("Download durante tráfego ativo", report.trafficDownload, "Mbps")
                MetricStats("Upload durante tráfego ativo", report.trafficUpload, "Mbps")
                ReportChart(report.trafficChart, "Tráfego de download")
            }
        }
        item {
            ReportSection("Latência") {
                MetricStats("Latência POP", report.latency, "ms", showP95 = true)
                Text("Tempo amostrado acima de 100 ms: ${duration(report.latencyAbove100Ms)}")
                ReportChart(report.latencyChart, "Latência")
            }
        }
        item {
            ReportSection("Perda de pacotes") {
                MetricStats("Perda", report.packetLoss, "%")
                MetricRow("Períodos com perda elevada", report.packetLossPeriods.toString(),
                    "Tempo acumulado", duration(report.packetLossDurationMs))
                ReportChart(report.lossChart, "Perda de pacotes")
                EventTimelineGraph(report.timeline.filter { it.title == "Perda elevada de pacotes" },
                    report.start, report.end, "Ocorrências de perda elevada")
            }
        }
        item {
            ReportSection("Campo de visão") {
                MetricStats("Obstrução", report.obstruction, "%")
                if (report.obstruction?.maximum?.let { it < 1f } == true)
                    Text("A visada permaneceu excelente nas leituras disponíveis.")
                ReportChart(report.obstructionChart, "Obstrução")
            }
        }
        item {
            ReportSection("Rede e hardware") {
                if (report.hardwareEvents.isEmpty()) Text("Nenhuma reinicialização ou mudança de Ethernet detectada.")
                report.hardwareEvents.forEach { Text("${time(it.timestamp)} — ${it.title} ${it.detail}") }
                Text("A temperatura aparece pelos alertas do terminal. A potência aparece quando o firmware fornece amostras de powerIn.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            ReportSection("Alertas do período") {
                if (report.alerts.isEmpty()) Text("Nenhum alerta registrado.")
                else {
                    EventTimelineGraph(report.alerts, report.start, report.end, "Distribuição dos alertas")
                    EventBreakdown(report.alerts)
                }
            }
        }
        item {
            ReportSection("Linha do tempo") {
                if (report.timeline.isEmpty()) Text("Nenhum acontecimento relevante registrado.")
                else {
                    EventTimelineGraph(report.timeline, report.start, report.end, "Eventos ao longo do período")
                    Text("Destaques", style = MaterialTheme.typography.titleMedium)
                    report.timeline.sortedWith(compareByDescending<ReportMoment> {
                        eventPriority(it.title)
                    }.thenByDescending { it.timestamp }).take(3).forEach { moment ->
                        MetricRow(time(moment.timestamp), moment.title, "Detalhe",
                            moment.detail.ifBlank { "Registrado" })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ComparisonCard(current: DailyReport, previous: DailyReport) {
    ReportSection("Comparação com o relatório anterior") {
        ComparisonRow("Disponibilidade", current.availabilityPercent, previous.availabilityPercent, "%")
        ComparisonRow("Latência média", current.latency?.average, previous.latency?.average, " ms", lowerIsBetter = true)
        MetricRow("Interrupções", current.interruptions.size.toString(), "Anterior", previous.interruptions.size.toString())
    }
}

@Composable
private fun ComparisonRow(label: String, current: Float?, previous: Float?, unit: String, lowerIsBetter: Boolean = false) {
    if (current == null || previous == null) {
        Text("$label: comparação indisponível", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val delta = current - previous
    val arrow = if (delta > 0f) "↑" else if (delta < 0f) "↓" else "="
    val favorable = if (lowerIsBetter) delta <= 0f else delta >= 0f
    Text("$label  ${number(current, 1)}$unit  $arrow ${number(kotlin.math.abs(delta), 1)}$unit",
        color = if (favorable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.titleMedium)
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
    if (metric == null) Text("Sem amostras disponíveis", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else {
        MetricRow("Média", "${number(metric.average)} $unit", "Mediana", "${number(metric.median)} $unit")
        MetricRow("Mínima", "${number(metric.minimum)} $unit às ${time(metric.minimumAt)}",
            "Máxima", "${number(metric.maximum)} $unit às ${time(metric.maximumAt)}")
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
private fun ReportChart(values: List<ReportChartBucket>, description: String) {
    val localLine = MaterialTheme.colorScheme.primary
    val beaconLine = MaterialTheme.colorScheme.secondary
    val mixedLine = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val samples = remember(values) { reportChartSamples(values) }
    val scale = remember(values) { reportChartScale(values.map { it.value }) }
    val sampleCount = remember(samples) { samples.sumOf { it.size } }
    if (samples.isEmpty() || scale == null) {
        Text("Gráfico indisponível por falta de amostras.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section, color = background) {
        Canvas(Modifier.fillMaxWidth().height(132.dp).clip(DashDesign.section)) {
            val horizontalInset = 14.dp.toPx().coerceAtMost(size.width / 4f)
            val verticalInset = 14.dp.toPx().coerceAtMost(size.height / 4f)
            val plotLeft = horizontalInset
            val plotRight = (size.width - horizontalInset).coerceAtLeast(plotLeft)
            val plotTop = verticalInset
            val plotBottom = (size.height - verticalInset).coerceAtLeast(plotTop)
            val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
            val lastIndex = values.lastIndex.coerceAtLeast(1)

            fun point(sample: ReportChartSample): Offset {
                val x = if (values.size <= 1) (plotLeft + plotRight) / 2f
                else plotLeft + sample.index.toFloat() / lastIndex * plotWidth
                val ratio = if (scale.constant) .5f
                else ((sample.bucket.value!! - scale.minimum) / scale.range).coerceIn(0f, 1f)
                return Offset(x, plotBottom - ratio * plotHeight)
            }

            fun color(origin: ReportBucketOrigin): Color = when (origin) {
                ReportBucketOrigin.BEACON -> beaconLine
                ReportBucketOrigin.MIXED -> mixedLine
                else -> localLine
            }

            clipRect(0f, 0f, size.width, size.height) {
                repeat(3) { index ->
                    val y = plotTop + plotHeight * (index + 1) / 4f
                    drawLine(grid.copy(alpha = .38f), Offset(plotLeft, y), Offset(plotRight, y),
                        1.dp.toPx(), StrokeCap.Round)
                }

                samples.forEach { segment ->
                    val points = segment.map(::point)
                    if (points.size == 1) {
                        val pointColor = color(segment.first().bucket.origin)
                        drawCircle(pointColor.copy(alpha = .22f), 7.dp.toPx(), points.first())
                        drawCircle(pointColor, 3.5.dp.toPx(), points.first())
                        return@forEach
                    }
                    val segmentOrigin = when {
                        segment.any { it.bucket.origin == ReportBucketOrigin.MIXED } -> ReportBucketOrigin.MIXED
                        else -> segment.first().bucket.origin
                    }
                    val segmentColor = color(segmentOrigin)
                    val area = Path().apply {
                        moveTo(points.first().x, plotBottom)
                        lineTo(points.first().x, points.first().y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                        lineTo(points.last().x, plotBottom)
                        close()
                    }
                    drawPath(area, Brush.verticalGradient(
                        0f to segmentColor.copy(alpha = .14f),
                        1f to Color.Transparent,
                        startY = plotTop,
                        endY = plotBottom
                    ))
                    points.zipWithNext().forEachIndexed { index, (from, to) ->
                        val fromOrigin = segment[index].bucket.origin
                        val toOrigin = segment[index + 1].bucket.origin
                        val edgeOrigin = when {
                            fromOrigin == ReportBucketOrigin.MIXED || toOrigin == ReportBucketOrigin.MIXED ->
                                ReportBucketOrigin.MIXED
                            fromOrigin == toOrigin -> fromOrigin
                            else -> toOrigin
                        }
                        val edgeColor = color(edgeOrigin)
                        drawLine(edgeColor.copy(alpha = .20f), from, to, 5.dp.toPx(), StrokeCap.Round)
                        drawLine(edgeColor, from, to, 2.5.dp.toPx(), StrokeCap.Round)
                    }
                    if (sampleCount <= 8) points.forEachIndexed { index, point ->
                        val pointColor = color(segment[index].bucket.origin)
                        drawCircle(background, 4.dp.toPx(), point)
                        drawCircle(pointColor, 2.5.dp.toPx(), point)
                    }
                }
            }
        }
    }
    val coverage = remember(values) { reportChartCoverage(values) }
    Text("$description • ${coverage.filled}/${coverage.total} intervalos • ${number(coverage.percent)}% de cobertura",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val origins = values.filter { it.value?.isFinite() == true }.map { it.origin }.toSet()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ReportBucketOrigin.LOCAL in origins) ReportOriginLegend("Local", localLine)
        if (ReportBucketOrigin.BEACON in origins) ReportOriginLegend("L-Shell Beacon", beaconLine)
        if (ReportBucketOrigin.MIXED in origins) ReportOriginLegend("Local + Beacon", mixedLine)
    }
    if (coverage.filled < coverage.total) Text("Lacunas indicam intervalos sem dados válidos.",
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
                "${events.size} ocorrências • ${buckets.size} grupos"
            else "${events.size} ocorrência${if (events.size == 1) "" else "s"}"
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
        if (index == 0 || values[index - 1].value?.isFinite() != true) segments.add(mutableListOf())
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

private fun reportTimelineTone(event: ReportMoment): Int = when {
    event.title.contains("perda", true) -> 3
    event.title.contains("conexão", true) -> 2
    event.title.contains("latência", true) -> 1
    else -> 0
}

@Composable
private fun EventBreakdown(events: List<ReportMoment>) {
    val grouped = events.groupingBy { it.title }.eachCount().entries.sortedByDescending { it.value }
    val maximum = grouped.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
    grouped.take(5).forEach { (title, count) ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(count.toString(), style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(progress = { count.toFloat() / maximum },
                modifier = Modifier.fillMaxWidth().height(8.dp), trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        }
    }
    if (grouped.size > 5) Text("Mais ${grouped.size - 5} tipos agrupados no gráfico.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun eventPriority(title: String): Int = when {
    title.contains("conexão perdida", true) -> 4
    title.contains("alerta", true) -> 3
    title.contains("perda", true) || title.contains("latência", true) -> 2
    else -> 1
}

@Composable
private fun ReportEmpty(message: String) {
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero,
        color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Schedule, null, Modifier.size(42.dp))
            Text(message, textAlign = TextAlign.Center)
        }
    }
}

private fun day(value: Long) = SimpleDateFormat("dd 'de' MMMM", Locale.getDefault()).format(Date(value))
private fun time(value: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(value))
private fun dateTime(value: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
private fun number(value: Float, decimals: Int = 1) = String.format(Locale.getDefault(), "%.${decimals}f", value)
private fun duration(ms: Long): String = when {
    ms < 60_000L -> "${ms / 1_000L} s"
    ms < 3_600_000L -> "${ms / 60_000L} min ${ms / 1_000L % 60L} s"
    else -> "${ms / 3_600_000L} h ${ms / 60_000L % 60L} min"
}
