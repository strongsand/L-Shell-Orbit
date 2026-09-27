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

import android.os.Build
import android.os.Bundle
import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import io.github.strongsand.lshell.core.model.DishState
import io.github.strongsand.lshell.core.model.DishySnapshot
import io.github.strongsand.lshell.beacon.BeaconState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val repository = DishyRepository()
    private val requestedTab = mutableStateOf<String?>(null)
    private val requestedReportEnd = mutableStateOf<Long?>(null)
    private val launchRequestId = mutableIntStateOf(0)

    private fun applyLaunchIntent(source: Intent?) {
        requestedTab.value = source?.getStringExtra("open_tab")
        requestedReportEnd.value = source?.getLongExtra("report_end", -1L)?.takeIf { it > 0L }
        if (requestedTab.value != null) launchRequestId.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLaunchIntent(intent)
        cancelWidgetRefresh(this)
        DailyReportScheduler.catchUpOrSchedule(this)
        enableEdgeToEdge()
        setContent {
            LShellTheme {
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> {
                                repository.startMonitoring(pollIntervalMs = 2000L)
                                lifecycleScope.launch { refreshLShellWidgets(applicationContext) }
                            }
                            Lifecycle.Event.ON_STOP -> repository.stopMonitoring()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        repository.stopMonitoring()
                    }
                }

                // Acesso direto ao valor do State sem delegação 'by' para compatibilidade total com Kotlin 2.x
                val snapshot = repository.telemetryFlow.collectAsState(initial = DishySnapshot()).value
                val samples = repository.samples.collectAsState().value
                val events = repository.events.collectAsState().value
                val terminalHistory = repository.history.collectAsState().value
                LaunchedEffect(terminalHistory?.current, snapshot.isOnline) {
                    val data = terminalHistory ?: return@LaunchedEffect
                    val onlineSnapshot = snapshot.takeIf { it.isOnline } ?: return@LaunchedEffect
                    try {
                        withContext(Dispatchers.IO) {
                            HistoryStore(applicationContext).use { store ->
                                val oldBoot = MonitorPreferences.historyBoot(applicationContext)
                                val candidateBoot = if (onlineSnapshot.deviceInfo.uptimeSeconds > 0L)
                                    System.currentTimeMillis() / 1_000L - onlineSnapshot.deviceInfo.uptimeSeconds
                                else oldBoot.coerceAtLeast(0L)
                                val oldCounter = MonitorPreferences.historyCounter(applicationContext)
                                val sameBoot = oldCounter < data.current && abs(candidateBoot - oldBoot) <= 60L
                                val boot = if (sameBoot) oldBoot else candidateBoot
                                store.importTerminalHistory(data, if (sameBoot) oldCounter else -1L, boot)
                                if (data.current > 0L && minOf(data.downloadMbps.size, data.uploadMbps.size,
                                        data.latencyMs.size, data.dropPercent.size) > 0) {
                                    MonitorPreferences.setHistoryCursor(applicationContext, data.current - 1L, boot)
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The live dashboard must remain usable if local history persistence fails.
                    }
                }
                LShellApp(snapshot = snapshot, samples = samples, events = events,
                    initialTab = requestedTab.value,
                    initialReportEnd = requestedReportEnd.value,
                    initialRequestId = launchRequestId.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchIntent(intent)
    }

    override fun onDestroy() {
        repository.close()
        super.onDestroy()
    }
}

@Composable
fun LShellTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    SideEffect {
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .isAppearanceLightStatusBars = !darkTheme
        }
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DashDesign.typography,
        shapes = Shapes(medium = DashDesign.section, large = DashDesign.section, extraLarge = DashDesign.hero),
        content = content
    )
}


@Composable
fun DashboardScreen(snapshot: DishySnapshot, samples: List<TelemetrySample> = emptyList(),
                    beaconState: BeaconState = BeaconState.NotConfigured,
                    onOpenBeacon: () -> Unit = {},
                    onOpenMetric: (MetricDetailKind) -> Unit = {}) {
    val latestPowerSample = samples.lastOrNull { it.powerWatts?.let { watts -> watts.isFinite() && watts > 0f } == true }
    val latestPower = latestPowerSample?.takeIf {
        snapshot.isOnline && System.currentTimeMillis() - it.timestamp <= 15_000L
    }?.powerWatts
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val wide = maxWidth >= (760 * fontScale.coerceAtMost(1.4f)).dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 1120.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                DishStatusHeroCard(snapshot, latestPower)
                BeaconHomeCard(beaconState, onOpenBeacon)
                AnimatedVisibility(snapshot.alerts.hasCriticalAlerts || snapshot.alerts.getActiveAlertsList().isNotEmpty(),
                    enter = fadeIn(tween(180)) + expandVertically(
                        spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(180))) {
                    AlertsCard(snapshot.alerts.getActiveAlertsList())
                }
                SectionHeading("Sua conexão, agora", if (snapshot.isFromCache) "Últimas métricas salvas" else "Tráfego e qualidade do enlace",
                    Modifier.expressiveReveal("dashboard-heading", 55))
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(DashDesign.gap)) {
                        DashboardMetrics(snapshot, Modifier.weight(1.15f), onOpenMetric, latestPower)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ObstructionCard(snapshot)
                            HardwareInfoCard(snapshot)
                        }
                    }
                } else {
                    DashboardMetrics(snapshot, onOpenMetric = onOpenMetric, powerWatts = latestPower)
                    ObstructionCard(snapshot)
                    HardwareInfoCard(snapshot)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DashboardMetrics(snapshot: DishySnapshot, modifier: Modifier = Modifier,
                             onOpenMetric: (MetricDetailKind) -> Unit = {}, powerWatts: Float? = null) {
    val available = snapshot.isOnline || snapshot.isFromCache
    val signal = if (available) snapshot.latency.formattedSignal else "Sem leitura"
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = maxWidth / LocalDensity.current.fontScale < 290.dp
        Column(verticalArrangement = Arrangement.spacedBy(DashDesign.gap)) {
            @Composable fun Download(modifier: Modifier) = MetricCard(modifier, "Download",
                if (available) snapshot.throughput.formattedDownlink else "— Mbps",
                icon = Icons.Rounded.Download, accentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                containerColor = MaterialTheme.colorScheme.primaryContainer, prominent = true,
                onClick = { onOpenMetric(MetricDetailKind.DOWNLOAD) })
            @Composable fun Upload(modifier: Modifier) = MetricCard(modifier, "Upload",
                if (available) snapshot.throughput.formattedUplink else "— Mbps",
                icon = Icons.Rounded.Upload, accentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                onClick = { onOpenMetric(MetricDetailKind.UPLOAD) })
            @Composable fun Latency(modifier: Modifier) = MetricCard(modifier, "Latência POP",
                if (available) snapshot.latency.formattedLatency else "— ms",
                if (available) "Perda ${snapshot.latency.formattedDropRate}" else "Aguardando a antena",
                Icons.Rounded.Speed, MaterialTheme.colorScheme.tertiary,
                onClick = { onOpenMetric(MetricDetailKind.LATENCY) })
            @Composable fun Signal(modifier: Modifier) = MetricCard(modifier, "Sinal", signal,
                if (available) snapshot.latency.signalDescription else "Conecte-se à Starlink",
                Icons.Rounded.SignalCellularAlt, if (snapshot.latency.isSnrAboveNoiseFloor == false)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                onClick = { onOpenMetric(MetricDetailKind.SIGNAL) })
            if (stacked) {
                Download(Modifier.fillMaxWidth())
                Upload(Modifier.fillMaxWidth())
                Latency(Modifier.fillMaxWidth())
                Signal(Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(DashDesign.gap)) {
                    Download(Modifier.weight(1.12f))
                    Upload(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(DashDesign.gap)) {
                    Latency(Modifier.weight(1f))
                    Signal(Modifier.weight(1f))
                }
            }
            EnergyMetricCard(powerWatts, available)
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
        !connected || alerts.isNotEmpty() -> HealthTone.CAUTION
        else -> HealthTone.GOOD
    }
    val status = if (snapshot.isFromCache) "Sem conexão" else if (!snapshot.isOnline && snapshot.state == DishState.UNKNOWN)
        "Aguardando antena" else snapshot.state.displayName
    val panel by animateColorAsState(
        if (tone == HealthTone.DANGER) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        tween(DashDesign.motionMillis), label = "terminal panel")
    val onPanel = if (tone == HealthTone.DANGER) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(modifier.fillMaxWidth().expressiveReveal("dish-status", 20).animateContentSize(
        spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow)),
        shape = DashDesign.hero, color = panel, contentColor = onPanel) {
        Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_dish_antenna), contentDescription = null,
                        modifier = Modifier.size(42.dp), tint = onPanel)
                }
                Column(Modifier.weight(1f)) {
                    Text("Terminal Starlink", style = MaterialTheme.typography.labelLarge)
                    Text(snapshot.deviceInfo.hardwareVersion.ifBlank { "Visão geral do terminal" },
                        style = MaterialTheme.typography.bodySmall, color = onPanel.copy(alpha = 0.8f))
                }
            }
            Text(status, style = if (status.length <= 12) MaterialTheme.typography.displaySmall
                else MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { stateDescription = status })
            HealthChip(when {
                !available -> if (snapshot.isFromCache) "Dados da última conexão" else "Sem leitura atual"
                alerts.isNotEmpty() -> "${alerts.size} aviso${if (alerts.size == 1) "" else "s"} no terminal"
                connected -> "Sistemas operando"
                else -> "Verifique o estado do terminal"
            }, tone)
            HorizontalDivider(color = onPanel.copy(alpha = 0.12f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroIndicator("Temperatura", available,
                    !snapshot.alerts.thermalThrottle && !snapshot.alerts.thermalShutdown, Icons.Rounded.Thermostat)
                HeroIndicator("Energia", available && powerWatts != null,
                    !snapshot.alerts.powerSupplyThermalThrottle, Icons.Rounded.Bolt,
                    powerWatts?.let { String.format(java.util.Locale.getDefault(), "%.1f W", it) })
                val ethernet = snapshot.deviceInfo.ethSpeedMbps
                HeroIndicator("Ethernet", available && ethernet != null, (ethernet ?: 0) >= 1000,
                    Icons.Rounded.SettingsEthernet, if ((ethernet ?: 0) >= 1000) "Gigabit" else ethernet?.let { "$it Mbps" })
            }
            if (snapshot.deviceInfo.softwareVersion.isNotBlank()) {
                Text("Software · ${snapshot.deviceInfo.softwareVersion}", style = MaterialTheme.typography.labelSmall,
                    color = onPanel.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun HeroIndicator(label: String, known: Boolean, okay: Boolean, icon: ImageVector, detail: String? = null) {
    val tone = when { !known -> HealthTone.UNKNOWN; okay -> HealthTone.GOOD; else -> HealthTone.CAUTION }
    val colors = healthColors(tone)
    Surface(color = colors.container, contentColor = colors.foreground, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(if (!known) "Sem leitura" else detail ?: if (okay) "Normal" else "Atenção",
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun EnergyMetricCard(powerWatts: Float?, available: Boolean, modifier: Modifier = Modifier) {
    val validPower = powerWatts?.takeIf { it.isFinite() && it > 0f }
    val colors = energyMetricColors()
    MetricCard(
        modifier = modifier.fillMaxWidth(),
        title = "Potência da Dishy",
        value = validPower?.let { String.format(java.util.Locale.getDefault(), "%.1f W", it) } ?: "— W",
        subtitle = if (!available) "Aguardando conexão com a antena"
            else if (validPower == null) "Métrica não disponível neste firmware"
            else "Amostra mais recente de powerIn",
        icon = Icons.Rounded.Bolt,
        accentColor = colors.onContainer,
        containerColor = colors.container,
        supportingColor = colors.onContainer
    )
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
    onClick: (() -> Unit)? = null
) {
    val (number, unit) = splitMetric(value)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val revealDelay = when (title) {
        "Download" -> 80
        "Upload" -> 115
        "Latência POP" -> 150
        "Sinal" -> 185
        "Potência da Dishy" -> 220
        else -> 60
    }
    val cardModifier = modifier.expressiveReveal(title, revealDelay).pressMotion(interaction, 0.97f)
    val shape = if (prominent) DashDesign.download else if (title == "Upload") DashDesign.upload else DashDesign.section
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpressiveIcon(icon, accentColor.copy(alpha = 0.10f), accentColor, Modifier)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = accentColor)
            }
            if (unit.isNotBlank()) MetricValue(number, unit, accentColor)
            else Text(number, style = MaterialTheme.typography.headlineSmall, color = accentColor)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = supportingColor)
        }
    }
    if (onClick != null) Surface(onClick = hapticClick(action = onClick), interactionSource = interaction,
        modifier = cardModifier, shape = shape, color = containerColor, content = body)
    else Surface(cardModifier, shape = shape, color = containerColor, content = body)
}

@Composable
fun ObstructionCard(snapshot: DishySnapshot) {
    val known = (snapshot.isOnline || snapshot.isFromCache) && snapshot.obstruction.fractionObstructed.isFinite()
    val percent = snapshot.obstruction.obstructionPercentage.takeIf { it.isFinite() } ?: 0f
    // Presentation bands only: terminal alert rules and monitoring remain unchanged.
    val tone = when {
        !known -> HealthTone.UNKNOWN
        snapshot.obstruction.currentlyObstructed || percent >= 10 -> HealthTone.DANGER
        percent >= 1 -> HealthTone.CAUTION
        else -> HealthTone.GOOD
    }
    val state = when {
        !known -> "Aguardando leitura"
        snapshot.obstruction.currentlyObstructed -> "Obstrução ativa"
        percent >= 10 -> "Crítico"
        percent >= 5 -> "Visada comprometida"
        percent >= 1 -> "Atenção à visada"
        else -> "Visada excelente"
    }
    val colors = healthColors(tone)
    val progress = animateFloatAsState((percent / 100).coerceIn(0f, 1f),
        tween(450), label = "obstruction progress")
    Surface(Modifier.fillMaxWidth().expressiveReveal("obstruction-card", 235),
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = DashDesign.section) {
        Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("Campo de visão", "Obstrução da antena")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (known) snapshot.obstruction.formattedPercentage else "—",
                        style = MaterialTheme.typography.displaySmall, color = colors.foreground)
                    Text(state, style = MaterialTheme.typography.labelLarge, color = colors.foreground)
                }
                Box(Modifier.size(86.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { if (known) (1f - percent / 100f).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxSize(), color = colors.foreground,
                        trackColor = colors.container, strokeWidth = 12.dp)
                    Icon(if (tone == HealthTone.GOOD) Icons.Rounded.CheckCircle else if (tone == HealthTone.UNKNOWN)
                        Icons.Rounded.Visibility else Icons.Rounded.Warning, null,
                        Modifier.size(30.dp), tint = colors.foreground)
                }
            }
            LinearProgressIndicator(
                progress = { if (known) progress.value else 0f },
                modifier = Modifier.fillMaxWidth().height(12.dp).clip(DashDesign.pill)
                    .semantics { stateDescription = if (known) "$state, ${snapshot.obstruction.formattedPercentage} obstruído" else state },
                color = colors.foreground, trackColor = colors.container
            )
            Text(when {
                !known -> "Conecte-se à antena para avaliar a visada."
                snapshot.isFromCache -> "Última leitura salva. A situação atual pode ter mudado."
                snapshot.obstruction.currentlyObstructed -> "Há um bloqueio na direção dos satélites."
                percent >= 5 -> "Procure uma posição com mais céu livre."
                percent >= 1 -> "Pequenos bloqueios podem interromper a conexão."
                else -> "Quase todo o céu livre. Ótimas condições de visada."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AlertsCard(alerts: List<String>) {
    Surface(Modifier.fillMaxWidth().expressiveReveal("alerts-card"), color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = DashDesign.section) {
        Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Warning, contentDescription = null)
                Text("Avisos do terminal", style = MaterialTheme.typography.titleMedium)
            }
            alerts.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
fun HardwareInfoCard(snapshot: DishySnapshot) {
    Surface(Modifier.fillMaxWidth().expressiveReveal("hardware-card", 270),
        shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeading("Seu equipamento")
            Spacer(Modifier.height(8.dp))
            InfoRow("Ligado há", snapshot.deviceInfo.formattedUptime)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            InfoRow("Software", snapshot.deviceInfo.softwareVersion.ifBlank { "—" })
            InfoRow("Hardware", snapshot.deviceInfo.hardwareVersion.ifBlank { "—" })
            InfoRow("Ethernet", snapshot.deviceInfo.formattedEthernetSpeed)
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
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

