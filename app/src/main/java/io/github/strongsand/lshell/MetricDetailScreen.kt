package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.strongsand.lshell.beacon.BeaconPreferences
import io.github.strongsand.lshell.core.data.ConnectionEvent
import io.github.strongsand.lshell.core.data.TelemetrySample
import io.github.strongsand.lshell.core.data.LivePowerReading
import io.github.strongsand.lshell.core.model.DishySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

enum class MetricDetailKind { DOWNLOAD, UPLOAD, LATENCY, SIGNAL, POWER }
internal fun MetricDetailKind.historyMetric() = when (this) {
    MetricDetailKind.DOWNLOAD -> HistoricalMetric.DOWNLOAD
    MetricDetailKind.UPLOAD -> HistoricalMetric.UPLOAD
    MetricDetailKind.LATENCY -> HistoricalMetric.LATENCY
    MetricDetailKind.SIGNAL -> HistoricalMetric.LOSS
    MetricDetailKind.POWER -> HistoricalMetric.POWER
}
private data class MetricDetailView(val series: MetricHistorySeries, val traffic: TrafficSummary?,
    val mean: Double?, val median: Double?, val minimum: Double?, val maximum: Double?, val obstructions: Int)

/** Read-only Home details. The separate traffic monitor retains all recording/session controls. */
@Composable
fun MetricDetailScreen(kind: MetricDetailKind, snapshot: DishySnapshot, modifier: Modifier = Modifier,
    onBack: () -> Unit, liveSamples: List<TelemetrySample> = emptyList(),
    @Suppress("UNUSED_PARAMETER") liveEvents: List<ConnectionEvent> = emptyList(),
    livePower: LivePowerReading? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var minutes by rememberSaveable(kind) { mutableIntStateOf(15) }
    var revision by remember { mutableIntStateOf(0) }
    var view by remember(kind, minutes) { mutableStateOf<MetricDetailView?>(null) }
    var failed by remember(kind, minutes) { mutableStateOf(false) }
    var loading by remember(kind, minutes) { mutableStateOf(true) }
    DisposableEffect(lifecycle, context) {
        val prefs = BeaconPreferences(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "background_completed" || key == "last_sync_at") revision++
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) revision++ }
        prefs.observeSync(listener); lifecycle.addObserver(observer)
        onDispose { prefs.stopObservingSync(listener); lifecycle.removeObserver(observer) }
    }
    // Existing telemetry drives refresh; no RPC, new collector, speedtest or background timer.
    val cadence = if (minutes <= 30) 5_000 else 30_000
    LaunchedEffect(kind, minutes, snapshot.timestamp / cadence, revision, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loading = true; failed = false
            try {
                val end = System.currentTimeMillis()
                val start = end - minutes * 60_000L
                val stored = withContext(Dispatchers.IO) { HistoryStore(context).use { it.between(start - 5_000, end) } }
                val live = liveSamples.filter { it.timestamp >= start - 5_000 && it.timestamp < end }.map {
                    HistoryEntry(it.timestamp, if (it.isHistory) "terminal_history" else "reading", "",
                        it.downloadMbps, it.uploadMbps, it.latencyMs, it.dropPercent, powerWatts = it.powerWatts)
                }
                val current = snapshot.takeIf { it.isOnline && !it.isFromCache && it.timestamp in start until end }?.let {
                    listOf(HistoryEntry(it.timestamp, "reading", "", it.throughput.downlinkMbps, it.throughput.uplinkMbps,
                        it.latency.popPingLatencyMs, it.latency.popPingDropRate * 100, it.obstruction.obstructionPercentage))
                }.orEmpty()
                view = withContext(Dispatchers.Default) {
                    val rows = stored + live + current
                    val series = MetricHistorySeriesBuilder.build(rows, kind.historyMetric(), start, end)
                    val values = series.values.sorted()
                    val traffic = if (kind == MetricDetailKind.DOWNLOAD || kind == MetricDetailKind.UPLOAD)
                        TrafficSessionAnalyzer.analyze(rows, start, end).summary(
                            if (kind == MetricDetailKind.DOWNLOAD) TrafficMetric.DOWNLOAD else TrafficMetric.UPLOAD) else null
                    MetricDetailView(series, traffic, values.takeIf { it.isNotEmpty() }?.average(),
                        values.takeIf { it.isNotEmpty() }?.let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 },
                        values.firstOrNull(), values.lastOrNull(), stored.count {
                            it.timestamp >= start && it.obstructionPercent.isFinite() && it.obstructionPercent >= 1 })
                }
                failed = false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { loading = false }
        }
    }
    val title = stringResource(when (kind) {
        MetricDetailKind.DOWNLOAD -> R.string.traffic_download
        MetricDetailKind.UPLOAD -> R.string.traffic_upload
        MetricDetailKind.LATENCY -> R.string.traffic_latency
        MetricDetailKind.SIGNAL -> R.string.metric_detail_signal
        MetricDetailKind.POWER -> R.string.editorial_dish_power
    })
    val icon = when (kind) {
        MetricDetailKind.DOWNLOAD -> OrbitSymbols.Download; MetricDetailKind.UPLOAD -> OrbitSymbols.Upload
        MetricDetailKind.LATENCY -> OrbitSymbols.Speed; MetricDetailKind.SIGNAL -> OrbitSymbols.SignalCellularAlt
        MetricDetailKind.POWER -> OrbitSymbols.Bolt
    }
    val unit = when (kind) {
        MetricDetailKind.DOWNLOAD, MetricDetailKind.UPLOAD -> "Mbps"
        MetricDetailKind.LATENCY -> "ms"; MetricDetailKind.SIGNAL -> "%"; MetricDetailKind.POWER -> "W"
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "heading") { Column {
            Row(Modifier.expressiveReveal(kind, distance = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = hapticClick(action = onBack)) { Icon(OrbitSymbols.ArrowBack, stringResource(R.string.auto_back)) }
                Column(Modifier.weight(1f)) {
                    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.metric_detail_subtitle), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (kind == MetricDetailKind.POWER) PoeVoltageCard(snapshot)
        } }
        item(key = "period") {
            Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (maxWidth / LocalDensity.current.fontScale < 280.dp) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CurrentMetricReadingValue(kind, snapshot, Modifier.fillMaxWidth(), livePower)
                            MetricPeriodControl(minutes, Modifier.align(Alignment.End)) { minutes = it }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CurrentMetricReadingValue(kind, snapshot, Modifier.weight(1f), livePower)
                            MetricPeriodControl(minutes) { minutes = it }
                        }
                    }
                }
            }
        }
        item(key = "refresh") {
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (loading && view != null) LinearProgressIndicator(Modifier.fillMaxSize())
            }
        }
        if (failed) item(key = "error") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.metric_detail_load_error), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !loading, onClick = hapticClick { revision++ }) {
                    Text(stringResource(R.string.health_retry))
                }
            }
        }
        val data = view
        if (data == null && !failed) {
            item(key = "chart") { ChartLoadingPlaceholder(title, stringResource(R.string.metric_detail_loading)) }
            item(key = "summary") {
                Surface(Modifier.fillMaxWidth(), shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(DashDesign.inset).heightIn(min = 112.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.traffic_summary), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.metric_detail_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else if (data != null) {
            item(key = "chart") { MetricDetailChart(data.series, if (kind == MetricDetailKind.SIGNAL) stringResource(R.string.traffic_loss) else title, unit) }
            item(key = "summary") {
                Surface(Modifier.fillMaxWidth().expressiveReveal("summary-$kind-$minutes", distance = 6.dp),
                    shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.traffic_summary), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        val s = data.traffic
                        MetricStatPair(stringResource(R.string.traffic_mean), trafficValue(s?.mean ?: data.mean, unit),
                            stringResource(R.string.metric_detail_median), trafficValue(s?.median ?: data.median, unit))
                        MetricStatPair(stringResource(R.string.traffic_minimum), trafficValue(data.minimum, unit),
                            stringResource(R.string.metric_peak), trafficValue(s?.peak ?: data.maximum, unit))
                        if (s != null) {
                            listOf("30 s" to s.sustained30s, "1 min" to s.sustained1m, "5 min" to s.sustained5m).forEach { (window, value) ->
                                MetricStat(stringResource(R.string.traffic_short_sustained, window), trafficValue(value, unit))
                            }
                            MetricStat(stringResource(R.string.metric_detail_estimated), s.estimatedBytes?.let(::trafficBytes) ?: stringResource(R.string.editorial_no_reading))
                            MetricStat(stringResource(R.string.traffic_short_active), trafficShortDuration(s.activeMs))
                        }
                        Text(stringResource(R.string.metric_detail_support_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (kind == MetricDetailKind.SIGNAL) item(key = "signal") { MetricSignalContext(snapshot, data.obstructions) }
        }
    }
}

@Composable
private fun CurrentMetricReadingValue(kind: MetricDetailKind, snapshot: DishySnapshot, modifier: Modifier = Modifier,
    livePower: LivePowerReading? = null) {
    val reading = currentMetricReading(kind, snapshot, System.currentTimeMillis(), livePower) ?: return
    val title = stringResource(when (kind) {
        MetricDetailKind.DOWNLOAD -> R.string.traffic_download
        MetricDetailKind.UPLOAD -> R.string.traffic_upload
        MetricDetailKind.LATENCY -> R.string.traffic_latency
        MetricDetailKind.SIGNAL -> R.string.traffic_loss
        MetricDetailKind.POWER -> R.string.editorial_dish_power
    })
    val unit = when (kind) {
        MetricDetailKind.DOWNLOAD, MetricDetailKind.UPLOAD -> "Mbps"
        MetricDetailKind.LATENCY -> "ms"
        MetricDetailKind.SIGNAL -> "%"
        MetricDetailKind.POWER -> "W"
    }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val number = if (reading.state == CurrentMetricState.AVAILABLE)
        if (kind == MetricDetailKind.LATENCY) formatCurrentLatency(checkNotNull(reading.value), locale)
        else formatCurrentMbps(checkNotNull(reading.value), locale) else ""
    val value = when (reading.state) {
        CurrentMetricState.AVAILABLE -> "$number $unit"
        CurrentMetricState.WAITING -> stringResource(R.string.metric_detail_current_waiting)
        CurrentMetricState.UNAVAILABLE -> stringResource(R.string.metric_detail_current_unavailable)
        CurrentMetricState.DISCONNECTED -> stringResource(R.string.metric_detail_current_disconnected)
    }
    val spokenValue = if (reading.state == CurrentMetricState.AVAILABLE)
        when (kind) {
            MetricDetailKind.DOWNLOAD, MetricDetailKind.UPLOAD -> stringResource(R.string.test_rate_accessible, number)
            MetricDetailKind.LATENCY -> stringResource(R.string.metric_detail_current_latency_spoken, number)
            MetricDetailKind.SIGNAL -> stringResource(R.string.metric_detail_current_loss_spoken, number)
            MetricDetailKind.POWER -> stringResource(R.string.metric_detail_current_power_spoken, number)
        } else value
    val accessibility = stringResource(R.string.metric_detail_current_accessible, title, spokenValue)
    Column(modifier.clearAndSetSemantics { contentDescription = accessibility },
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.metric_detail_current), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        MetricDetailValueText(value, style = if (reading.state == CurrentMetricState.AVAILABLE)
            MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
    }
}
@Composable
private fun MetricPeriodControl(minutes: Int, modifier: Modifier = Modifier, select: (Int) -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.metric_detail_period), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        MetricPeriodMenu(minutes, select)
    }
}
@Composable
private fun MetricPeriodMenu(minutes: Int, select: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = hapticClick { expanded = true }) { Text(metricPeriodLabel(minutes)); Icon(OrbitSymbols.ExpandMore, null, Modifier.size(20.dp)) }
        DropdownMenu(expanded, { expanded = false }) {
            listOf(5, 15, 30, 60, 120, 360, 1440).forEach { value ->
                DropdownMenuItem(text = { Text(metricPeriodLabel(value)) }, onClick = hapticClick { select(value); expanded = false },
                    modifier = Modifier.semantics { selected = value == minutes },
                    trailingIcon = { if (value == minutes) Icon(OrbitSymbols.Check, null) })
            }
        }
    }
}
@Composable
private fun metricPeriodLabel(minutes: Int) = stringResource(if (minutes < 60) R.string.metric_detail_minutes else R.string.auto_hours,
    if (minutes < 60) minutes else minutes / 60)
