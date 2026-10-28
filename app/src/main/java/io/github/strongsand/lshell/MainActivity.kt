// =========================================================================
// PROJETO: L-Shell Orbit (L-Shell Orbit Android)
// ARQUIVO: MainActivity.kt
// PACOTE: io.github.strongsand.lshell
// VERSÃO: v1.3 (2026-09-20)
//
// CHANGELOG:
// - [v1.3 | 2026-09-20]: Substituída a delegação 'by collectAsState()' por acesso direto
//   ao estado reativo '.collectAsState(initial = DishySnapshot()).value'. Isso elimina
//   definitivamente a incompatibilidade do compilador Kotlin 2.1 (K2) com o operador
//   'getValue' em delegates locais, mantendo 100% da reatividade do Compose.
// - [v1.2 | 2026-09-20]: Padronização de pacotes para 'io.github.strongsand.lshell'.
// - [v1.0 | 2026-09-20]: Criação da interface com Material 3 Expressive e temas dinâmicos.
// =========================================================================

package io.github.strongsand.lshell
import io.github.strongsand.lshell.core.network.grpc.terminalHistorySize

import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import io.github.strongsand.lshell.core.data.DishyRepository
import io.github.strongsand.lshell.core.data.TelemetrySample
import io.github.strongsand.lshell.core.data.LivePowerReading
import io.github.strongsand.lshell.core.model.DishState
import io.github.strongsand.lshell.core.model.DishySnapshot
import io.github.strongsand.lshell.beacon.BeaconState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val runtime by lazy { TelemetryRuntime.get(applicationContext) }
    private val repository get() = runtime.repository
    private val uiOwner = "${TelemetryLeases.UI}:${System.identityHashCode(this)}"
    private val requestedTestId = mutableStateOf<Long?>(null)
    private val requestedTab = mutableStateOf<String?>(null)
    private val requestedSessionId = mutableStateOf<String?>(null)
    private val requestedReportEnd = mutableStateOf<Long?>(null)
    private val launchRequestId = mutableIntStateOf(0)

    private fun applyLaunchIntent(source: Intent?) {
        requestedTestId.value = source?.getLongExtra(ConnectionTestService.EXTRA_ID, 0L)?.takeIf { it > 0L }
        requestedTab.value = source?.getStringExtra("open_tab")
        requestedSessionId.value = source?.getStringExtra(TrafficSessionService.EXTRA_SESSION_ID)
        requestedReportEnd.value = source?.getLongExtra("report_end", -1L)?.takeIf { it > 0L }
        if (requestedTab.value != null) launchRequestId.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (java.io.File(filesDir, "backup_restore_v1/journal.json").exists()) {
            startAfterBackupRecovery(savedInstanceState)
        } else initializeApp(savedInstanceState)
    }

    private fun startAfterBackupRecovery(savedInstanceState: Bundle?) {
        val failed = mutableStateOf(false)
        enableEdgeToEdge()
        setContent { BackupRecoveryScreen(failed.value) { startAfterBackupRecovery(savedInstanceState) } }
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { BackupGate.ensureRecovered(applicationContext) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed.value = true; return@launch }
            initializeApp(savedInstanceState)
        }
    }

    private fun initializeApp(savedInstanceState: Bundle?) {
        lifecycleScope.launch(Dispatchers.IO) { BackupCoordinator(applicationContext).cleanupOrphans() }
        // Keep launch request identity stable across recreation. A genuinely
        // new notification Intent still advances it through onNewIntent.
        applyLaunchIntent(intent)
        if (savedInstanceState?.containsKey("launch_request_id") == true) {
            launchRequestId.intValue = savedInstanceState.getInt("launch_request_id")
        }
        cancelWidgetRefresh(this)
        DailyReportScheduler.catchUpOrSchedule(this)
        ConnectionHealthSchedule.schedule(this)
        AutomaticTestSchedule.configure(this)
        lifecycleScope.launch {
            try { TrafficSessionController.get(applicationContext).restoreFromVisibleApp() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { TrafficSessionController.get(applicationContext).failed(null) }
        }
        enableEdgeToEdge()
        val experience = ExperiencePreferences.read(applicationContext)
        setContent {
            ExperienceProvider(experience) { LShellTheme {
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> {
                                runtime.acquire(uiOwner, 2000L)
                                lifecycleScope.launch { refreshLShellWidgets(applicationContext) }
                            }
                            Lifecycle.Event.ON_STOP -> runtime.release(uiOwner)
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        runtime.release(uiOwner)
                    }
                }

                // Acesso direto ao valor do State sem delegação 'by' para compatibilidade total com Kotlin 2.x
                val snapshot = repository.telemetryFlow.collectAsState(initial = DishySnapshot()).value
                val samples = repository.samples.collectAsState().value
                val livePower = repository.livePower.collectAsState().value
                val events = repository.events.collectAsState().value
                val terminalHistory = repository.history.collectAsState().value
                LShellApp(snapshot = snapshot, samples = samples, events = events,
                    initialTab = requestedTab.value,
                    initialTestId = requestedTestId.value,
                    initialReportEnd = requestedReportEnd.value,
                    initialRequestId = launchRequestId.intValue, initialSessionId = requestedSessionId.value,
                    livePower = livePower)
            } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("launch_request_id", launchRequestId.intValue)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchIntent(intent)
    }

    override fun onDestroy() {
        runtime.release(uiOwner)
        super.onDestroy()
    }
}

