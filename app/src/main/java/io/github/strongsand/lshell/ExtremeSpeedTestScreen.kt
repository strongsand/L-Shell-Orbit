package io.github.strongsand.lshell

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.core.model.DishySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

data class ExtremeRun(
    val index: Int,
    val timestamp: Long,
    val downloadMbps: Float,
    val uploadMbps: Float,
    val latencyMs: Float?,
    val packetLossPercent: Float,
    val obstructionPercent: Float,
    val dishState: String,
    val alerts: List<String>,
    val durationMs: Long,
    val estimatedBytes: Long,
    val powerWatts: Float? = null,
    val failure: String? = null
)

data class ExtremeSession(val id: Long, val requestedTests: Int, val startedAt: Long,
                          val endedAt: Long, val runs: List<ExtremeRun>)

private class ExtremeSessionStore(context: Context) {
    private val directory = File(context.filesDir, "extreme_tests").apply { mkdirs() }
    fun save(session: ExtremeSession) {
        val target = File(directory, "extreme_${session.id}.json")
        val temporary = File(directory, "extreme_${session.id}.tmp")
        temporary.writeText(session.toJson().toString())
        if (!temporary.renameTo(target)) { target.writeText(temporary.readText()); temporary.delete() }
        directory.listFiles { file -> file.extension == "json" }.orEmpty()
            .sortedByDescending { it.lastModified() }.drop(30).forEach { it.delete() }
    }
    fun all(): List<ExtremeSession> = directory.listFiles { file -> file.extension == "json" }.orEmpty()
        .mapNotNull { runCatching { sessionFromJson(JSONObject(it.readText())) }.getOrNull() }
        .sortedByDescending { it.startedAt }
}

