package io.github.strongsand.lshell

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.strongsand.lshell.beacon.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import java.util.Locale

@Composable
internal fun BeaconPowerDiagnosticScreen(repository: BeaconRepository, modifier: Modifier, onBack: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var session by remember { mutableStateOf(BeaconPowerSession()) }
    // This override belongs only to this development panel. No Beacon pairing/preferences change.
    var testAddress by rememberSaveable { mutableStateOf("") }
    var connectionRevision by remember { mutableIntStateOf(0) }
    var showAddress by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { now = SystemClock.elapsedRealtime(); delay(1000) }
        }
    }
    LaunchedEffect(repository, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // collectLatest cancels and joins the old HTTP stream before switching addresses.
            snapshotFlow { testAddress to connectionRevision }.collectLatest { (address, revision) ->
                val external = address.takeIf { it.isNotEmpty() }?.let {
                    BeaconPowerHttpClient(BeaconPowerEndpoint.parse(it))
                }
                beaconPowerUpdates(read = {
                    if (external == null) repository.readPowerDiagnostic() else external.read()
                }).collect { response ->
                    if (testAddress == address && connectionRevision == revision) response.fold(
                        onSuccess = { status ->
                            val received = SystemClock.elapsedRealtime()
                            now = received
                            session = session.accept(status.device.id,
                                status.electrical ?: BeaconPowerPayload(BeaconPowerCondition.WAITING), received)
                        },
                        onFailure = { error -> session = session.failed(error) })
                }
            }
        }
    }
    val reading = session.visible(now)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = hapticClick(action = onBack)) { Icon(OrbitSymbols.ArrowBack, stringResource(R.string.beacon_back)) }
            Text(stringResource(R.string.power_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        }
        // Origin stays visible outside scrolling, including retained stale/offline chart observations.
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = DashDesign.section,
            color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(OrbitSymbols.Info, null)
                Text(stringResource(when (session.origin) {
                    BeaconPowerOrigin.SIMULATED -> R.string.power_simulated
                    BeaconPowerOrigin.REAL -> R.string.power_real
                    BeaconPowerOrigin.UNAVAILABLE -> R.string.power_origin_unavailable
                }), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.power_diagnostic_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.power_refresh_help), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(if (testAddress.isEmpty()) R.string.power_source_configured else R.string.power_source_test),
                style = MaterialTheme.typography.labelLarge)
            if (testAddress.isNotEmpty()) Text(testAddress, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = hapticClick { showAddress = true }) { Text(stringResource(R.string.power_test_address)) }
            if (session.condition(now) == BeaconPowerCondition.UNSUPPORTED)
                Text(stringResource(R.string.power_module_missing_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = DashDesign.hero, color = amoledCardColor(MaterialTheme.colorScheme.primaryContainer),
                contentColor = amoledCardContent(MaterialTheme.colorScheme.onPrimaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.power_sensor), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(session.condition(now).label()), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.headlineSmall)
                    reading?.let { Text(stringResource(when (it.circuit) {
                        "battery_bus" -> R.string.power_circuit_battery
                        "solar_input" -> R.string.power_circuit_solar
                        else -> R.string.power_circuit_load
                    }) + " · ${it.circuitIndex}", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            PowerDiagnosticPair(
                { m -> PowerDiagnosticMetric(R.string.power_voltage, reading?.voltageV, "V", R.string.power_volts_spoken, m) },
                { m -> PowerDiagnosticMetric(R.string.power_current, reading?.currentA, "A", R.string.power_amps_spoken, m) })
            PowerDiagnosticPair(
                { m -> PowerDiagnosticMetric(R.string.power_power, reading?.powerW, "W", R.string.power_watts_spoken, m) },
                { m -> PowerDiagnosticMetric(R.string.power_energy, reading?.energyWh, "Wh", R.string.power_wh_spoken, m) })
            if (reading?.circuit == "battery_bus") Text(stringResource(R.string.power_signed_current), style = MaterialTheme.typography.bodySmall)
            if (reading != null && (reading.batteryPercent != null || reading.remainingWh != null || reading.charging != null || reading.autonomySeconds != null)) {
                Surface(shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        reading.batteryPercent?.let { InfoRow(stringResource(if (session.origin == BeaconPowerOrigin.SIMULATED) R.string.power_battery else R.string.power_battery_real), "${powerNumber(it)} %") }
                        reading.remainingWh?.let { InfoRow(stringResource(R.string.power_remaining), "${powerNumber(it)} Wh") }
                        reading.charging?.let { InfoRow(stringResource(R.string.power_charging), stringResource(if (it) R.string.power_yes else R.string.power_no)) }
                        reading.autonomySeconds?.let { InfoRow(stringResource(R.string.power_autonomy), "${powerNumber(it)} s") }
                    }
                }
            }
            session.payload.reading?.let { last ->
                Text(stringResource(R.string.power_capture,
                    String.format(Locale.getDefault(), "%.3f", last.captureUptimeMs / 1000.0), last.bootId),
                    style = MaterialTheme.typography.bodySmall)
                session.ageMs(now)?.let { age -> Text(stringResource(R.string.power_age, age / 1000),
                    style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.power_no_utc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(when (last.energyBasis) {
                    "net_since_sensor_start" -> R.string.power_basis_start
                    "device_counter" -> R.string.power_basis_device
                    else -> R.string.power_basis_unknown
                }), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.power_rounding_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            PowerDiagnosticChart(session.points, voltage = false)
            PowerDiagnosticChart(session.points, voltage = true)
            Text(stringResource(R.string.power_chart_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showAddress) PowerTestAddressDialog(testAddress,
        onDismiss = { showAddress = false },
        onApply = { address ->
            testAddress = address
            connectionRevision++
            session = BeaconPowerSession()
            showAddress = false
        })
}

@Composable
private fun PowerTestAddressDialog(current: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(current) }
    val endpoint = remember(draft) { runCatching { BeaconPowerEndpoint.parse(draft) }.getOrNull() }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.power_test_address)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.power_test_help))
                OutlinedTextField(draft, onValueChange = { draft = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.power_test_host)) },
                    placeholder = { Text("192.168.1.10:8765") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = draft.isNotBlank() && endpoint == null,
                    supportingText = { if (draft.isNotBlank() && endpoint == null) Text(stringResource(R.string.power_test_invalid)) })
                TextButton(onClick = hapticClick { onApply("") }) { Text(stringResource(R.string.power_use_configured)) }
            }
        },
        confirmButton = { TextButton(enabled = endpoint != null,
            onClick = hapticClick { endpoint?.let { onApply(it.address) } }) { Text(stringResource(R.string.power_connect_test)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.beacon_not_now)) } })
}