@Composable
fun LShellTheme(
    darkTheme: Boolean = LocalOrbitDark.current,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val amoled = LocalExperience.current.theme == OrbitTheme.AMOLED && darkTheme
    DisposableEffect(context, darkTheme, amoled) {
        (context as? ComponentActivity)?.let { activity ->
            if (amoled) activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
            ) else activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .apply { isAppearanceLightStatusBars = !darkTheme; isAppearanceLightNavigationBars = !darkTheme }
        }
        onDispose { }
    }
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            primary = Color(0xFFE7CE72), onPrimary = Color(0xFF3C3000),
            primaryContainer = Color(0xFF403819), onPrimaryContainer = Color(0xFFFFE690),
            secondary = Color(0xFFD2C89D), tertiary = Color(0xFFB5CDA9),
            background = Color(0xFF1C1B16), surface = Color(0xFF1C1B16),
            surfaceContainerLow = Color(0xFF24231D), surfaceContainer = Color(0xFF2A2922),
            surfaceContainerHigh = Color(0xFF333129),
            onSurface = Color(0xFFF0ECE0), onSurfaceVariant = Color(0xFFCFC8B7))
        else -> lightColorScheme(
            primary = Color(0xFF6B5400), onPrimary = Color.White,
            primaryContainer = Color(0xFFF4E6B5), onPrimaryContainer = Color(0xFF302500),
            secondary = Color(0xFF645C43), tertiary = Color(0xFF4F6B49),
            background = Color(0xFFF8F5EC), surface = Color(0xFFF8F5EC),
            surfaceContainerLow = Color(0xFFF4F0E5), surfaceContainer = Color(0xFFEFEBDD),
            surfaceContainerHigh = Color(0xFFEAE4D4),
            onSurface = Color(0xFF242218), onSurfaceVariant = Color(0xFF625D4E))
    }

    val resolvedColors = if (amoled)
        colorScheme.copy(
            background = Color.Black, onBackground = Color(0xFFF2F2F2),
            surface = Color.Black, onSurface = Color(0xFFF2F2F2),
            surfaceDim = Color.Black, surfaceBright = Color(0xFF181818),
            surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF080808),
            surfaceContainer = Color(0xFF0A0A0A), surfaceContainerHigh = Color(0xFF101010),
            surfaceContainerHighest = Color(0xFF181818),
            surfaceVariant = Color(0xFF101010), onSurfaceVariant = Color(0xFFBDBDBD),
            outline = Color(0xFF666666), outlineVariant = Color(0xFF242424),
            surfaceTint = Color.Black
        ) else colorScheme
    MaterialTheme(
        colorScheme = resolvedColors,
        typography = DashDesign.typography,
        shapes = Shapes(medium = DashDesign.section, large = DashDesign.section, extraLarge = DashDesign.hero),
        content = content
    )
}