@Composable
fun ExtremeSpeedTestScreen(snapshot: DishySnapshot, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberDashHaptics()
    val currentSnapshot by rememberUpdatedState(snapshot)
    var count by rememberSaveable { mutableIntStateOf(5) }
    var running by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var runs by remember { mutableStateOf<List<ExtremeRun>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableStateOf<ExtremeSession?>(null) }

    fun start() {
        running = true; progress = 0; runs = emptyList(); error = null; completed = null
        scope.launch {
            val started = System.currentTimeMillis()
            try {
                repeat(count) { index ->
                    val runStarted = System.currentTimeMillis()
                    val run = try {
                        val result = SpeedTestClient.router()
                        val elapsed = (System.currentTimeMillis() - runStarted).coerceAtLeast(1L)
                        val after = currentSnapshot
                        val averageMbps = (result.downloadMbps + result.uploadMbps).coerceAtLeast(0f)
                        val estimatedBytes = (averageMbps * 1_000_000.0 / 8.0 * elapsed / 1_000.0).toLong()
                        ExtremeRun(index + 1, runStarted, result.downloadMbps, result.uploadMbps,
                            result.latencyMs ?: after.latency.popPingLatencyMs.takeIf { it > 0f },
                            after.latency.popPingDropRate * 100f, after.obstruction.obstructionPercentage,
                            after.state.name, after.alerts.getActiveAlertsList(), elapsed, estimatedBytes)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val after = currentSnapshot
                        ExtremeRun(index + 1, runStarted, 0f, 0f,
                            after.latency.popPingLatencyMs.takeIf { it > 0f },
                            after.latency.popPingDropRate * 100f, after.obstruction.obstructionPercentage,
                            after.state.name, after.alerts.getActiveAlertsList(),
                            (System.currentTimeMillis() - runStarted).coerceAtLeast(1L), 0L,
                            failure = e.message ?: "Teste sem resultado")
                    }
                    runs = runs + run
                    withContext(Dispatchers.IO) {
                        HistoryStore(context).use { it.addReading(currentSnapshot) }
                    }
                    progress = index + 1
                    haptics.perform(DashFeedback.TAP)
                    if (index < count - 1) delay(1_500L)
                }
                val session = ExtremeSession(started, count, started, System.currentTimeMillis(), runs)
                withContext(Dispatchers.IO) {
                    ExtremeSessionStore(context).save(session)
                    HistoryStore(context).use { history ->
                        val failed = runs.count { it.failure != null }
                        val peakLoss = runs.maxOfOrNull { it.packetLossPercent } ?: 0f
                        history.addEvent("Teste Extremo concluído: ${runs.size} testes" +
                            (if (failed > 0) " · $failed sem resultado" else "") +
                            (if (peakLoss >= 1f) " · perda máxima ${one(peakLoss)}%" else ""), session.endedAt)
                    }
                }
                completed = session
                haptics.perform(DashFeedback.CONFIRM)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = e.message ?: "Não foi possível concluir o Teste Extremo."
                haptics.perform(DashFeedback.WARNING)
            } finally { running = false }
        }
    }

    if (completed != null) ExtremeResultContent(completed!!, modifier, onBack)
    else LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ExtremeHeader("Teste Extremo", onBack) }
        item {
            Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero,
                color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.ElectricBolt, null, Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Teste repetido de estabilidade", style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Executa o mesmo teste do Teste Único várias vezes para comparar os resultados.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Quantidade de testes", style = MaterialTheme.typography.titleLarge)
                    Text(count.toString(), style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary)
                    Slider(value = count.toFloat(), onValueChange = hapticSliderValue(count) { count = it },
                        valueRange = 5f..15f, steps = 9, enabled = !running)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("5", style = MaterialTheme.typography.labelMedium)
                        Text("15", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        if (running) item {
            Surface(Modifier.fillMaxWidth().animateContentSize(), shape = DashDesign.section,
                color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Teste ${(progress + 1).coerceAtMost(count)} de $count", style = MaterialTheme.typography.titleLarge)
                    LinearProgressIndicator(progress = { progress.toFloat() / count }, Modifier.fillMaxWidth())
                    if (runs.isNotEmpty()) Text("Último: ${one(runs.last().downloadMbps)} Mbps ↓ · ${one(runs.last().uploadMbps)} Mbps ↑")
                }
            }
        }
        error?.let { message -> item {
            Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
                color = MaterialTheme.colorScheme.errorContainer) {
                Text(message, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        } }
        item {
            val interaction = remember { MutableInteractionSource() }
            Button(onClick = hapticClick(DashFeedback.SPECIAL) { confirm = true }, enabled = !running,
                interactionSource = interaction, modifier = Modifier.fillMaxWidth().pressMotion(interaction, .97f)) {
                Text("Iniciar Teste Extremo")
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        icon = { Icon(Icons.Rounded.DataUsage, null) },
        title = { Text("Teste Extremo") },
        text = { Text("Este teste executará $count testes consecutivos e poderá consumir uma quantidade significativa de dados. O consumo final será identificado como estimado porque o teste não fornece contadores de bytes.") },
        confirmButton = { TextButton(onClick = hapticClick(DashFeedback.CONFIRM) { confirm = false; start() }) { Text("Iniciar") } },
        dismissButton = { TextButton(onClick = hapticClick { confirm = false }) { Text("Cancelar") } })
}

@Composable
fun ExtremeTestHistoryScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<ExtremeSession>>(emptyList()) }
    var selected by remember { mutableStateOf<ExtremeSession?>(null) }
    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) { ExtremeSessionStore(context).all() }
    }
    if (selected != null) ExtremeResultContent(selected!!, modifier) { selected = null }
    else LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ExtremeHeader("Testes Extremos", onBack) }
        if (sessions.isEmpty()) item { ExtremeEmpty("Nenhum Teste Extremo concluído.") }
        else items(sessions, key = { it.id }) { session ->
            val interaction = remember { MutableInteractionSource() }
            Surface(onClick = hapticClick { selected = session }, interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().pressMotion(interaction, .98f), shape = DashDesign.section,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExpressiveIcon(Icons.Rounded.ElectricBolt, MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer)
                    Column(Modifier.weight(1f)) {
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.startedAt)),
                            style = MaterialTheme.typography.titleMedium)
                        Text("${session.runs.size} testes · download médio ${one(session.runs.map { it.downloadMbps }.averageFloat())} Mbps",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.ChevronRight, null)
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun ExtremeResultContent(session: ExtremeSession, modifier: Modifier, onBack: () -> Unit) {
    val stats = remember(session) { ExtremeStats.from(session) }
    var selectedRun by rememberSaveable(session.id) { mutableIntStateOf(-1) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ExtremeHeader("Resultado do Teste Extremo", onBack) }
        item {
            Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero,
                color = qualityColors(stats.quality).first,
                contentColor = qualityColors(stats.quality).second) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Verified, null, Modifier.size(42.dp))
                    Text(stats.quality, style = MaterialTheme.typography.headlineMedium)
                    Text("${session.runs.size} testes concluídos")
                }
            }
        }
        item {
            ExtremeSection("Resumo") {
                ExtremePair("Download médio", "${one(stats.downloadAverage)} Mbps", "Upload médio", "${one(stats.uploadAverage)} Mbps")
                ExtremePair("Latência média", stats.latencyAverage?.let { "${one(it)} ms" } ?: "Não informada",
                    "Packet loss médio", "${one(stats.lossAverage)}%")
                ExtremePair("Download estimado", "≈ ${formatBytes(stats.downloadEstimatedBytes)}",
                    "Upload estimado", "≈ ${formatBytes(stats.uploadEstimatedBytes)}")
                ExtremePair("Total estimado", "≈ ${formatBytes(stats.estimatedBytes)}", "Duração", durationText(session.endedAt - session.startedAt))
                Text("O consumo é estimado pelo throughput médio e pela duração de cada teste.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { ExtremeChart("Download por teste", session.runs.map { it.downloadMbps }, "Mbps", selectedRun) { selectedRun = it } }
        item { ExtremeChart("Upload por teste", session.runs.map { it.uploadMbps }, "Mbps", selectedRun) { selectedRun = it } }
        item { ExtremeChart("Latência por teste", session.runs.map { it.latencyMs ?: 0f }, "ms", selectedRun) { selectedRun = it } }
        item { ExtremeChart("Packet loss por teste", session.runs.map { it.packetLossPercent }, "%", selectedRun) { selectedRun = it } }
        item {
            ExtremeSection("Estatísticas") {
                ExtremePair("Download mediano", "${one(stats.downloadMedian)} Mbps", "Mínimo", "${one(stats.downloadMin)} Mbps")
                ExtremePair("Máximo", "${one(stats.downloadMax)} Mbps", "Desvio padrão", "${one(stats.downloadStdDev)} Mbps")
                ExtremePair("Upload mediano", "${one(stats.uploadMedian)} Mbps", "Upload mínimo", "${one(stats.uploadMin)} Mbps")
                ExtremePair("Upload máximo", "${one(stats.uploadMax)} Mbps", "Variação download", "${one(stats.downloadRange)} Mbps")
                stats.latencyP95?.let { ExtremePair("Latência p95", "${one(it)} ms",
                    "Faixa de latência", "${one(stats.latencyMin ?: 0f)}–${one(stats.latencyMax ?: 0f)} ms") }
                ExtremePair("Perda máxima", "${one(stats.lossMax)}%", "Testes com perda", stats.testsWithLoss.toString())
            }
        }
        items(session.runs, key = { it.index }) { run ->
            val anomaly = run.downloadMbps < stats.downloadMedian * .60f && session.runs.size >= 5
            val interaction = remember { MutableInteractionSource() }
            Surface(onClick = hapticClick { selectedRun = if (selectedRun == run.index - 1) -1 else run.index - 1 },
                interactionSource = interaction, modifier = Modifier.fillMaxWidth().pressMotion(interaction, .98f).animateContentSize(),
                shape = DashDesign.section,
                color = if (anomaly) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Teste ${run.index}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("${one(run.downloadMbps)} Mbps", style = MaterialTheme.typography.titleMedium)
                    }
                    if (anomaly) Text("Resultado significativamente abaixo dos demais.",
                        style = MaterialTheme.typography.bodySmall)
                    if (selectedRun == run.index - 1) {
                        run.failure?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium) }
                        ExtremePair("Download", "${one(run.downloadMbps)} Mbps", "Upload", "${one(run.uploadMbps)} Mbps")
                        ExtremePair("Latência", run.latencyMs?.let { "${one(it)} ms" } ?: "Não informada",
                            "Packet loss", "${one(run.packetLossPercent)}%")
                        ExtremePair("Obstrução", "${one(run.obstructionPercent)}%", "Duração", durationText(run.durationMs))
                        InfoRow("Estado", run.dishState.replace('_', ' '))
                        InfoRow("Horário", DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(run.timestamp)))
                        InfoRow("Consumo estimado", "≈ ${formatBytes(run.estimatedBytes)}")
                        InfoRow("Potência", run.powerWatts?.let { "${one(it)} W" } ?: "Não fornecida")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

private data class ExtremeStats(
    val downloadAverage: Float, val downloadMedian: Float, val downloadMin: Float, val downloadMax: Float,
    val downloadRange: Float, val downloadStdDev: Float, val uploadAverage: Float, val uploadMedian: Float,
    val uploadMin: Float, val uploadMax: Float, val latencyAverage: Float?, val latencyP95: Float?,
    val latencyMin: Float?, val latencyMax: Float?, val lossAverage: Float, val lossMax: Float,
    val testsWithLoss: Int, val downloadEstimatedBytes: Long, val uploadEstimatedBytes: Long,
    val estimatedBytes: Long, val quality: String
) {
    companion object {
        fun from(session: ExtremeSession): ExtremeStats {
            val down = session.runs.map { it.downloadMbps }
            val up = session.runs.map { it.uploadMbps }
            val latency = session.runs.mapNotNull { it.latencyMs }.sorted()
            val loss = session.runs.map { it.packetLossPercent }
            val downAvg = down.averageFloat()
            val std = sqrt(down.map { (it - downAvg).pow(2) }.average()).toFloat()
            val cv = if (downAvg > 0f) std / downAvg else 1f
            // Objective thresholds: throughput coefficient of variation, packet loss and failed/empty results.
            val quality = when {
                down.any { it <= 0f } || loss.averageFloat() >= 2f || cv >= .35f -> "Instabilidade significativa"
                loss.averageFloat() >= 1f || cv >= .20f -> "Conexão variável"
                loss.averageFloat() < .2f && cv < .10f -> "Excelente estabilidade"
                else -> "Boa estabilidade"
            }
            val downloadBytes = session.runs.sumOf {
                (it.downloadMbps.coerceAtLeast(0f) * 1_000_000.0 / 8.0 * it.durationMs / 1_000.0).toLong()
            }
            val uploadBytes = session.runs.sumOf {
                (it.uploadMbps.coerceAtLeast(0f) * 1_000_000.0 / 8.0 * it.durationMs / 1_000.0).toLong()
            }
            return ExtremeStats(downAvg, median(down), down.min(), down.max(), down.max() - down.min(), std,
                up.averageFloat(), median(up), up.min(), up.max(),
                latency.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                latency.takeIf { it.size >= 5 }?.let { it[((it.lastIndex * .95f).toInt()).coerceIn(0, it.lastIndex)] },
                latency.firstOrNull(), latency.lastOrNull(), loss.averageFloat(), loss.max(),
                loss.count { it > 0f }, downloadBytes, uploadBytes,
                session.runs.sumOf { it.estimatedBytes }, quality)
        }
    }
}

@Composable
private fun ExtremeChart(title: String, values: List<Float>, unit: String, selected: Int, onSelect: (Int) -> Unit) {
    if (values.size < 2) return
    var width by remember { mutableFloatStateOf(1f) }
    val line = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val background = MaterialTheme.colorScheme.surfaceContainer
    val max = values.max().coerceAtLeast(1f)
    ExtremeSection(title) {
        Canvas(Modifier.fillMaxWidth().height(150.dp).onSizeChanged { width = it.width.toFloat() }
            .pointerInput(values) { detectTapGestures { point ->
                onSelect(((point.x / width) * values.size).toInt().coerceIn(0, values.lastIndex))
            } }) {
            drawRoundRect(background, cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
            val inset = 16.dp.toPx()
            val path = Path()
            values.forEachIndexed { index, value ->
                val x = inset + (size.width - inset * 2) * index / values.lastIndex
                val y = size.height - inset - (size.height - inset * 2) * value.coerceAtLeast(0f) / max
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(if (index == selected) selectedColor else line,
                    if (index == selected) 7.dp.toPx() else 4.dp.toPx(), Offset(x, y))
            }
            drawPath(path, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        val index = selected.takeIf { it in values.indices } ?: values.lastIndex
        Text("Teste ${index + 1} · ${one(values[index])} $unit", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ExtremePair(a: String, av: String, b: String, bv: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 320.dp) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExtremeCell(a, av, Modifier.fillMaxWidth()); ExtremeCell(b, bv, Modifier.fillMaxWidth())
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExtremeCell(a, av, Modifier.weight(1f)); ExtremeCell(b, bv, Modifier.weight(1f))
        }
    }
}

@Composable private fun ExtremeCell(label: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = DashDesign.pill, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable private fun ExtremeSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeading(title); content()
        }
    }
}

@Composable private fun ExtremeHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = hapticClick(action = onBack)) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
    }
}

