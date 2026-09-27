package io.github.strongsand.lshell

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.core.data.TelemetrySample
import io.github.strongsand.lshell.core.model.DishState
import io.github.strongsand.lshell.core.model.DishySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class DiagnosticDestination { HEALTH, ENERGY, TIMELINE, OUTAGES, SINGLE_TEST, EXTREME_TEST, EXTREME_HISTORY }

@Composable
fun DiagnosticsHomeScreen(modifier: Modifier = Modifier, onOpen: (DiagnosticDestination) -> Unit) {
    val entries = listOf(
        Triple(DiagnosticDestination.HEALTH, "Saúde da instalação", Icons.Rounded.HealthAndSafety),
        Triple(DiagnosticDestination.OUTAGES, "Quedas", Icons.Rounded.CloudOff),
        Triple(DiagnosticDestination.TIMELINE, "Timeline", Icons.Rounded.Timeline),
        Triple(DiagnosticDestination.ENERGY, "Energia", Icons.Rounded.Bolt),
        Triple(DiagnosticDestination.SINGLE_TEST, "Teste Único", Icons.Rounded.Speed),
        Triple(DiagnosticDestination.EXTREME_TEST, "Teste Extremo", Icons.Rounded.ElectricBolt),
        Triple(DiagnosticDestination.EXTREME_HISTORY, "Testes Extremos", Icons.Rounded.History)
    )
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Diagnóstico", style = MaterialTheme.typography.headlineLarge)
            Text("Instalação, estabilidade e eventos explicados com os dados da antena.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
        }
        items(entries, key = { it.first }) { (destination, title, icon) ->
            val interaction = remember { MutableInteractionSource() }
            Surface(onClick = hapticClick { onOpen(destination) }, interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().pressMotion(interaction, .98f),
                shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExpressiveIcon(icon, MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
fun InstallationHealthScreen(snapshot: DishySnapshot, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val alerts = snapshot.alerts.getActiveAlertsList()
    val obstruction = snapshot.obstruction.obstructionPercentage
    val ethernet = snapshot.deviceInfo.ethSpeedMbps
    val hasOrientation = snapshot.deviceInfo.boresightAzimuthDeg != 0f ||
        snapshot.deviceInfo.boresightElevationDeg != 0f
    val problem = snapshot.alerts.hasCriticalAlerts || snapshot.state == DishState.THERMAL_SHUTDOWN ||
        snapshot.state == DishState.NO_PINGS || snapshot.state == DishState.NO_DOWNLINK
    val caution = !snapshot.isOnline || alerts.isNotEmpty() || obstruction >= 1f ||
        snapshot.latency.isSnrAboveNoiseFloor == false || (ethernet != null && ethernet < 1000)
    val tone = when { problem -> HealthTone.DANGER; caution -> HealthTone.CAUTION; else -> HealthTone.GOOD }
    val title = when (tone) {
        HealthTone.GOOD -> "Excelente"
        HealthTone.CAUTION -> "Atenção"
        HealthTone.DANGER -> "Problema detectado"
        HealthTone.UNKNOWN -> "Sem leitura"
    }
    val explanation = when {
        !snapshot.isOnline -> "A antena não está respondendo agora."
        snapshot.alerts.hasCriticalAlerts -> alerts.firstOrNull() ?: snapshot.state.displayName
        obstruction >= 1f -> "Obstrução de ${oneDecimal(obstruction)}%."
        ethernet != null && ethernet < 1000 -> "Ethernet negociada em $ethernet Mbps."
        snapshot.latency.isSnrAboveNoiseFloor == false -> "Sinal abaixo do nível de ruído esperado."
        alerts.isNotEmpty() -> alerts.first()
        else -> "Nenhuma condição relevante detectada."
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { DiagnosticHeader("Saúde da instalação", onBack) }
        item {
            val colors = healthColors(tone)
            Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero, color = colors.container,
                contentColor = colors.foreground) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (tone == HealthTone.GOOD) Icons.Rounded.Verified else Icons.Rounded.HealthAndSafety,
                        null, Modifier.size(42.dp))
                    Text(title, style = MaterialTheme.typography.headlineLarge)
                    Text(explanation, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        item {
            DiagnosticCard("Instalação física") {
                DiagnosticStateRow("Campo de visão", snapshot.obstruction.formattedPercentage,
                    obstruction < 1f && !snapshot.obstruction.currentlyObstructed)
                DiagnosticStateRow("Mastro", if (snapshot.alerts.mastNotNearVertical) "Fora da vertical" else "Sem alerta",
                    !snapshot.alerts.mastNotNearVertical)
                InfoRow("Azimute", if (hasOrientation) "${oneDecimal(snapshot.deviceInfo.boresightAzimuthDeg)}°" else "Não informado")
                InfoRow("Elevação", if (hasOrientation) "${oneDecimal(snapshot.deviceInfo.boresightElevationDeg)}°" else "Não informada")
            }
        }
        item {
            DiagnosticCard("Operação") {
                DiagnosticStateRow("Sinal", snapshot.latency.formattedSignal,
                    snapshot.latency.isSnrAboveNoiseFloor)
                DiagnosticStateRow("Temperatura", if (snapshot.alerts.thermalThrottle || snapshot.alerts.thermalShutdown)
                    "Limitação térmica" else "Sem alerta", !snapshot.alerts.thermalThrottle && !snapshot.alerts.thermalShutdown)
                DiagnosticStateRow("Energia", if (snapshot.alerts.powerSupplyThermalThrottle) "Fonte limitada" else "Sem alerta",
                    !snapshot.alerts.powerSupplyThermalThrottle)
                DiagnosticStateRow("Ethernet", ethernet?.let { "$it Mbps" } ?: "Não informada", ethernet?.let { it >= 1000 })
            }
        }
        item {
            DiagnosticCard("Terminal") {
                InfoRow("Estado", snapshot.state.displayName)
                InfoRow("Hardware", snapshot.deviceInfo.hardwareVersion)
                InfoRow("Software", snapshot.deviceInfo.softwareVersion)
                if (alerts.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    alerts.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
fun EnergyScreen(samples: List<TelemetrySample>, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    var readings by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var yesterdayReadings by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    val todayStart = dayStart(System.currentTimeMillis())
    val latestPowerMinute = samples.lastOrNull {
        it.powerWatts?.let { watts -> watts.isFinite() && watts > 0f } == true
    }?.timestamp?.div(60_000L)
    LaunchedEffect(todayStart, latestPowerMinute) {
        val now = System.currentTimeMillis()
        val yesterdayStart = todayStart - 86_400_000L
        val comparableYesterdayEnd = yesterdayStart + (now - todayStart)
        val all = withContext(Dispatchers.IO) {
            HistoryStore(context).use { it.between(yesterdayStart, now + 1_000L) }
                .filter { it.powerWatts?.let { watts -> watts.isFinite() && watts > 0f } == true }
        }
        readings = all.filter { it.timestamp >= todayStart }
        yesterdayReadings = all.filter { it.timestamp in yesterdayStart until comparableYesterdayEnd }
    }
    val currentReadings = remember(readings, samples, todayStart) {
        val livePower = samples.mapNotNull { sample ->
            sample.powerWatts?.takeIf { it.isFinite() && it > 0f }?.let { watts ->
                HistoryEntry(sample.timestamp, "terminal_history", "Potência da antena", powerWatts = watts)
            }
        }
        (readings + livePower).filter { it.timestamp >= todayStart }
            .associateBy { it.timestamp / 1_000L }.values.sortedBy { it.timestamp }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { DiagnosticHeader("Energia", onBack) }
        if (currentReadings.isEmpty()) item {
            Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExpressiveIcon(Icons.Rounded.Bolt, MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("Métrica não disponível neste firmware", style = MaterialTheme.typography.headlineMedium)
                    Text("O histórico da antena não retornou amostras válidas de power_in. Watts e kWh não serão estimados sem dados reais.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else item {
            val summary = remember(currentReadings) { calculateEnergy(currentReadings) }
            val latest = currentReadings.last()
            val currentPower = latest.powerWatts?.takeIf {
                System.currentTimeMillis() - latest.timestamp <= 15_000L
            }
            val yesterday = remember(yesterdayReadings) {
                yesterdayReadings.takeIf { it.size >= 2 }?.let(::calculateEnergy)
            }
            DiagnosticCard("Consumo de hoje") {
                Text(formatEnergy(summary.kwh), style = MaterialTheme.typography.displaySmall)
                Text("em ${duration(summary.monitoredMs)} monitoradas",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                InfoRow("Potência atual", currentPower?.let { "${oneDecimal(it)} W" } ?: "— W")
                InfoRow("Última amostra", clock(latest.timestamp))
                InfoRow("Potência média", "${oneDecimal(summary.average)} W")
                InfoRow("Mínima", "${oneDecimal(summary.minimum)} W")
                InfoRow("Máxima", "${oneDecimal(summary.maximum)} W")
                InfoRow("Pico", "${oneDecimal(summary.maximum)} W às ${clock(summary.peakAt)}")
                yesterday?.takeIf { it.kwh > 0f && it.monitoredMs >= summary.monitoredMs * 4 / 5 }?.let { previous ->
                    val difference = (summary.kwh - previous.kwh) / previous.kwh * 100f
                    InfoRow("Em relação a ontem", "${if (difference <= 0f) "↓" else "↑"} ${oneDecimal(abs(difference))}%")
                }
                SimpleSeriesChart(downsample(currentReadings.mapNotNull { it.powerWatts }, 240))
                Text("Lacunas acima de cinco minutos não entram no consumo calculado.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
fun SmartTimelineScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    var yesterday by rememberSaveable { mutableStateOf(false) }
    var report by remember { mutableStateOf<DailyReport?>(null) }
    LaunchedEffect(yesterday) {
        report = withContext(Dispatchers.IO) {
            val end = if (yesterday) dayStart(System.currentTimeMillis()) else System.currentTimeMillis()
            val start = if (yesterday) end - 86_400_000L else dayStart(end)
            DailyReportGenerator.generate(context, start, end)
        }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            DiagnosticHeader("Timeline", onBack)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!yesterday, onClick = hapticClick { yesterday = false }, label = { Text("Hoje") })
                FilterChip(yesterday, onClick = hapticClick { yesterday = true }, label = { Text("Ontem") })
            }
        }
        val moments = report?.timeline.orEmpty()
        if (moments.isEmpty()) item { DiagnosticEmpty("Nenhum acontecimento relevante no período.") }
        else items(moments, key = { "${it.timestamp}:${it.title}:${it.detail}" }) { moment -> TimelineMomentCard(moment) }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

private data class OutageContext(val outage: ReportInterruption, val before: HistoryEntry?,
                                 val during: HistoryEntry?, val after: HistoryEntry?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutagesScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    var period by rememberSaveable { mutableStateOf("Hoje") }
    var outages by remember { mutableStateOf<List<OutageContext>>(emptyList()) }
    var report by remember { mutableStateOf<DailyReport?>(null) }
    LaunchedEffect(period) {
        val now = System.currentTimeMillis()
        val (start, end) = when (period) {
            "Ontem" -> dayStart(now) - 86_400_000L to dayStart(now)
            "7 dias" -> now - 7L * 86_400_000L to now
            "30 dias" -> now - 30L * 86_400_000L to now
            else -> dayStart(now) to now
        }
        val generated = withContext(Dispatchers.IO) { DailyReportGenerator.generate(context, start, end) }
        val rows = withContext(Dispatchers.IO) { HistoryStore(context).use { it.between(start, end) } }
        report = generated
        outages = generated.interruptions.map { outage ->
            OutageContext(outage,
                rows.filter { (it.kind == "reading" || it.kind == "beacon_history") && it.timestamp < outage.start }.maxByOrNull { it.timestamp },
                rows.filter { (it.kind == "reading" || it.kind == "beacon_history") && it.timestamp in outage.start..outage.end }.minByOrNull { it.timestamp },
                rows.filter { (it.kind == "reading" || it.kind == "beacon_history") && it.timestamp >= outage.end }.minByOrNull { it.timestamp })
        }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            DiagnosticHeader("Quedas", onBack)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Hoje", "Ontem", "7 dias", "30 dias").forEach { option ->
                    FilterChip(period == option, onClick = hapticClick { period = option }, label = { Text(option) })
                }
            }
        }
        item {
            DiagnosticCard(period) {
                Text("${outages.size} queda${if (outages.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.headlineMedium)
                InfoRow("Tempo offline", duration(report?.offlineMs ?: 0L))
                InfoRow("Maior queda", duration(outages.maxOfOrNull { it.outage.durationMs } ?: 0L))
                report?.availabilityPercent?.let { InfoRow("Disponibilidade monitorada", "${twoDecimals(it)}%") }
            }
        }
        if (outages.isEmpty()) item { DiagnosticEmpty("Nenhuma queda registrada neste período.") }
        else items(outages, key = { it.outage.start }) { OutageCard(it) }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun TimelineMomentCard(moment: ReportMoment) {
    var expanded by rememberSaveable(moment.timestamp) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Surface(onClick = hapticClick { expanded = !expanded }, interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().pressMotion(interaction, .98f).animateContentSize(),
        shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ExpressiveIcon(timelineIcon(moment.title), MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(clock(moment.timestamp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(moment.title, style = MaterialTheme.typography.titleMedium)
                if (moment.detail.isNotBlank()) Text(moment.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (expanded) Text("Evento consolidado a partir das leituras salvas no aparelho.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OutageCard(context: OutageContext) {
    var expanded by rememberSaveable(context.outage.start) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val likely = context.during?.dishState?.let { stateName ->
        runCatching { DishState.valueOf(stateName) }.getOrNull()
    }?.takeIf { it != DishState.CONNECTED && it != DishState.UNKNOWN }
        ?.displayName ?: "Causa não determinada"
    Surface(onClick = hapticClick { expanded = !expanded }, interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().pressMotion(interaction, .98f).animateContentSize(),
        shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpressiveIcon(Icons.Rounded.CloudOff, MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer)
                Column(Modifier.weight(1f)) {
                    Text("Queda · ${clock(context.outage.start)}", style = MaterialTheme.typography.titleMedium)
                    Text(duration(context.outage.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (expanded) {
                InfoRow("Antes", context.before?.let { "${oneDecimal(it.latencyMs)} ms · perda ${oneDecimal(it.dropPercent)}%" } ?: "Sem leitura")
                InfoRow("Durante", context.during?.dishState?.replace('_', ' ') ?: "Sem leitura")
                InfoRow("Após", context.after?.dishState?.replace('_', ' ') ?: if (context.outage.resolved) "Conexão restaurada" else "Sem recuperação")
                InfoRow("Obstrução anterior", context.before?.let { "${oneDecimal(it.obstructionPercent)}%" } ?: "Sem leitura")
                InfoRow("Possível causa", likely)
            }
        }
    }
}

@Composable
private fun DiagnosticHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = hapticClick(action = onBack)) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
    }
}

@Composable
private fun DiagnosticCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeading(title)
            content()
        }
    }
}

@Composable
private fun DiagnosticStateRow(label: String, value: String, okay: Boolean?) {
    val colors = healthColors(when (okay) { true -> HealthTone.GOOD; false -> HealthTone.CAUTION; null -> HealthTone.UNKNOWN })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(when (okay) { true -> Icons.Rounded.CheckCircle; false -> Icons.Rounded.Warning; null -> Icons.Rounded.Info }, null,
            Modifier.size(20.dp), tint = colors.foreground)
        Text(label, Modifier.padding(start = 8.dp).weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DiagnosticEmpty(text: String) {
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(text, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class EnergySummary(val kwh: Float, val monitoredMs: Long, val average: Float,
                                 val minimum: Float, val maximum: Float, val peakAt: Long)

private fun calculateEnergy(entries: List<HistoryEntry>): EnergySummary {
    val sorted = entries.filter {
        it.powerWatts?.let { watts -> watts.isFinite() && watts > 0f } == true
    }.sortedBy { it.timestamp }
    var wh = 0.0
    var monitored = 0L
    sorted.zipWithNext().forEach { (a, b) ->
        val gap = b.timestamp - a.timestamp
        if (gap in 1..300_000L) {
            wh += ((a.powerWatts!! + b.powerWatts!!) / 2.0) * gap / 3_600_000.0
            monitored += gap
        }
    }
    val peak = sorted.maxByOrNull { it.powerWatts!! }
    val values = sorted.map { it.powerWatts!! }
    return EnergySummary((wh / 1_000.0).toFloat(), monitored,
        values.average().toFloat(), values.min(), values.max(), peak?.timestamp ?: 0L)
}

@Composable
private fun SimpleSeriesChart(values: List<Float>) {
    if (values.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceContainer
    val max = values.max().coerceAtLeast(1f)
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        drawRoundRect(background, cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = 12.dp.toPx() + (size.width - 24.dp.toPx()) * index / values.lastIndex
            val y = size.height - 12.dp.toPx() - (size.height - 24.dp.toPx()) * value / max
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

private fun timelineIcon(title: String) = when {
    title.contains("conexão", true) -> Icons.Rounded.CloudOff
    title.contains("latência", true) -> Icons.Rounded.Speed
    title.contains("perda", true) -> Icons.Rounded.NetworkCheck
    title.contains("reinici", true) -> Icons.Rounded.RestartAlt
    title.contains("Ethernet", true) -> Icons.Rounded.SettingsEthernet
    else -> Icons.Rounded.Info
}

private fun dayStart(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
private fun clock(time: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))
private fun oneDecimal(value: Float) = String.format(Locale.getDefault(), "%.1f", value)
private fun twoDecimals(value: Float) = String.format(Locale.getDefault(), "%.2f", value)
private fun formatEnergy(kwh: Float): String = if (kwh < 1f)
    "${oneDecimal(kwh * 1_000f)} Wh" else "${twoDecimals(kwh)} kWh"
private fun downsample(values: List<Float>, maximum: Int): List<Float> {
    if (values.size <= maximum) return values
    val step = values.size.toDouble() / maximum
    return List(maximum) { index -> values[(index * step).toInt().coerceAtMost(values.lastIndex)] }
}
private fun duration(ms: Long): String = when {
    ms < 60_000L -> "${ms / 1_000L} s"
    ms < 3_600_000L -> "${ms / 60_000L} min ${ms / 1_000L % 60L} s"
    else -> "${ms / 3_600_000L}h ${ms / 60_000L % 60L}min"
}