@Composable
fun DashboardScreen(snapshot: DishySnapshot, samples: List<TelemetrySample> = emptyList(),
                    beaconState: BeaconState = BeaconState.NotConfigured,
                    onOpenBeacon: () -> Unit = {}, onOpenTraffic: () -> Unit = {},
                    onOpenSpeed: () -> Unit = {}, onOpenExtreme: () -> Unit = {},
                    onOpenExtremeHistory: () -> Unit = {}, onOpenReports: () -> Unit = {},
                    livePower: LivePowerReading? = null,
                    onOpenMetric: (MetricDetailKind) -> Unit = {}) {
    val latestPower = if (snapshot.isOnline) recentAntennaPower(livePower, System.currentTimeMillis()) else null
    val dashboardScroll = rememberScrollState()
    val editorScroll = rememberCardEditorScroll(dashboardScroll)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val wide = maxWidth >= (760 * fontScale.coerceAtMost(1.4f)).dp
        Column(Modifier.fillMaxSize().cardEditorViewport(editorScroll).verticalScroll(dashboardScroll)
            .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 1120.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DishStatusHeroCard(snapshot, latestPower)
                SectionHeading(stringResource(R.string.rich_connection_now),
                    stringResource(if (snapshot.isFromCache) R.string.rich_cached_metrics else R.string.rich_connection_hint),
                    Modifier.expressiveReveal("dashboard-heading", 55))
                AnimatedVisibility(snapshot.alerts.hasCriticalAlerts || snapshot.alerts.getActiveAlertsList().isNotEmpty(),
                    enter = fadeIn(tween(180)) + expandVertically(
                        spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(180))) {
                    AlertsCard(snapshot.alerts.getActiveAlertsList())
                }
                DashboardMetrics(snapshot, onOpenMetric = onOpenMetric, powerWatts = latestPower, editorScroll = editorScroll, samples = samples)
                BeaconHomeCard(beaconState, onOpenBeacon)
                HomeFeatureCards(onOpenSpeed, onOpenExtreme, onOpenExtremeHistory, onOpenReports)
                TrafficSessionHomeRow(onOpenTraffic)
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(DashDesign.gap)) {
                        Box(Modifier.weight(1f)) { ObstructionCard(snapshot) }
                        Box(Modifier.weight(1f)) { HardwareInfoCard(snapshot) }
                    }
                } else {
                    ObstructionCard(snapshot)
                    HardwareInfoCard(snapshot)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
internal fun DashboardMetrics(snapshot: DishySnapshot, modifier: Modifier = Modifier,
    onOpenMetric: (MetricDetailKind) -> Unit = {}, powerWatts: Float? = null,
    editorScroll: CardEditorScroll? = null, samples: List<TelemetrySample> = emptyList()) {
    val available = snapshot.isOnline || snapshot.isFromCache
    val defaults = listOf("download", "upload", "latency", "signal", "power")
    val arrangement = rememberCardArrangement("home_metrics", defaults, defaults.take(4).toSet())
    val host = editorScroll ?: rememberCardEditorScroll(rememberScrollState())
    val palette = MaterialTheme.colorScheme
    val baseline = remember(samples) {
        val now = System.currentTimeMillis()
        samples.takeLast(61).dropLast(1).filter { now - it.timestamp in 0..120_000L }
    }
    val intensities = remember(snapshot, powerWatts, baseline) {
        if (!snapshot.isOnline || snapshot.isFromCache) emptyMap() else mapOf(
            "download" to CardVisualPolicy.emphasis(snapshot.throughput.downlinkMbps, baseline.map { it.downloadMbps }, CardMetricProfile.THROUGHPUT),
            "upload" to CardVisualPolicy.emphasis(snapshot.throughput.uplinkMbps, baseline.map { it.uploadMbps }, CardMetricProfile.THROUGHPUT),
            "latency" to CardVisualPolicy.emphasis(snapshot.latency.popPingLatencyMs, baseline.map { it.latencyMs }, CardMetricProfile.LATENCY),
            "power" to CardVisualPolicy.emphasis(powerWatts, baseline.map { it.powerWatts ?: Float.NaN }, CardMetricProfile.ENERGY))
    }
    val sparklines = remember(samples) {
        mapOf("download" to MetricDetailKind.DOWNLOAD, "upload" to MetricDetailKind.UPLOAD,
            "latency" to MetricDetailKind.LATENCY, "power" to MetricDetailKind.POWER)
            .mapValues { (_, kind) -> HomeSparklinePolicy.series(samples, kind) }
    }
    val showSparklines = snapshot.isOnline && !snapshot.isFromCache
    val signal = if (available) signalLabel(snapshot.latency.isSnrAboveNoiseFloor) else stringResource(R.string.editorial_no_reading)
    Box(modifier) {
        EditableCardGrid(arrangement, host = host, label = { id ->
            stringResource(when (id) {
                "download" -> R.string.traffic_download; "upload" -> R.string.traffic_upload
                "latency" -> R.string.rich_latency; "signal" -> R.string.editorial_signal
                else -> R.string.editorial_dish_power
            })
        }) { id, compact ->
            when (id) {
                "download" -> MetricCard(Modifier.fillMaxWidth(), stringResource(R.string.traffic_download),
                    HomePresentation.reading(available, snapshot.throughput.downlinkBps, snapshot.throughput.formattedDownlink, "Mbps"),
                    icon = OrbitSymbols.Download, accentColor = palette.onPrimaryContainer,
                    containerColor = palette.primaryContainer, prominent = true, compact = compact,
                    emphasis = intensities["download"] ?: 0f, sparkline = if (showSparklines) sparklines["download"] else null,
                    onClick = if (arrangement.editing) null else ({ onOpenMetric(MetricDetailKind.DOWNLOAD) }))
                "upload" -> MetricCard(Modifier.fillMaxWidth(), stringResource(R.string.traffic_upload),
                    HomePresentation.reading(available, snapshot.throughput.uplinkBps, snapshot.throughput.formattedUplink, "Mbps"),
                    icon = OrbitSymbols.Upload, accentColor = palette.onSecondaryContainer,
                    containerColor = palette.secondaryContainer, compact = compact,
                    emphasis = intensities["upload"] ?: 0f, sparkline = if (showSparklines) sparklines["upload"] else null,
                    onClick = if (arrangement.editing) null else ({ onOpenMetric(MetricDetailKind.UPLOAD) }))
                "latency" -> MetricCard(Modifier.fillMaxWidth(), stringResource(R.string.rich_latency),
                    HomePresentation.reading(available, snapshot.latency.popPingLatencyMs, snapshot.latency.formattedLatency, "ms"),
                    if (available) stringResource(R.string.rich_loss,
                        snapshot.latency.formattedDropRate.takeIf { snapshot.latency.popPingDropRate.isFinite() } ?: stringResource(R.string.editorial_no_reading))
                    else stringResource(R.string.editorial_waiting_dish),
                    OrbitSymbols.Speed, palette.tertiary, compact = compact,
                    emphasis = intensities["latency"] ?: 0f, intensityTint = palette.error,
                    sparkline = if (showSparklines) sparklines["latency"] else null,
                    onClick = if (arrangement.editing) null else ({ onOpenMetric(MetricDetailKind.LATENCY) }))
                "signal" -> MetricCard(Modifier.fillMaxWidth(), stringResource(R.string.editorial_signal), signal,
                    stringResource(when {
                        !available || snapshot.latency.isSnrAboveNoiseFloor == null -> R.string.editorial_no_reading
                        snapshot.latency.isSnrAboveNoiseFloor == true -> R.string.rich_signal_above
                        else -> R.string.rich_signal_low
                    }), OrbitSymbols.SignalCellularAlt,
                    if (snapshot.latency.isSnrAboveNoiseFloor == false) palette.error else palette.primary,
                    compact = compact, onClick = if (arrangement.editing) null else ({ onOpenMetric(MetricDetailKind.SIGNAL) }))
                else -> EnergyMetricCard(powerWatts, available, compact = compact, emphasis = intensities["power"] ?: 0f,
                    sparkline = if (showSparklines) sparklines["power"] else null,
                    onClick = if (arrangement.editing) null else ({ onOpenMetric(MetricDetailKind.POWER) }))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DishStatusHeroCard(snapshot: DishySnapshot, powerWatts: Float? = null, modifier: Modifier = Modifier) {
    val available = snapshot.isOnline && !snapshot.isFromCache
    val alerts = snapshot.alerts.getActiveAlertsList()
    val connected = available && snapshot.state == DishState.CONNECTED
    val tone = when {
        !available -> HealthTone.UNKNOWN
        snapshot.alerts.hasCriticalAlerts -> HealthTone.DANGER
        !connected || alerts.isNotEmpty() || (snapshot.latency.popPingDropRate.isFinite() && snapshot.latency.popPingDropRate > 0f) -> HealthTone.CAUTION
        else -> HealthTone.GOOD
    }
    val status = if (snapshot.isFromCache) androidx.compose.ui.res.stringResource(R.string.editorial_no_dish_reading) else if (!snapshot.isOnline && snapshot.state == DishState.UNKNOWN)
        androidx.compose.ui.res.stringResource(R.string.editorial_waiting_dish) else dishStateLabel(snapshot.state)
    val panel by animateColorAsState(
        amoledCardColor(if (tone == HealthTone.DANGER) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer),
        tween(DashDesign.motionMillis), label = "terminal panel")
    val onPanel = amoledCardContent(if (tone == HealthTone.DANGER) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
    Surface(modifier.fillMaxWidth().expressiveReveal("dish-status", 20).animateContentSize(
        spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow)),
        shape = DashDesign.hero, color = panel, contentColor = onPanel) {
        Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(androidx.compose.ui.res.stringResource(if (LocalExperience.current.technical) R.string.editorial_terminal_brand else R.string.editorial_dish_brand), style = MaterialTheme.typography.titleSmall)
                    Text(snapshot.deviceInfo.hardwareVersion.ifBlank { stringResource(R.string.rich_terminal_overview) },
                        style = MaterialTheme.typography.bodySmall, color = onPanel.copy(alpha = 0.8f))
                }
            }
            Text(status, style = MaterialTheme.typography.headlineLarge)
            HomeStatusPill(when {
                !available -> stringResource(if (snapshot.isFromCache) R.string.rich_cached_status else R.string.rich_no_live_reading)
                alerts.isNotEmpty() -> pluralStringResource(R.plurals.rich_terminal_warnings, alerts.size, alerts.size)
                connected && snapshot.latency.popPingDropRate.isFinite() && snapshot.latency.popPingDropRate > 0f ->
                    androidx.compose.ui.res.stringResource(R.string.dashboard_loss, snapshot.latency.formattedDropRate)
                connected -> stringResource(R.string.rich_operating)
                else -> stringResource(R.string.rich_check_terminal)
            }, tone)
            HorizontalDivider(color = onPanel.copy(alpha = 0.12f))
            AntennaStatusIndicators(snapshot, powerWatts)
            if (snapshot.deviceInfo.softwareVersion.isNotBlank()) {
                Text(stringResource(R.string.rich_software, snapshot.deviceInfo.softwareVersion), style = MaterialTheme.typography.bodySmall,
                    color = onPanel.copy(alpha = 0.8f))
            }
        }
    }
}

private data class HeroIndicatorContent(val label: String, val value: String, val known: Boolean,
    val okay: Boolean, val icon: ImageVector, val spoken: String? = null)

@Composable
private fun AntennaStatusIndicators(snapshot: DishySnapshot, powerWatts: Float?) {
    val volts = rememberPoeInputVolts(snapshot)
    val available = snapshot.isOnline && !snapshot.isFromCache
    val ethernet = snapshot.deviceInfo.ethSpeedMbps
    val missing = stringResource(R.string.editorial_no_reading)
    val normal = stringResource(R.string.rich_normal)
    val attention = stringResource(R.string.rich_attention)
    val thermalOkay = !snapshot.alerts.thermalThrottle && !snapshot.alerts.thermalShutdown
    val indicators = buildList {
        add(HeroIndicatorContent(stringResource(R.string.rich_temperature),
            if (!available) missing else if (thermalOkay) normal else attention,
            available, thermalOkay, OrbitSymbols.Thermostat))
        add(HeroIndicatorContent(stringResource(R.string.editorial_power),
            if (available && powerWatts != null) String.format(java.util.Locale.getDefault(), "%.1f W", powerWatts) else missing,
            available && powerWatts != null, !snapshot.alerts.powerSupplyThermalThrottle, OrbitSymbols.Bolt))
        add(HeroIndicatorContent("Ethernet", if (!available || ethernet == null) missing
            else if (ethernet >= 1000) "Gigabit" else "$ethernet Mbps",
            available && ethernet != null, (ethernet ?: 0) >= 1000, OrbitSymbols.SettingsEthernet))
        if (volts != null) {
            val number = String.format(java.util.Locale.getDefault(), "%.1f", volts)
            add(HeroIndicatorContent(stringResource(R.string.poe_input_label), stringResource(R.string.poe_input_value, number),
                true, true, OrbitSymbols.Bolt, stringResource(R.string.poe_input_accessibility, number)))
        }
    }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 24)
    val labelStyle = MaterialTheme.typography.bodySmall
    val valueStyle = MaterialTheme.typography.titleMedium
    val density = LocalDensity.current
    val widths = remember(indicators.map { it.label to it.value }, labelStyle, valueStyle, measurer, density) {
        indicators.map { item ->
            val label = measurer.measure(item.label, labelStyle, softWrap = false).size.width
            val value = measurer.measure(item.value, valueStyle, softWrap = false).size.width
            with(density) { (maxOf(label + 18.dp.toPx(), value.toFloat()) + 4.dp.toPx()) / density.density }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = CardLayoutPolicy.indicatorColumns(maxWidth.value, widths)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            indicators.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    row.forEach { item -> Box(Modifier.weight(1f)) { HeroIndicator(item) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun HeroIndicator(item: HeroIndicatorContent) {
    val foreground = LocalContentColor.current
    val spoken = item.spoken ?: splitMetric(item.value).let { (number, unit) -> homeMetricSpoken(number, unit) }
    Column(Modifier.then(if (item.spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken }
        else Modifier.semantics(mergeDescendants = true) {}), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (item.known && !item.okay) OrbitSymbols.Warning else item.icon, null, Modifier.size(14.dp))
            // Column count was selected using this exact label/style/font scale, not a character budget.
            Text(item.label, style = MaterialTheme.typography.bodySmall, softWrap = false)
        }
        Text(item.value, Modifier.clearAndSetSemantics { contentDescription = spoken },
            style = MaterialTheme.typography.titleMedium, color = foreground)
    }
}

@Composable
fun EnergyMetricCard(powerWatts: Float?, available: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, onClick: (() -> Unit)? = null, emphasis: Float = 0f, sparkline: MetricHistorySeries? = null) {
    val validPower = powerWatts?.takeIf { it.isFinite() && it >= 0f }
    val colors = energyMetricColors()
    val sparklineSpace = remember { HomeSparklineSpace() }
    val textSpace = sparklineSpace.takeIf { sparkline != null }
    val value = validPower?.let { String.format(java.util.Locale.getDefault(), "%.1f", it) } ?: "—"
    val caption = stringResource(when {
        !available -> R.string.editorial_power_wait
        validPower == null -> R.string.editorial_no_power
        else -> R.string.rich_power_reading
    })
    val body: @Composable () -> Unit = {
        MetricIntensityBody(emphasis, colors.container, colors.chartLine, listOf(colors.onContainer)) {
        HomeSparkline(sparkline, sparklineSpace, colors.chartLine, Modifier.matchParentSize())
        BoxWithConstraints(Modifier.padding(horizontal = if (compact) 12.dp else 16.dp, vertical = 12.dp)) {
            val stacked = HomePresentation.stacked(maxWidth.value, LocalDensity.current.fontScale, 280f)
            val label: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(OrbitSymbols.Bolt, null, Modifier.size(20.dp))
                        SparklineText(stringResource(R.string.editorial_dish_power), textSpace, "title",
                            style = MaterialTheme.typography.titleSmall, color = colors.onContainer)
                    }
                    if (!compact || validPower == null || !available) SparklineText(caption, textSpace, "caption", style = MaterialTheme.typography.bodySmall, color = colors.onContainer)
                }
            }
            if (compact || stacked) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                label(); HomeMetricValue(value, "W", colors.onContainer, compact = true, sparklineSpace = textSpace)
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { label() }
                HomeMetricValue(value, "W", colors.onContainer, compact = true, sparklineSpace = textSpace)
            }
        }
    }
    }
    val cardModifier = modifier.fillMaxWidth().orbitSharedBounds("metric:POWER")
        .expressiveReveal("power-card", 220).semantics(mergeDescendants = true) {}
    if (onClick != null) Surface(onClick = hapticClick(action = onClick), modifier = cardModifier,
        shape = DashDesign.section, color = colors.container, contentColor = colors.onContainer, content = body)
    else Surface(cardModifier, shape = DashDesign.section, color = colors.container, contentColor = colors.onContainer, content = body)
}