@Composable private fun ExtremeEmpty(text: String) {
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
        color = MaterialTheme.colorScheme.surfaceContainerLow) { Text(text, Modifier.padding(20.dp)) }
}

@Composable
private fun hapticSliderValue(current: Int, update: (Int) -> Unit): (Float) -> Unit {
    val haptics = rememberDashHaptics()
    return { raw ->
        val next = raw.toInt().coerceIn(5, 15)
        if (next != current) { haptics.perform(DashFeedback.TAP); update(next) }
    }
}

@Composable private fun qualityColors(quality: String): Pair<Color, Color> = when (quality) {
    "Instabilidade significativa" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    "Conexão variável" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
}

private fun List<Float>.averageFloat() = if (isEmpty()) 0f else average().toFloat()
private fun median(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted(); val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2f else sorted[middle]
}
private fun one(value: Float) = String.format(Locale.getDefault(), "%.1f", value)
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.getDefault(), "%.1f kB", bytes / 1_000.0)
}
private fun durationText(ms: Long): String = when {
    ms < 60_000 -> "${ms / 1_000}s"
    else -> "${ms / 60_000}min ${ms / 1_000 % 60}s"
}

private fun ExtremeSession.toJson() = JSONObject().apply {
    put("id", id); put("requested", requestedTests); put("start", startedAt); put("end", endedAt)
    put("runs", JSONArray().apply { runs.forEach { run -> put(JSONObject().apply {
        put("index", run.index); put("time", run.timestamp); put("down", run.downloadMbps.toDouble())
        put("up", run.uploadMbps.toDouble()); put("latency", run.latencyMs?.toDouble() ?: JSONObject.NULL)
        put("loss", run.packetLossPercent.toDouble()); put("obstruction", run.obstructionPercent.toDouble())
        put("state", run.dishState); put("alerts", JSONArray(run.alerts)); put("duration", run.durationMs)
        put("bytes", run.estimatedBytes); put("power", run.powerWatts?.toDouble() ?: JSONObject.NULL)
        put("failure", run.failure ?: JSONObject.NULL)
    }) } })
}

private fun sessionFromJson(json: JSONObject): ExtremeSession {
    val array = json.getJSONArray("runs")
    require(array.length() > 0) { "Sessão sem resultados" }
    val runs = (0 until array.length()).map { i -> array.getJSONObject(i) }.map { run ->
        ExtremeRun(run.getInt("index"), run.getLong("time"), run.getDouble("down").toFloat(),
            run.getDouble("up").toFloat(), run.optDouble("latency").takeUnless { it.isNaN() }?.toFloat(),
            run.getDouble("loss").toFloat(), run.getDouble("obstruction").toFloat(), run.getString("state"),
            (0 until run.getJSONArray("alerts").length()).map { run.getJSONArray("alerts").getString(it) },
            run.getLong("duration"), run.getLong("bytes"),
            run.optDouble("power").takeUnless { it.isNaN() }?.toFloat(),
            run.optString("failure").takeIf { it.isNotBlank() && it != "null" })
    }
    return ExtremeSession(json.getLong("id"), json.getInt("requested"), json.getLong("start"), json.getLong("end"), runs)
}