@Composable
private fun MetricStatPair(label: String, value: String, secondLabel: String, secondValue: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth / LocalDensity.current.fontScale < 280.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricStat(label, value); MetricStat(secondLabel, secondValue)
        } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricStat(label, value, Modifier.weight(1f)); MetricStat(secondLabel, secondValue, Modifier.weight(1f))
        }
    }
}
@Composable
private fun MetricStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MetricDetailValueText(value, style = MaterialTheme.typography.titleLarge)
    }
}
@Composable
private fun MetricSignalContext(snapshot: DishySnapshot, obstructions: Int) {
    val recent = snapshot.isOnline && !snapshot.isFromCache && System.currentTimeMillis() - snapshot.timestamp in 0..10_000
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.metric_detail_link), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.metric_detail_signal_note), style = MaterialTheme.typography.bodyMedium)
            MetricStat(stringResource(R.string.metric_detail_above_noise), if (recent) signalLabel(snapshot.latency.isSnrAboveNoiseFloor) else stringResource(R.string.editorial_no_reading))
            MetricStatPair(stringResource(R.string.metric_detail_current_loss), if (recent) snapshot.latency.formattedDropRate else stringResource(R.string.editorial_no_reading),
                stringResource(R.string.metric_detail_obstruction), if (recent) snapshot.obstruction.formattedPercentage else stringResource(R.string.editorial_no_reading))
            Text(stringResource(R.string.metric_detail_obstruction_count, obstructions), style = MaterialTheme.typography.bodySmall)
        }
    }
}