@Composable
fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String? = null,
    icon: ImageVector,
    accentColor: Color,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    prominent: Boolean = false,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    emphasis: Float = 0f,
    intensityTint: Color = accentColor,
    sparkline: MetricHistorySeries? = null
) {
    val (number, unit) = splitMetric(value)
    val sparklineSpace = remember { HomeSparklineSpace() }
    val textSpace = sparklineSpace.takeIf { sparkline != null }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val revealDelay = when (icon) {
        OrbitSymbols.Download -> 80
        OrbitSymbols.Upload -> 115
        OrbitSymbols.Speed -> 150
        OrbitSymbols.SignalCellularAlt -> 185
        else -> 220
    }
    val metricKey = when (icon) {
        OrbitSymbols.Download -> "DOWNLOAD"
        OrbitSymbols.Upload -> "UPLOAD"
        OrbitSymbols.Speed -> "LATENCY"
        OrbitSymbols.SignalCellularAlt -> "SIGNAL"
        else -> "POWER"
    }
    val cardModifier = modifier.orbitSharedBounds("metric:$metricKey")
        .expressiveReveal(metricKey, revealDelay).pressMotion(interaction, 0.97f).semantics(mergeDescendants = true) {}
    val shape = if (prominent) DashDesign.download else if (icon == OrbitSymbols.Upload) DashDesign.upload else DashDesign.section
    val body: @Composable () -> Unit = {
        MetricIntensityBody(emphasis, amoledCardColor(containerColor), intensityTint,
            listOf(amoledCardContent(accentColor), supportingColor, MaterialTheme.colorScheme.onSurface)) {
        HomeSparkline(sparkline, sparklineSpace, accentColor, Modifier.matchParentSize())
        Column(Modifier.padding(if (compact) 12.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = DashDesign.pill, color = accentColor.copy(alpha = 0.10f)) {
                    Icon(icon, null, Modifier.padding(6.dp).size(20.dp), tint = accentColor)
                }
                SparklineText(title, textSpace, "title", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = amoledCardContent(accentColor))
            }
            if (unit.isNotBlank()) HomeMetricValue(number, unit, amoledCardContent(accentColor), compact, sparklineSpace = textSpace)
            else SparklineText(number, textSpace, "number", style = MaterialTheme.typography.headlineSmall, color = amoledCardContent(accentColor))
            if (subtitle != null) SparklineText(subtitle, textSpace, "caption", style = MaterialTheme.typography.bodySmall,
                color = supportingColor)
        }
    }
    }
    if (onClick != null) Surface(onClick = hapticClick(action = onClick), interactionSource = interaction,
        modifier = cardModifier, shape = shape, color = amoledCardColor(containerColor), content = body)
    else Surface(cardModifier, shape = shape, color = amoledCardColor(containerColor), content = body)
}

