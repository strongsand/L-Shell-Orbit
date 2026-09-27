package io.github.strongsand.lshell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.core.model.DishySnapshot
import io.github.strongsand.lshell.core.network.grpc.RouterClientInfo
import io.github.strongsand.lshell.core.network.grpc.RouterGrpcClient
import io.github.strongsand.lshell.beacon.BeaconSampleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

enum class MetricDetailKind { DOWNLOAD, UPLOAD, LATENCY, SIGNAL }

@Composable
fun MetricDetailScreen(kind: MetricDetailKind, snapshot: DishySnapshot, modifier: Modifier = Modifier,
                       onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var clients by remember { mutableStateOf<List<RouterClientInfo>>(emptyList()) }
    var routerMessage by remember { mutableStateOf<String?>(null) }
    var hours by rememberSaveable { mutableIntStateOf(1) }
    val latestMinute = snapshot.timestamp / 60_000L
    LaunchedEffect(kind, hours, latestMinute) {
        val cutoff = System.currentTimeMillis() - hours * 3_600_000L
        entries = withContext(Dispatchers.IO) {
            HistoryStore(context).use { it.between(cutoff, System.currentTimeMillis() + 1_000L) }
                .filter { item -> item.kind == "reading" || item.kind == "terminal_history" || item.kind == "beacon_history" }
                .distinctBy { it.timestamp / 1_000L }
        }
        if (kind == MetricDetailKind.DOWNLOAD || kind == MetricDetailKind.UPLOAD) {
            RouterGrpcClient().fetch().fold(
                onSuccess = { info ->
                    clients = mergeClientSnapshots(clients, info.clients)
                    routerMessage = null
                },
                onFailure = { routerMessage = "O roteador não respondeu agora." }
            )
        }
    }
    val title = when (kind) {
        MetricDetailKind.DOWNLOAD -> "Download"
        MetricDetailKind.UPLOAD -> "Upload"
        MetricDetailKind.LATENCY -> "Latência"
        MetricDetailKind.SIGNAL -> "Qualidade do sinal"
    }
    val icon = when (kind) {
        MetricDetailKind.DOWNLOAD -> Icons.Rounded.Download
        MetricDetailKind.UPLOAD -> Icons.Rounded.Upload
        MetricDetailKind.LATENCY -> Icons.Rounded.Speed
        MetricDetailKind.SIGNAL -> Icons.Rounded.SignalCellularAlt
    }
    val values = when (kind) {
        MetricDetailKind.DOWNLOAD -> entries.map { it.downloadMbps }
        MetricDetailKind.UPLOAD -> entries.map { it.uploadMbps }
        MetricDetailKind.LATENCY -> entries.map { it.latencyMs }
        MetricDetailKind.SIGNAL -> entries.map { it.dropPercent }
    }
    val unit = when (kind) {
        MetricDetailKind.DOWNLOAD, MetricDetailKind.UPLOAD -> "Mbps"
        MetricDetailKind.LATENCY -> "ms"
        MetricDetailKind.SIGNAL -> "% de perda"
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = hapticClick(action = onBack)) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineLarge)
                    Text("Leituras reais salvas no aparelho", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ExpressiveIcon(icon, MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeading("Histórico", "Última${if (hours == 1) " hora" else "s $hours horas"}")
                        Spacer(Modifier.weight(1f))
                        PeriodMenu(hours) { hours = it }
                    }
                    val plottedValues = downsample(values)
                    val plottedSources = downsampleSources(entries.map { it.source })
                    MetricHistoryChart(plottedValues, plottedSources, MaterialTheme.colorScheme.primary)
                    if (entries.any { it.source == BeaconSampleSource.BEACON }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (entries.any { it.source == BeaconSampleSource.LOCAL_PHONE })
                                MetricOriginLegend("Este dispositivo", MaterialTheme.colorScheme.primary)
                            MetricOriginLegend("Via Beacon", MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    val valid = values.filter { it.isFinite() && it >= 0f }
                    AnimatedContent(valid.isNotEmpty(), label = "metric summary") { available ->
                        if (available) AdaptiveStatPair("Média", "${one(valid.average().toFloat())} $unit",
                            "Pico", "${one(valid.max())} $unit")
                        else Text("Ainda não há leituras neste período.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (kind == MetricDetailKind.SIGNAL) item { SignalExplanation(snapshot, entries) }
        if (kind == MetricDetailKind.DOWNLOAD || kind == MetricDetailKind.UPLOAD) {
            item {
                SectionHeading("Dispositivos", "Tráfego acumulado informado pelo roteador")
                routerMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            val ordered = clients.sortedByDescending {
                if (kind == MetricDetailKind.DOWNLOAD) it.receivedBytes else it.sentBytes
            }
            val largest = ordered.maxOfOrNull {
                if (kind == MetricDetailKind.DOWNLOAD) it.receivedBytes else it.sentBytes
            }?.coerceAtLeast(1L) ?: 1L
            if (ordered.isEmpty()) item {
                Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text("Nenhum contador de dispositivo disponível.", Modifier.padding(18.dp))
                }
            } else items(ordered.take(12), key = { it.mac + it.name }) { client ->
                val bytes = if (kind == MetricDetailKind.DOWNLOAD) client.receivedBytes else client.sentBytes
                DeviceTrafficRow(client, bytes, largest)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun PeriodMenu(hours: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = hapticClick { open = true }) {
            Text(if (hours == 1) "1 h" else "$hours h")
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }) {
            listOf(1, 3, 6, 12, 24).forEach { option ->
                DropdownMenuItem(text = { Text(if (option == 1) "1 hora" else "$option horas") },
                    onClick = hapticClick { onSelect(option); open = false })
            }
        }
    }
}

@Composable
private fun MetricHistoryChart(values: List<Float>, sources: List<BeaconSampleSource>, color: Color) {
    val valid = values.filter { it.isFinite() && it >= 0f }
    if (valid.size < 2) {
        Surface(Modifier.fillMaxWidth().height(150.dp), shape = DashDesign.section,
            color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(contentAlignment = Alignment.Center) { Text("Aguardando dados para o gráfico") }
        }
        return
    }
    var entered by remember(values) { mutableStateOf(false) }
    LaunchedEffect(values) { entered = true }
    val reveal by animateFloatAsState(if (entered) 1f else 0f,
        spring(dampingRatio = .76f, stiffness = 180f), label = "chart reveal")
    val background = MaterialTheme.colorScheme.surfaceContainer
    val grid = MaterialTheme.colorScheme.outlineVariant
    val beaconColor = MaterialTheme.colorScheme.onSecondaryContainer
    val max = valid.max().coerceAtLeast(1f)
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        drawRoundRect(background, cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()))
        repeat(3) { line ->
            val y = size.height * (line + 1) / 4f
            drawLine(grid, Offset(12.dp.toPx(), y), Offset(size.width - 12.dp.toPx(), y), 1.dp.toPx())
        }
        val endX = size.width * reveal
        clipRect(right = endX) {
            values.indices.drop(1).forEach { index ->
                if (!values[index - 1].isFinite() || !values[index].isFinite()) return@forEach
                val segmentColor = if (sources.getOrNull(index) == BeaconSampleSource.BEACON) beaconColor else color
                drawLine(segmentColor, Offset(
                    12.dp.toPx() + (size.width - 24.dp.toPx()) * (index - 1) / values.lastIndex.coerceAtLeast(1),
                    size.height - 14.dp.toPx() - (size.height - 28.dp.toPx()) *
                        (values[index - 1].coerceAtLeast(0f) / max)
                ), Offset(
                    12.dp.toPx() + (size.width - 24.dp.toPx()) * index / values.lastIndex.coerceAtLeast(1),
                    size.height - 14.dp.toPx() - (size.height - 28.dp.toPx()) *
                        (values[index].coerceAtLeast(0f) / max)
                ), 3.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun MetricOriginLegend(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(9.dp), shape = DashDesign.cookie, color = color) {}
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = DashDesign.pill, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(13.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun DeviceTrafficRow(client: RouterClientInfo, bytes: Long, largest: Long) {
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ExpressiveIcon(if (client.interfaceName == "Ethernet") Icons.Rounded.Lan else Icons.Rounded.Wifi,
                MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.weight(1f)) {
                Text(client.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(client.interfaceName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(formatBytes(bytes), style = MaterialTheme.typography.titleMedium)
        }
        LinearProgressIndicator(progress = { bytes.coerceAtLeast(0L).toFloat() / largest.toFloat() },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        }
    }
}

@Composable
private fun SignalExplanation(snapshot: DishySnapshot, entries: List<HistoryEntry>) {
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("Leitura do enlace", "O terminal não fornece um valor contínuo de SNR")
            StatPill("Sinal acima do ruído", snapshot.latency.formattedSignal, Modifier.fillMaxWidth())
            AdaptiveStatPair("Perda atual", snapshot.latency.formattedDropRate,
                "Obstrução", snapshot.obstruction.formattedPercentage)
            val obstructed = entries.count { it.obstructionPercent >= 1f }
            Text(if (obstructed == 0) "Nenhuma leitura com obstrução relevante no período."
                else "$obstructed leituras apresentaram ao menos 1% de obstrução.",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AdaptiveStatPair(firstLabel: String, firstValue: String, secondLabel: String, secondValue: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth / LocalDensity.current.fontScale < 300.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill(firstLabel, firstValue, Modifier.fillMaxWidth())
                StatPill(secondLabel, secondValue, Modifier.fillMaxWidth())
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatPill(firstLabel, firstValue, Modifier.weight(1f))
            StatPill(secondLabel, secondValue, Modifier.weight(1f))
        }
    }
}

private fun one(value: Float) = String.format(Locale.getDefault(), "%.1f", value)
private fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f GB", value / 1_000_000_000)
        value >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", value / 1_000_000)
        value >= 1_000 -> String.format(Locale.getDefault(), "%.1f kB", value / 1_000)
        else -> "${value.toLong()} B"
    }
}

private fun downsample(values: List<Float>, limit: Int = 300): List<Float> {
    if (values.size <= limit) return values
    val chunk = kotlin.math.ceil(values.size.toDouble() / limit).toInt()
    return values.chunked(chunk).map { group ->
        group.filter { it.isFinite() && it >= 0f }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: Float.NaN
    }
}

private fun downsampleSources(values: List<BeaconSampleSource>, limit: Int = 300): List<BeaconSampleSource> {
    if (values.size <= limit) return values
    val chunk = kotlin.math.ceil(values.size.toDouble() / limit).toInt()
    return values.chunked(chunk).map { group ->
        if (group.count { it == BeaconSampleSource.BEACON } >= group.size / 2f)
            BeaconSampleSource.BEACON else BeaconSampleSource.LOCAL_PHONE
    }
}

private fun mergeClientSnapshots(previous: List<RouterClientInfo>, current: List<RouterClientInfo>): List<RouterClientInfo> =
    (previous + current).groupBy { it.mac.ifBlank { it.ipv4.ifBlank { it.name } } }.map { (_, versions) ->
        val newest = versions.last()
        newest.copy(
            receivedBytes = versions.maxOf { it.receivedBytes },
            sentBytes = versions.maxOf { it.sentBytes },
            active = versions.any { it.active }
        )
    }.sortedWith(compareByDescending<RouterClientInfo> { it.active }.thenBy { it.name })
