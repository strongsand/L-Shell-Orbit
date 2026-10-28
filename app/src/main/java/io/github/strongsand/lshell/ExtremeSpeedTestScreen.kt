package io.github.strongsand.lshell

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.core.model.DishySnapshot
import kotlinx.coroutines.Dispatchers
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
    val failure: String? = null,
    val downloadMeasured: Boolean? = null,
    val uploadMeasured: Boolean? = null
)

data class ExtremeSession(val id: Long, val requestedTests: Int, val startedAt: Long,
                          val endedAt: Long, val runs: List<ExtremeRun>)

internal class ExtremeSessionStore(context: Context) {
    init { BackupGate.ensureRecovered(context) }
    private val directory = File(context.filesDir, "extreme_tests").apply { mkdirs() }
    fun save(session: ExtremeSession) = synchronized(BackupGate.monitor) {
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
fun ExtremeSpeedTestScreen(snapshot: DishySnapshot, modifier: Modifier = Modifier, requestedId: Long? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { ConnectionTestController.get(context) }
    ConnectionTestFeedback(controller, ConnectionTestKind.EXTREME)
    val execution by controller.state.collectAsState()
    var count by rememberSaveable { mutableIntStateOf(5) }
    // This destination follows an active run or an explicitly requested result,
    // never an unrelated completed execution retained by the controller.
    var selectedId by rememberSaveable(requestedId) {
        mutableStateOf(ExtremeTestNavigation.followedId(execution, requestedId, null))
    }
    LaunchedEffect(execution.id, execution.active, requestedId) {
        selectedId = ExtremeTestNavigation.followedId(execution, requestedId, selectedId)
    }
    val own = execution.kind == ConnectionTestKind.EXTREME && (selectedId == null || selectedId == execution.id)
    val running = execution.active
    var confirm by remember { mutableStateOf(false) }
    val error = when {
        selectedId != null && execution.id != selectedId -> androidx.compose.ui.res.stringResource(R.string.test_result_unavailable)
        !own && running -> androidx.compose.ui.res.stringResource(R.string.test_other_active)
        own && execution.phase == ConnectionTestPhase.INTERRUPTED -> androidx.compose.ui.res.stringResource(R.string.test_interrupted_detail)
        own -> execution.error
        else -> null
    }
    val completed = execution.extreme.takeIf { ExtremeTestNavigation.showsResult(execution, selectedId) }
    val startTest = rememberConnectionTestStart(ConnectionTestKind.EXTREME, count)
    fun start() { selectedId = null; startTest() }

    val motion = LocalOrbitMotion.current
    Crossfade(targetState = completed, animationSpec = tween(if (motion) 200 else 0),
        label = "extreme execution to result") { shownResult ->
        if (shownResult != null) ExtremeResultContent(shownResult, modifier, onBack)
        else LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { ExtremeHeader(androidx.compose.ui.res.stringResource(R.string.function_extreme), onBack) }
            if (!(running && own)) item {
                Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero,
                    color = amoledCardColor(MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(OrbitSymbols.ElectricBolt, null, Modifier.size(42.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(androidx.compose.ui.res.stringResource(R.string.test_repeat_title), style = MaterialTheme.typography.headlineMedium,
                            color = amoledCardContent(MaterialTheme.colorScheme.onPrimaryContainer))
                        Text(androidx.compose.ui.res.stringResource(R.string.editorial_repeat_test),
                            color = amoledCardContent(MaterialTheme.colorScheme.onPrimaryContainer))
                    }
                }
            }
            if (!(running && own)) item {
                Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(androidx.compose.ui.res.stringResource(R.string.test_round_count), style = MaterialTheme.typography.titleLarge)
                        TestValueText(count.toString(), style = MaterialTheme.typography.displaySmall,
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
            if (running && own) item("active") {
                TestActivityCard(execution, onStop = { controller.stop(execution.id) })
            }
            error?.let { message -> item {
                Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
                    color = amoledCardColor(MaterialTheme.colorScheme.errorContainer)) {
                    Text(message, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } }
            if (!(running && own)) item {
                val interaction = remember { MutableInteractionSource() }
                Button(onClick = hapticClick(DashFeedback.SPECIAL) { confirm = true }, enabled = !running,
                    interactionSource = interaction, modifier = Modifier.fillMaxWidth().pressMotion(interaction, .97f)) {
                    Text(androidx.compose.ui.res.stringResource(R.string.test_start_extreme))
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        icon = { Icon(OrbitSymbols.DataUsage, null) },
        title = { Text(androidx.compose.ui.res.stringResource(R.string.function_extreme)) },
        text = { Text(androidx.compose.ui.res.stringResource(R.string.test_extreme_confirm, count)) },
        confirmButton = { TextButton(onClick = hapticClick(DashFeedback.CONFIRM) { confirm = false; start() }) { Text(androidx.compose.ui.res.stringResource(R.string.editorial_start_test)) } },
        dismissButton = { TextButton(onClick = hapticClick { confirm = false }) { Text(androidx.compose.ui.res.stringResource(R.string.test_cancel_action)) } })
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
        item { ExtremeHeader(stringResource(R.string.ui_extreme_tests), onBack) }
        if (sessions.isEmpty()) item { ExtremeEmpty(stringResource(R.string.ui_no_completed_extreme_tests)) }
        else items(sessions, key = { it.id }) { session ->
            val interaction = remember { MutableInteractionSource() }
            Surface(onClick = hapticClick { selected = session }, interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().pressMotion(interaction, .98f), shape = DashDesign.section,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExpressiveIcon(OrbitSymbols.ElectricBolt, MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer)
                    Column(Modifier.weight(1f)) {
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.startedAt)),
                            style = MaterialTheme.typography.titleMedium)
                        Text(pluralStringResource(R.plurals.ui_extreme_history_summary, session.runs.size, session.runs.size,
                            one(session.runs.map { it.downloadMbps }.averageFloat())),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(OrbitSymbols.ChevronRight, null)
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun ExtremeResultContent(session: ExtremeSession, modifier: Modifier, onBack: () -> Unit) {
    val stats = remember(session) { ExtremeStats.from(session) }
    val resultState = remember(session) { ConnectionTestState(id = session.id,
        kind = ConnectionTestKind.EXTREME, phase = ConnectionTestPhase.COMPLETED,
        startedAt = session.startedAt, endedAt = session.endedAt, runs = session.runs, extreme = session,
        total = session.requestedTests, completed = session.runs.size) }
    val resultModel = remember(resultState) { ConnectionTestResultModel.from(resultState) }
    var selectedRun by rememberSaveable(session.id) { mutableIntStateOf(-1) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ExtremeHeader(androidx.compose.ui.res.stringResource(R.string.test_extreme_result), onBack) }
        item {
            val outcome = ConnectionTestExperience.outcome(resultState)
            val fullyCompleted = session.runs.size == session.requestedTests
            val description = stringResource(when {
                outcome != TestOutcome.SUCCESS -> R.string.test_no_result
                fullyCompleted -> R.string.test_done
                else -> R.string.test_result_ready
            })
            val colors = qualityColors(stats.quality)
            TestResultBanner(stringResource(R.string.function_extreme),
                if (outcome == TestOutcome.SUCCESS) stringResource(resultModel.title) else description,
                description + " · " + stringResource(R.string.test_rounds_done, session.runs.size, session.requestedTests),
                outcome == TestOutcome.SUCCESS, colors.first, colors.second, icon = when (resultModel.statusIcon) {
                    R.drawable.ic_test_result_check -> OrbitSymbols.CheckCircle
                    R.drawable.ic_test_result_warning -> OrbitSymbols.Warning
                    else -> OrbitSymbols.Info
                })
        }
        item { TestResultRates(resultModel.metrics, averages = true) }
        item { TestResultFacts(resultModel.metrics, resultModel.durationSeconds?.let { durationText(it * 1000L) }, averages = true) }
        item {
            ExtremeSection(stringResource(R.string.traffic_summary)) {
                ExtremePair(stringResource(R.string.ui_estimated_download), "≈ ${formatBytes(stats.downloadEstimatedBytes)}",
                    stringResource(R.string.ui_estimated_upload), "≈ ${formatBytes(stats.uploadEstimatedBytes)}")
                ExtremePair(stringResource(R.string.ui_estimated_total), "≈ ${formatBytes(stats.estimatedBytes)}",
                    stringResource(R.string.editorial_mean_loss), "${one(stats.lossAverage)}%")
                Text(stringResource(R.string.ui_data_use_is_estimated_from_the_average_throughput_and_duration_of),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { ExtremeChart(stringResource(R.string.ui_download_per_test), session.runs.map { if (it.downloadMeasured == false) Float.NaN else it.downloadMbps }, "Mbps", session.runs.map { it.timestamp }, selectedRun) { selectedRun = it } }
        item { ExtremeChart(stringResource(R.string.ui_upload_per_test), session.runs.map { if (it.uploadMeasured == false) Float.NaN else it.uploadMbps }, "Mbps", session.runs.map { it.timestamp }, selectedRun) { selectedRun = it } }
        item { ExtremeChart(stringResource(R.string.ui_latency_per_test), session.runs.map { it.latencyMs ?: Float.NaN }, "ms", session.runs.map { it.timestamp }, selectedRun) { selectedRun = it } }
        item { ExtremeChart(androidx.compose.ui.res.stringResource(R.string.editorial_loss_tests), session.runs.map { it.packetLossPercent }, "%", session.runs.map { it.timestamp }, selectedRun) { selectedRun = it } }
        item {
            ExtremeSection(stringResource(R.string.ui_statistics)) {
                ExtremePair(stringResource(R.string.health_download_median), "${one(stats.downloadMedian)} Mbps", stringResource(R.string.ui_minimum), "${one(stats.downloadMin)} Mbps")
                ExtremePair(stringResource(R.string.ui_maximum), "${one(stats.downloadMax)} Mbps", stringResource(R.string.ui_standard_deviation), "${one(stats.downloadStdDev)} Mbps")
                ExtremePair(stringResource(R.string.health_upload_median), "${one(stats.uploadMedian)} Mbps", stringResource(R.string.ui_minimum_upload), "${one(stats.uploadMin)} Mbps")
                ExtremePair(stringResource(R.string.ui_maximum_upload), "${one(stats.uploadMax)} Mbps", stringResource(R.string.ui_download_range), "${one(stats.downloadRange)} Mbps")
                stats.latencyP95?.let { ExtremePair(stringResource(R.string.ui_latency_p95), "${one(it)} ms",
                    stringResource(R.string.ui_latency_range), "${one(stats.latencyMin ?: 0f)}–${one(stats.latencyMax ?: 0f)} ms") }
                ExtremePair(stringResource(R.string.ui_peak_packet_loss), "${one(stats.lossMax)}%", stringResource(R.string.ui_tests_with_packet_loss), stats.testsWithLoss.toString())
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
                        Text(stringResource(R.string.ui_test_number, run.index), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("${one(run.downloadMbps)} Mbps", style = MaterialTheme.typography.titleMedium)
                    }
                    if (anomaly) Text(stringResource(R.string.ui_result_significantly_below_the_others),
                        style = MaterialTheme.typography.bodySmall)
                    if (selectedRun == run.index - 1) {
                        run.failure?.let { Text(telemetryLabel(it), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium) }
                        ExtremePair(stringResource(R.string.health_download), "${one(run.downloadMbps)} Mbps", stringResource(R.string.health_upload), "${one(run.uploadMbps)} Mbps")
                        ExtremePair(stringResource(R.string.health_latency), run.latencyMs?.let { "${one(it)} ms" } ?: stringResource(R.string.ui_not_provided),
                            androidx.compose.ui.res.stringResource(R.string.traffic_loss), "${one(run.packetLossPercent)}%")
                        ExtremePair(stringResource(R.string.editorial_obstruction), "${one(run.obstructionPercent)}%", stringResource(R.string.ui_duration), durationText(run.durationMs))
                        InfoRow(stringResource(R.string.ui_state), storedDishStateLabel(run.dishState))
                        InfoRow(stringResource(R.string.settings_report_time), DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(run.timestamp)))
                        InfoRow(stringResource(R.string.ui_estimated_data_use), "≈ ${formatBytes(run.estimatedBytes)}")
                        InfoRow(stringResource(R.string.editorial_power), run.powerWatts?.let { "${one(it)} W" } ?: stringResource(R.string.ui_not_provided_2),
                            missing = run.powerWatts == null)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

internal data class ExtremeStats(
    val downloadAverage: Float, val downloadMedian: Float, val downloadMin: Float, val downloadMax: Float,
    val downloadRange: Float, val downloadStdDev: Float, val uploadAverage: Float, val uploadMedian: Float,
    val uploadMin: Float, val uploadMax: Float, val latencyAverage: Float?, val latencyP95: Float?,
    val latencyMin: Float?, val latencyMax: Float?, val lossAverage: Float, val lossMax: Float,
    val testsWithLoss: Int, val downloadEstimatedBytes: Long, val uploadEstimatedBytes: Long,
    val estimatedBytes: Long, val quality: ExtremeStabilityGrade
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
                down.any { it <= 0f } || loss.averageFloat() >= 2f || cv >= .35f -> ExtremeStabilityGrade.UNSTABLE
                loss.averageFloat() >= 1f || cv >= .20f -> ExtremeStabilityGrade.VARIABLE
                loss.averageFloat() < .2f && cv < .10f -> ExtremeStabilityGrade.EXCELLENT
                else -> ExtremeStabilityGrade.GOOD
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
private fun ExtremeChart(title: String, values: List<Float>, unit: String, times: List<Long>, selected: Int, onSelect: (Int) -> Unit) {
    if (values.size < 2) return
    val palette = MaterialTheme.colorScheme
    val peak = remember(values) { values.filter { it.isFinite() }.maxOrNull()?.coerceAtLeast(1f) ?: 1f }
    val haptics = rememberDashHaptics()
    val feedback = remember(title) { MetricInspectionFeedback() }
    val valid = remember(values) { values.indices.filter { values[it].isFinite() } }
    val shown = selected.takeIf { it in values.indices } ?: values.lastIndex
    val sampleTime = selected.takeIf { it in values.indices && values[it].isFinite() }?.let { times[it] }
    val pulse = rememberChartPulse(sampleTime)
    val motion = LocalOrbitMotion.current
    val gesture = remember { ChartDirectManipulation() }
    val pixelDensity = androidx.compose.ui.platform.LocalDensity.current.density
    var plotSize by remember { mutableStateOf(Size(1f, 1f)) }
    val segments = remember(values) {
        var first = 0
        values.mapIndexed { i, value ->
            if (i == 0 || !values[i - 1].isFinite()) first = i
            first.toLong().takeIf { value.isFinite() }
        }
    }
    val travelTarget = sampleTime?.let { time -> segments[selected]?.let { segment ->
        ChartMotionTarget(time, Offset(selected.toFloat() / values.lastIndex, 1f - values[selected] / peak), segment)
    } }
    val travel = rememberChartTravel(travelTarget, gesture.active, Triple(title, values.size, peak), plotSize)
    val gap = androidx.compose.ui.res.stringResource(R.string.metric_detail_gap)
    val value = values[shown].takeIf { it.isFinite() }?.let { trafficValue(it.toDouble(), unit) } ?: gap
    val description = androidx.compose.ui.res.stringResource(R.string.chart_round_reading, shown + 1, trafficTime(times[shown], seconds = true), value)
    val inset = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
    fun select(index: Int) {
        onSelect(index)
        val time = times[index].takeIf { values[index].isFinite() }
        if (feedback.sample(time, android.os.SystemClock.uptimeMillis())) haptics.perform(DashFeedback.DRAG_SNAP)
    }
    fun step(forward: Boolean): Boolean {
        val index = if (forward) valid.firstOrNull { selected < 0 || it > selected }
            else valid.lastOrNull { selected < 0 || it < selected }
        index ?: return false
        select(index); return true
    }
    val latestSelect by rememberUpdatedState<(Float) -> Unit>({ fraction -> select(chartOrdinalIndex(fraction, values.size)) })
    ExtremeSection(title) {
        Box {
            val drawing = remember(values, peak, palette) {
                Modifier.fillMaxWidth().height(150.dp).clipToBounds().drawWithCache {
                    val margin = 16.dp.toPx()
                    fun position(index: Int) = Offset(margin + (size.width - margin * 2) * index / values.lastIndex,
                        size.height - margin - (size.height - margin * 2) * values[index] / peak)
                    val path = Path()
                    val dots = mutableListOf<Offset>()
                    values.indices.forEach { index ->
                        if (!values[index].isFinite()) return@forEach
                        val point = position(index)
                        if (index == 0 || !values[index - 1].isFinite()) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                        dots += point
                    }
                    onDrawBehind {
                        drawRoundRect(palette.surfaceContainer, cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
                        drawPath(path, palette.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                        dots.forEach { drawCircle(palette.primary, 2.5.dp.toPx(), it) }
                    }
                }
            }
            Box(drawing)
            Canvas(Modifier.fillMaxWidth().height(150.dp).onSizeChanged { plotSize = Size((it.width / pixelDensity - 32f).coerceAtLeast(1f), (it.height / pixelDensity - 32f).coerceAtLeast(1f)) }.clipToBounds()
                .chartInspectionAccess(title, description, { if (selected < 0) step(true) else true }, ::step,
                    { onSelect(-1); feedback.sample(null, android.os.SystemClock.uptimeMillis()); true })
                .chartScrubbing(title, inset, gesture) { latestSelect(it) }) {
                if (selected in values.indices) {
                    val x = inset + (size.width - inset * 2) * selected / values.lastIndex
                    drawLine(palette.onSurface.copy(alpha = .7f), Offset(x, inset), Offset(x, size.height - inset), 1.dp.toPx())
                    if (values[selected].isFinite()) drawChartMarker(Offset(x, size.height - inset - (size.height - inset * 2) * values[selected] / peak),
                        palette.primary, palette.surfaceContainer, if (motion) pulse.value else 0f)
                    travelTarget?.let { drawChartTravel(travel.value, it.position, palette.primary,
                        inset, inset, size.width - 2 * inset, size.height - 2 * inset) }
                }
            }
        }
        MetricDetailValueText(description, MaterialTheme.typography.labelLarge, color = palette.primary)
    }
}

@Composable
private fun ExtremePair(a: String, av: String, b: String, bv: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth / androidx.compose.ui.platform.LocalDensity.current.fontScale < 320.dp) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExtremeCell(a, av, Modifier.fillMaxWidth()); ExtremeCell(b, bv, Modifier.fillMaxWidth())
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExtremeCell(a, av, Modifier.weight(1f)); ExtremeCell(b, bv, Modifier.weight(1f))
        }
    }
}

@Composable private fun ExtremeCell(label: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TestValueText(value, style = MaterialTheme.typography.titleMedium)
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
        IconButton(onClick = hapticClick(action = onBack)) { Icon(OrbitSymbols.ArrowBack, stringResource(R.string.antenna_back)) }
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

@Composable private fun qualityColors(quality: ExtremeStabilityGrade): Pair<Color, Color> = when (quality) {
    ExtremeStabilityGrade.UNSTABLE -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    ExtremeStabilityGrade.VARIABLE -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
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
@Composable
private fun durationText(ms: Long): String = when {
    ms < 60_000 -> stringResource(R.string.ui_duration_seconds_short, ms / 1_000)
    else -> stringResource(R.string.ui_duration_minutes_seconds, ms / 60_000, ms / 1_000 % 60)
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
        put("download_measured", run.downloadMeasured ?: JSONObject.NULL)
        put("upload_measured", run.uploadMeasured ?: JSONObject.NULL)
    }) } })
}

private fun sessionFromJson(json: JSONObject): ExtremeSession {
    val array = json.getJSONArray("runs")
    require(array.length() > 0) { "Sessão sem resultados" }
    val runs = (0 until array.length()).map { i -> array.getJSONObject(i) }.map { run ->
        ExtremeRun(run.getInt("index"), run.getLong("time"), run.optDouble("down").toFloat(),
            run.optDouble("up").toFloat(), run.optDouble("latency").takeUnless { it.isNaN() }?.toFloat(),
            run.optDouble("loss").toFloat(), run.optDouble("obstruction").toFloat(), run.getString("state"),
            (0 until run.getJSONArray("alerts").length()).map { run.getJSONArray("alerts").getString(it) },
            run.getLong("duration"), run.getLong("bytes"),
            run.optDouble("power").takeUnless { it.isNaN() }?.toFloat(),
            run.optString("failure").takeIf { it.isNotBlank() && it != "null" },
            if (run.has("download_measured") && !run.isNull("download_measured")) run.getBoolean("download_measured") else null,
            if (run.has("upload_measured") && !run.isNull("upload_measured")) run.getBoolean("upload_measured") else null)
    }
    return ExtremeSession(json.getLong("id"), json.getInt("requested"), json.getLong("start"), json.getLong("end"), runs)
}