@Composable
fun ObstructionCard(snapshot: DishySnapshot) {
    // All entry points use the same colors matched to the actual surface.
    AntennaFieldOfViewCard(snapshot)
}

@Composable
fun AlertsCard(alerts: List<String>) {
    Surface(Modifier.fillMaxWidth().expressiveReveal("alerts-card"), color = amoledCardColor(MaterialTheme.colorScheme.errorContainer),
        contentColor = amoledCardContent(MaterialTheme.colorScheme.onErrorContainer), shape = DashDesign.section) {
        Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(OrbitSymbols.Warning, contentDescription = null,
                    tint = if (LocalExperience.current.theme == OrbitTheme.AMOLED) MaterialTheme.colorScheme.error else LocalContentColor.current)
                Text(androidx.compose.ui.res.stringResource(R.string.editorial_dish_warnings), style = MaterialTheme.typography.titleMedium)
            }
            alerts.forEach { Text("• ${telemetryLabel(it)}", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
fun HardwareInfoCard(snapshot: DishySnapshot) {
    Surface(Modifier.fillMaxWidth().expressiveReveal("hardware-card", 270),
        shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionHeading(stringResource(R.string.rich_equipment))
            InfoRow(stringResource(R.string.rich_uptime), uptimeLabel(snapshot.deviceInfo.uptimeSeconds),
                missing = snapshot.deviceInfo.uptimeSeconds <= 0L)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            InfoRow(stringResource(R.string.ui_software), snapshot.deviceInfo.softwareVersion.ifBlank { "—" },
                missing = snapshot.deviceInfo.softwareVersion.isBlank())
            InfoRow(stringResource(R.string.ui_hardware), snapshot.deviceInfo.hardwareVersion.ifBlank { "—" },
                missing = snapshot.deviceInfo.hardwareVersion.isBlank())
            InfoRow(stringResource(R.string.domain_router_ethernet),
                snapshot.deviceInfo.ethSpeedMbps?.let { "$it Mbps" } ?: stringResource(R.string.beacon_unknown),
                missing = snapshot.deviceInfo.ethSpeedMbps == null)
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    missing: Boolean = infoRowValueMissing(value)
) {
    val unknownValue = androidx.compose.ui.res.stringResource(R.string.beacon_unknown)
    val spokenModifier = if (missing) Modifier.clearAndSetSemantics { contentDescription = "$label: $unknownValue" } else Modifier
    BoxWithConstraints(spokenModifier.fillMaxWidth().padding(vertical = 5.dp)) {
        if (maxWidth / LocalDensity.current.fontScale < 300.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
                Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(label, Modifier.weight(0.36f), style = MaterialTheme.typography.bodySmall,
                    color = labelColor)
                Text(value, Modifier.weight(0.64f), style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End, color = valueColor)
            }
        }
    }
}

