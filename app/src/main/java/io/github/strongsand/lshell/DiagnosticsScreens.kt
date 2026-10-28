package io.github.strongsand.lshell

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.core.data.TelemetrySample
import io.github.strongsand.lshell.core.data.LivePowerReading
import io.github.strongsand.lshell.core.model.DishState
import io.github.strongsand.lshell.core.model.DishySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

internal enum class OutageRange(val label: Int) {
    TODAY(R.string.ui_period_today), YESTERDAY(R.string.ui_period_yesterday),
    WEEK(R.string.ui_period_week), MONTH(R.string.ui_period_month);

    companion object {
        // Restore the previous UI-only saved values without using translated text as state.
        fun fromSavedValue(value: String): OutageRange = entries.firstOrNull { it.name == value } ?: when (value) {
            "Ontem" -> YESTERDAY
            "7 dias" -> WEEK
            "30 dias" -> MONTH
            else -> TODAY
        }
    }
}

private val outageRangeSaver = Saver<OutageRange, String>(
    save = { it.name }, restore = OutageRange::fromSavedValue
)

enum class DiagnosticDestination { HEALTH, ENERGY, TIMELINE, OUTAGES, SINGLE_TEST, EXTREME_TEST, EXTREME_HISTORY }

@Composable
fun DiagnosticsHomeScreen(modifier: Modifier = Modifier, onOpen: (DiagnosticDestination) -> Unit) {
    val entries = listOf(
        Triple(DiagnosticDestination.HEALTH, androidx.compose.ui.res.stringResource(R.string.function_health), OrbitSymbols.HealthAndSafety),
        Triple(DiagnosticDestination.OUTAGES, androidx.compose.ui.res.stringResource(R.string.function_outages), OrbitSymbols.CloudOff),
        Triple(DiagnosticDestination.TIMELINE, androidx.compose.ui.res.stringResource(R.string.function_history), OrbitSymbols.History),
        Triple(DiagnosticDestination.ENERGY, androidx.compose.ui.res.stringResource(R.string.function_energy), OrbitSymbols.Bolt),
        Triple(DiagnosticDestination.SINGLE_TEST, androidx.compose.ui.res.stringResource(R.string.function_speed), OrbitSymbols.Speed),
        Triple(DiagnosticDestination.EXTREME_TEST, androidx.compose.ui.res.stringResource(R.string.function_extreme), OrbitSymbols.ElectricBolt),
        Triple(DiagnosticDestination.EXTREME_HISTORY, androidx.compose.ui.res.stringResource(R.string.editorial_extreme_history), OrbitSymbols.History)
    )
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(androidx.compose.ui.res.stringResource(R.string.function_diagnostics), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.ui_installation_stability_and_events_explained_using_dish_data),
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
                    Icon(OrbitSymbols.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        HealthTone.GOOD -> stringResource(R.string.ui_excellent)
        HealthTone.CAUTION -> stringResource(R.string.rich_attention)
        HealthTone.DANGER -> stringResource(R.string.ui_problem_detected)
        HealthTone.UNKNOWN -> stringResource(R.string.editorial_no_reading)
    }
    val explanation = when {
        !snapshot.isOnline -> stringResource(R.string.ui_the_dish_is_not_responding_right_now)
        snapshot.alerts.hasCriticalAlerts -> alerts.firstOrNull()?.let { telemetryLabel(it) } ?: dishStateLabel(snapshot.state)
        obstruction >= 1f -> stringResource(R.string.ui_health_obstruction, oneDecimal(obstruction))
        ethernet != null && ethernet < 1000 -> stringResource(R.string.ui_health_ethernet, ethernet)
        snapshot.latency.isSnrAboveNoiseFloor == false -> stringResource(R.string.ui_signal_below_the_expected_noise_floor)
        alerts.isNotEmpty() -> telemetryLabel(alerts.first())
        else -> stringResource(R.string.ui_no_relevant_condition_detected)
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { DiagnosticHeader(androidx.compose.ui.res.stringResource(R.string.function_health), onBack) }
        item {
            val colors = healthColors(tone)
            Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero, color = amoledCardColor(colors.container),
                contentColor = amoledCardContent(colors.foreground)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (tone == HealthTone.GOOD) OrbitSymbols.Verified else OrbitSymbols.HealthAndSafety,
                        null, Modifier.size(42.dp))
                    Text(title, style = MaterialTheme.typography.headlineLarge)
                    Text(explanation, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        item {
            DiagnosticCard(stringResource(R.string.ui_physical_installation)) {
                DiagnosticStateRow(stringResource(R.string.report_fov), snapshot.obstruction.formattedPercentage,
                    obstruction < 1f && !snapshot.obstruction.currentlyObstructed)
                DiagnosticStateRow(stringResource(R.string.ui_mast), if (snapshot.alerts.mastNotNearVertical) stringResource(R.string.ui_not_vertical) else stringResource(R.string.ui_no_alert),
                    !snapshot.alerts.mastNotNearVertical)
                InfoRow(stringResource(R.string.ui_azimuth), if (hasOrientation) "${oneDecimal(snapshot.deviceInfo.boresightAzimuthDeg)}°" else stringResource(R.string.beacon_unknown), missing = !hasOrientation)
                InfoRow(stringResource(R.string.ui_elevation), if (hasOrientation) "${oneDecimal(snapshot.deviceInfo.boresightElevationDeg)}°" else stringResource(R.string.ui_not_provided), missing = !hasOrientation)
            }
        }
        item {
            DiagnosticCard(stringResource(R.string.ui_operation)) {
                DiagnosticStateRow(stringResource(R.string.editorial_signal), signalLabel(snapshot.latency.isSnrAboveNoiseFloor),
                    snapshot.latency.isSnrAboveNoiseFloor)
                DiagnosticStateRow(stringResource(R.string.rich_temperature), if (snapshot.alerts.thermalThrottle || snapshot.alerts.thermalShutdown)
                    stringResource(R.string.ui_thermal_throttling) else stringResource(R.string.ui_no_alert), !snapshot.alerts.thermalThrottle && !snapshot.alerts.thermalShutdown)
                DiagnosticStateRow(androidx.compose.ui.res.stringResource(R.string.function_energy), if (snapshot.alerts.powerSupplyThermalThrottle) stringResource(R.string.ui_power_supply_throttling) else stringResource(R.string.ui_no_alert),
                    !snapshot.alerts.powerSupplyThermalThrottle)
                DiagnosticStateRow(stringResource(R.string.domain_router_ethernet), ethernet?.let { "$it Mbps" } ?: stringResource(R.string.ui_not_provided), ethernet?.let { it >= 1000 })
            }
        }
        item {
            DiagnosticCard(androidx.compose.ui.res.stringResource(if (LocalExperience.current.technical) R.string.editorial_term else R.string.function_dish)) {
                InfoRow(stringResource(R.string.ui_state), dishStateLabel(snapshot.state))
                InfoRow(stringResource(R.string.ui_hardware), snapshot.deviceInfo.hardwareVersion)
                InfoRow(stringResource(R.string.ui_software), snapshot.deviceInfo.softwareVersion)
                if (alerts.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    alerts.forEach { Text("• ${telemetryLabel(it)}", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
fun EnergyScreen(samples: List<TelemetrySample>, modifier: Modifier = Modifier,
    livePower: LivePowerReading? = null, snapshot: io.github.strongsand.lshell.core.model.DishySnapshot? = null,
    onBack: () -> Unit) {
    val context = LocalContext.current
    var readings by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var yesterdayReadings by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var powerLoading by remember { mutableStateOf(true) }
    var powerFailed by remember { mutableStateOf(false) }
    var powerRetry by remember { mutableIntStateOf(0) }
    val todayStart = dayStart(System.currentTimeMillis())
    val latestPowerMinute = samples.lastOrNull {
        it.powerWatts?.let { watts -> watts.isFinite() && watts > 0f } == true
    }?.timestamp?.div(60_000L)
    LaunchedEffect(todayStart, latestPowerMinute, powerRetry) {
        powerLoading = true; powerFailed = false
        try {
            val now = System.currentTimeMillis()
            val yesterdayStart = todayStart - 86_400_000L
            val comparableYesterdayEnd = yesterdayStart + (now - todayStart)
            val all = withContext(Dispatchers.IO) {
                HistoryStore(context).use { it.between(yesterdayStart, now + 1_000L) }
                    .filter { it.powerWatts?.let { watts -> watts.isFinite() && watts > 0f } == true }
            }
            readings = all.filter { it.timestamp >= todayStart }
            yesterdayReadings = all.filter { it.timestamp in yesterdayStart until comparableYesterdayEnd }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { powerFailed = true }
        finally { powerLoading = false }
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
        item { Column {
            DiagnosticHeader(stringResource(R.string.function_energy), onBack)
            snapshot?.let { PoeVoltageCard(it) }
        } }
        item("power-refresh") {
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (powerLoading && currentReadings.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxSize())
            }
        }
        if (powerFailed) item("power-error") {
            Column {
                Text(stringResource(R.string.metric_detail_load_error), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !powerLoading, onClick = hapticClick { powerRetry++ }) {
                    Text(stringResource(R.string.health_retry))
                }
            }
        }
        if (currentReadings.isEmpty() && powerLoading) item("power-loading") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ux_power_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } else if (currentReadings.isEmpty() && !powerFailed) item {
            Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExpressiveIcon(OrbitSymbols.Bolt, MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(androidx.compose.ui.res.stringResource(R.string.editorial_no_power), style = MaterialTheme.typography.headlineMedium)
                    Text(androidx.compose.ui.res.stringResource(R.string.editorial_no_power_detail),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (currentReadings.isNotEmpty()) item {
            val summary = remember(currentReadings) { calculateEnergy(currentReadings) }
            val latest = currentReadings.last()
            val currentPower = recentAntennaPower(livePower, System.currentTimeMillis())
            val yesterday = remember(yesterdayReadings) {
                yesterdayReadings.takeIf { it.size >= 2 }?.let(::calculateEnergy)
            }
            val powerPlot = remember(currentReadings) {
                MetricHistorySeriesBuilder.build(currentReadings, HistoricalMetric.POWER, todayStart, latest.timestamp + 1)
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero,
                    color = amoledCardColor(MaterialTheme.colorScheme.primaryContainer), contentColor = amoledCardContent(MaterialTheme.colorScheme.onPrimaryContainer)) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(OrbitSymbols.Bolt, null, Modifier.size(24.dp))
                            Text(stringResource(R.string.editorial_today_energy), Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleMedium)
                        }
                        Text(formatEnergy(summary.kwh), style = MaterialTheme.typography.displayMedium)
                        Text(stringResource(R.string.polish_energy_period,
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(todayStart)),
                            clock(todayStart), clock(latest.timestamp)), style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(OrbitSymbols.Schedule, null, Modifier.size(18.dp))
                            Text(stringResource(R.string.editorial_monitored_period, duration(summary.monitoredMs)),
                                style = MaterialTheme.typography.labelLarge)
                        }
                        yesterday?.takeIf { it.kwh > 0f && it.monitoredMs >= summary.monitoredMs * 4 / 5 }?.let { previous ->
                            val difference = (summary.kwh - previous.kwh) / previous.kwh * 100f
                            val value = oneDecimal(abs(difference)) + "%"
                            val direction = stringResource(if (difference <= 0f) R.string.editorial_decreased else R.string.editorial_increased, value)
                            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(if (difference <= 0f) OrbitSymbols.TrendingDown else OrbitSymbols.TrendingUp, null, Modifier.size(18.dp))
                                Text(stringResource(R.string.editorial_vs_yesterday) + ": " + direction,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                ReportSummaryGrid(listOf(
                    { EnergyTile(OrbitSymbols.ElectricBolt, stringResource(R.string.editorial_current_power),
                        currentPower?.let { "${oneDecimal(it)} W" } ?: "— W",
                        stringResource(R.string.editorial_latest_sample) + " · " + clock(latest.timestamp), prominent = true) },
                    { EnergyTile(OrbitSymbols.Timeline, stringResource(R.string.editorial_mean_power), "${oneDecimal(summary.average)} W") },
                    { EnergyTile(OrbitSymbols.TrendingDown, stringResource(R.string.report_minimum), "${oneDecimal(summary.minimum)} W") },
                    { EnergyTile(OrbitSymbols.TrendingUp, stringResource(R.string.report_maximum), "${oneDecimal(summary.maximum)} W",
                        stringResource(R.string.polish_peak_time, clock(summary.peakAt))) }
                ), minimumWidthDp = 300f)
                MetricDetailChart(powerPlot, stringResource(R.string.polish_power_chart), "W")
                Text(stringResource(R.string.polish_energy_note), Modifier.padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun EnergyTile(icon: ImageVector, label: String, value: String, note: String? = null, prominent: Boolean = false) {
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
        color = amoledCardColor(if (prominent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
        contentColor = amoledCardContent(if (prominent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)) {
        Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(20.dp))
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            }
            Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (note != null) Text(note, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun SmartTimelineScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    HistoryScreen(modifier, onBack = onBack)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutagesScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    var period by rememberSaveable(stateSaver = outageRangeSaver) { mutableStateOf(OutageRange.TODAY) }
    var outages by remember { mutableStateOf<List<OutageContext>>(emptyList()) }
    var report by remember { mutableStateOf<DailyReport?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val request = period to retry
    var load by remember { mutableStateOf(UiLoadStatus(request)) }
    val phase = load.phaseFor(request)
    LaunchedEffect(period, retry) {
        load = UiLoadStatus(request)
        try {
            val now = System.currentTimeMillis()
            val (start, end) = when (period) {
                OutageRange.YESTERDAY -> dayStart(now) - 86_400_000L to dayStart(now)
                OutageRange.WEEK -> now - 7L * 86_400_000L to now
                OutageRange.MONTH -> now - 30L * 86_400_000L to now
                else -> dayStart(now) to now
            }
            val rows = withContext(Dispatchers.IO) { HistoryStore(context).use { it.between(start, end) } }
            val (generated, contexts) = withContext(Dispatchers.Default) {
                ensureActive()
                val generated = DailyReportGenerator.generate(rows, start, end)
                ensureActive()
                val lookup = OutageHistoryLookup(rows) { ensureActive() }
                generated to generated.interruptions.map { outage ->
                    ensureActive()
                    lookup.context(outage)
                }
            }
            report = generated
            outages = contexts
            load = load.completed(outages.isEmpty())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { load = UiLoadStatus(request).failed() }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            DiagnosticHeader(androidx.compose.ui.res.stringResource(R.string.function_outages), onBack)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutageRange.entries.forEach { option ->
                    FilterChip(period == option,
                        onClick = if (period == option) ({}) else hapticClick { period = option },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text(stringResource(option.label)) })
                }
            }
        }
        item("summary") {
            DiagnosticCard(stringResource(period.label)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 142.dp), contentAlignment = Alignment.CenterStart) {
                    when (phase) {
                        UiLoadPhase.LOADING -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.ux_outages_loading), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.ux_outages_loading_detail),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        UiLoadPhase.ERROR -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.ux_outages_error), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = hapticClick { retry++ }) { Text(stringResource(R.string.health_retry)) }
                        }
                        UiLoadPhase.LOADED, UiLoadPhase.EMPTY -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(pluralStringResource(R.plurals.ui_outages, outages.size, outages.size),
                                style = MaterialTheme.typography.headlineMedium)
                            InfoRow(stringResource(R.string.ui_offline_time), duration(report?.offlineMs ?: 0L))
                            InfoRow(stringResource(R.string.ui_longest_outage), duration(outages.maxOfOrNull { it.outage.durationMs } ?: 0L))
                            report?.availabilityPercent?.let { InfoRow(stringResource(R.string.ui_observed_availability), "${twoDecimals(it)}%") }
                        }
                    }
                }
            }
        }
        if (phase == UiLoadPhase.EMPTY) item("empty") { DiagnosticEmpty(stringResource(R.string.ui_no_outages_recorded_in_this_period)) }
        else if (phase == UiLoadPhase.LOADED) items(outages, key = { it.outage.start }) { OutageCard(it) }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun OutageCard(context: OutageContext) {
    var expanded by rememberSaveable(context.outage.start) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val likely = context.during?.dishState?.let { stateName ->
        runCatching { DishState.valueOf(stateName) }.getOrNull()
    }?.takeIf { it != DishState.CONNECTED && it != DishState.UNKNOWN }
        ?.let { dishStateLabel(it) } ?: androidx.compose.ui.res.stringResource(R.string.editorial_cause_unknown)
    Surface(onClick = hapticClick { expanded = !expanded }, interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().pressMotion(interaction, .98f).animateContentSize(if (LocalOrbitMotion.current) spring(dampingRatio = .82f, stiffness = 380f) else snap()),
        shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpressiveIcon(OrbitSymbols.CloudOff, MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ui_outage_time, clock(context.outage.start)), style = MaterialTheme.typography.titleMedium)
                    Text(duration(context.outage.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) OrbitSymbols.ExpandLess else OrbitSymbols.ExpandMore, null)
            }
            if (expanded) {
                InfoRow(stringResource(R.string.ui_before), context.before?.let { stringResource(R.string.ui_latency_loss, oneDecimal(it.latencyMs), oneDecimal(it.dropPercent)) } ?: stringResource(R.string.editorial_no_reading),
                    missing = context.before == null)
                InfoRow(androidx.compose.ui.res.stringResource(R.string.editorial_during), storedDishStateLabel(context.during?.dishState),
                    missing = context.during?.dishState == null)
                InfoRow(androidx.compose.ui.res.stringResource(R.string.editorial_after), context.after?.dishState?.let { storedDishStateLabel(it) } ?: if (context.outage.resolved) androidx.compose.ui.res.stringResource(R.string.editorial_restored) else androidx.compose.ui.res.stringResource(R.string.editorial_no_reading),
                    missing = context.after?.dishState == null && !context.outage.resolved)
                InfoRow(stringResource(R.string.ui_previous_obstruction), context.before?.let { "${oneDecimal(it.obstructionPercent)}%" } ?: stringResource(R.string.editorial_no_reading),
                    missing = context.before == null)
                InfoRow(androidx.compose.ui.res.stringResource(R.string.editorial_observed_state), telemetryLabel(likely))
            }
        }
    }
}

@Composable
private fun DiagnosticHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = hapticClick(action = onBack)) { Icon(OrbitSymbols.ArrowBack, stringResource(R.string.antenna_back)) }
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
        Icon(when (okay) { true -> OrbitSymbols.CheckCircle; false -> OrbitSymbols.Warning; null -> OrbitSymbols.Info }, null,
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

internal fun timelineIcon(title: String) = when (HistoryEventCodec.parse(title).type) {
    CanonicalHistoryEventType.CONNECTION_LOST, CanonicalHistoryEventType.CONNECTION_RESTORED,
    CanonicalHistoryEventType.STATE, CanonicalHistoryEventType.STATE_TRANSITION -> OrbitSymbols.CloudOff
    CanonicalHistoryEventType.LATENCY_PEAK -> OrbitSymbols.Speed
    CanonicalHistoryEventType.PACKET_LOSS -> OrbitSymbols.NetworkCheck
    CanonicalHistoryEventType.DEVICE_RESTART -> OrbitSymbols.RestartAlt
    CanonicalHistoryEventType.ETHERNET_CHANGED -> OrbitSymbols.SettingsEthernet
    else -> OrbitSymbols.Info
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
@Composable
private fun duration(ms: Long): String = when {
    ms < 60_000L -> stringResource(R.string.ui_duration_seconds_short, ms / 1_000L)
    ms < 3_600_000L -> stringResource(R.string.ui_duration_minutes_seconds, ms / 60_000L, ms / 1_000L % 60L)
    else -> stringResource(R.string.ui_duration_hours_minutes, ms / 3_600_000L, ms / 60_000L % 60L)
}