@Composable
private fun PowerDiagnosticPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth / LocalDensity.current.fontScale < 320.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            first(Modifier.fillMaxWidth()); second(Modifier.fillMaxWidth())
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { first(Modifier.weight(1f)); second(Modifier.weight(1f)) }
    }
}

@Composable
private fun PowerDiagnosticMetric(label: Int, value: Double?, unit: String, spokenUnit: Int, modifier: Modifier) {
    val caption = stringResource(label)
    val display = value?.let { powerNumber(it, when (unit) { "V", "A" -> 3; "Wh" -> 4; else -> 2 }) } ?: stringResource(R.string.test_metric_dash)
    val speech = value?.let { stringResource(spokenUnit, display) } ?: stringResource(R.string.test_metric_unavailable)
    Surface(modifier.clearAndSetSemantics { contentDescription = "$caption: $speech" },
        shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(caption, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TestValueText(display, MaterialTheme.typography.headlineLarge, MaterialTheme.colorScheme.primary)
            Text(unit, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun powerNumber(value: Double, decimals: Int = 2): String = formatBeaconPowerNumber(value, decimals)

@Composable
private fun PowerDiagnosticChart(points: List<BeaconPowerPoint>, voltage: Boolean) {
    val palette = MaterialTheme.colorScheme
    val title = stringResource(if (voltage) R.string.power_voltage else R.string.power_power)
    val unit = if (voltage) "V" else "W"
    val samples = remember(points, voltage) { points.map { it to if (voltage) it.voltage else it.power } }
    val values = remember(samples) { samples.mapNotNull { it.second } }
    val scale = remember(values) { beaconPowerPlotScale(values) }
    val empty = stringResource(R.string.power_no_samples)
    val range = if (values.isEmpty()) empty else stringResource(R.string.power_chart_range,
        powerNumber(values.min(), if (voltage) 3 else 2), powerNumber(values.max(), if (voltage) 3 else 2), unit, values.size)
    Surface(shape = DashDesign.section, color = palette.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(range, style = MaterialTheme.typography.bodySmall)
            if (values.isNotEmpty()) Text(stringResource(R.string.power_chart_scale,
                powerNumber(scale.low, if (voltage) 3 else 2), powerNumber(scale.high, if (voltage) 3 else 2), unit),
                style = MaterialTheme.typography.labelSmall)
            if (points.isNotEmpty()) Text(stringResource(R.string.power_chart_time,
                powerNumber(points.first().uptimeMs / 1000.0), powerNumber(points.last().uptimeMs / 1000.0)),
                style = MaterialTheme.typography.labelSmall)
            Canvas(Modifier.fillMaxWidth().height(140.dp).clearAndSetSemantics { contentDescription = "$title. $range" }
                .drawWithCache {
                    val path = Path(); val dots = ArrayList<Offset>()
                    val lo = scale.low
                    val span = scale.span
                    val first = points.firstOrNull()?.uptimeMs ?: 0L
                    val timeSpan = ((points.lastOrNull()?.uptimeMs ?: first) - first).coerceAtLeast(1L)
                    val inset = 6.dp.toPx()
                    var connected = false
                    samples.forEach { (sample, value) ->
                        if (value == null) connected = false else {
                            val point = Offset(inset + (size.width - inset * 2) * ((sample.uptimeMs - first).toDouble() / timeSpan).toFloat(),
                                size.height - inset - (size.height - inset * 2) * ((value - lo) / span).toFloat())
                            if (connected && !sample.breakBefore) path.lineTo(point.x, point.y) else path.moveTo(point.x, point.y)
                            dots += point; connected = true
                        }
                    }
                    onDrawBehind {
                        val zeroY = size.height - inset - (size.height - inset * 2) * ((0.0 - lo) / span).toFloat()
                        drawLine(palette.outlineVariant, Offset(inset, zeroY), Offset(size.width - inset, zeroY))
                        drawPath(path, palette.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                        dots.forEach { drawCircle(palette.primary, 2.dp.toPx(), it) }
                    }
                }) {}
        }
    }
}

private fun BeaconPowerCondition.label(): Int = when (this) {
    BeaconPowerCondition.WAITING -> R.string.power_waiting
    BeaconPowerCondition.UNSUPPORTED -> R.string.power_unsupported
    BeaconPowerCondition.INVALID -> R.string.power_invalid
    BeaconPowerCondition.VALID -> R.string.power_valid
    BeaconPowerCondition.PARTIAL -> R.string.power_partial
    BeaconPowerCondition.SENSOR_DISCONNECTED -> R.string.power_sensor_disconnected
    BeaconPowerCondition.STALE -> R.string.power_stale
    BeaconPowerCondition.OFFLINE -> R.string.power_offline
}
