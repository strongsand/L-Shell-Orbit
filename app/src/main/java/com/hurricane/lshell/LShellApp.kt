package com.hurricane.lshell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.rounded.ChevronRight
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hurricane.lshell.ar.SkyARScreen
import com.hurricane.lshell.ar.arColorScheme
import com.hurricane.lshell.core.data.ConnectionEvent
import com.hurricane.lshell.core.data.TelemetrySample
import com.hurricane.lshell.core.model.DishySnapshot
import com.hurricane.lshell.core.network.grpc.DishyGrpcClient
import com.hurricane.lshell.beacon.BeaconRepository
import com.hurricane.lshell.beacon.BeaconState
import com.hurricane.lshell.beacon.runtimeStatusOrNull
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("Início", Icons.Rounded.Home),
    DISH("Antena", Icons.Rounded.SettingsInputAntenna),
    NETWORK("Rede", Icons.Rounded.Router),
    SPEED("Teste Único", Icons.Rounded.Speed),
    DIAGNOSTICS("Diagnóstico", Icons.Rounded.Troubleshoot),
    HEALTH("Saúde", Icons.Rounded.Troubleshoot),
    ENERGY("Energia", Icons.Rounded.Assessment),
    TIMELINE("Timeline", Icons.Rounded.Schedule),
    OUTAGES("Quedas", Icons.Rounded.Troubleshoot),
    EXTREME("Teste Extremo", Icons.Rounded.Speed),
    EXTREME_HISTORY("Testes Extremos", Icons.Rounded.History),
    HISTORY("Histórico", Icons.Rounded.History),
    REPORTS("Relatórios", Icons.Rounded.Assessment),
    TERMINAL("Terminal", Icons.Rounded.Tune),
    ALIGNMENT("Alinhamento", Icons.Rounded.Tune),
    SKY("AR", Icons.Rounded.CameraAlt),
    SETTINGS("Funções", Icons.Rounded.Widgets),
    DOWNLOAD("Download", Icons.Rounded.Download),
    UPLOAD("Upload", Icons.Rounded.Upload),
    LATENCY("Latência", Icons.Rounded.Speed),
    SIGNAL("Sinal", Icons.Rounded.SignalCellularAlt),
    BEACON("Beacon", Icons.Rounded.Memory)
}

@Composable
fun LShellApp(snapshot: DishySnapshot, samples: List<TelemetrySample>, events: List<ConnectionEvent>,
                      initialTab: String? = null, initialReportEnd: Long? = null,
                      initialRequestId: Int = 0) {
    val appContext = LocalContext.current.applicationContext
    val beaconRepository = remember(appContext) { BeaconRepository.create(appContext) }
    val beaconState = beaconRepository.state.collectAsState().value
    val beaconWidgetRevision = beaconState.runtimeStatusOrNull()?.let {
        listOf(beaconState::class.simpleName, it.recording, it.pendingRecords, it.lastSyncAtMillis, it.dishyAvailable)
    } ?: listOf(beaconState::class.simpleName)
    LaunchedEffect(beaconWidgetRevision) { refreshBeaconWidget(appContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val beaconPermissions = remember {
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun hasBeaconPermissions(): Boolean = beaconPermissions.all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }
    var permissionSheetVisible by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var nearbyDismissedForSession by rememberSaveable { mutableStateOf(false) }
    var startupComplete by remember { mutableStateOf(false) }
    val latestBeaconState by rememberUpdatedState(beaconState)
    val latestStartupComplete by rememberUpdatedState(startupComplete)
    val latestNearbyDismissed by rememberUpdatedState(nearbyDismissedForSession)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (beaconPermissions.all { result[it] == true ||
                ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }) {
            Log.i(BEACON_APP_TAG, "BEACON_PERMISSIONS_OK")
            permissionDenied = false
            permissionSheetVisible = false
            beaconRepository.startDiscovery()
        } else {
            Log.i(BEACON_APP_TAG, "BEACON_PERMISSIONS_DENIED")
            permissionDenied = true
            permissionSheetVisible = true
            beaconRepository.reportBluetoothPermissionDenied()
        }
    }
    LaunchedEffect(beaconRepository) {
        if (beaconRepository.requiresBluetoothSetup()) {
            if (hasBeaconPermissions()) beaconRepository.startDiscovery()
            else permissionSheetVisible = true
        } else {
            beaconRepository.triggerAutoSync("app_open")
        }
        startupComplete = true
    }
    DisposableEffect(lifecycleOwner, beaconRepository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && latestStartupComplete) {
                if (beaconRepository.requiresBluetoothSetup()) {
                    if (latestBeaconState is BeaconState.NotConfigured && !latestNearbyDismissed) {
                        if (hasBeaconPermissions()) beaconRepository.startDiscovery()
                        else permissionSheetVisible = true
                    }
                } else {
                    permissionSheetVisible = false
                    beaconRepository.triggerAutoSync("foreground")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(beaconRepository) { onDispose { beaconRepository.close() } }
    // The existing destinations now use the Navigation Compose back stack.
    // NavHost owns predictive-back progress, cancellation and root back-to-home.
    val navigation = rememberNavController()
    fun navigate(tab: MainTab) {
        if (navigation.currentDestination?.route == tab.name) return
        navigation.navigate(tab.name) {
            if (tab in listOf(MainTab.HOME, MainTab.DISH, MainTab.NETWORK, MainTab.SETTINGS)) {
                popUpTo(MainTab.HOME.name) { inclusive = false }
            }
            launchSingleTop = true
        }
    }
    LaunchedEffect(initialTab, initialReportEnd, initialRequestId) {
        MainTab.entries.firstOrNull { it.name == initialTab && it != MainTab.HOME }?.let { navigate(it) }
    }
    val nearbyDevice = (beaconState as? BeaconState.BleFound)?.device
    val nearbySheetVisible = nearbyDevice != null && !nearbyDismissedForSession
    val backgroundScale by animateFloatAsState(
        if (permissionSheetVisible || nearbySheetVisible) 0.985f else 1f,
        spring(dampingRatio = 0.88f, stiffness = 320f),
        label = "Beacon modal background"
    )
    NavHost(navigation, startDestination = MainTab.HOME.name,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = backgroundScale
            scaleY = backgroundScale
        },
        enterTransition = {
            fadeIn(tween(DashDesign.motionMillis)) +
                scaleIn(tween(DashDesign.motionMillis), initialScale = 0.985f)
        },
        exitTransition = { fadeOut(tween(130)) + scaleOut(tween(160), targetScale = 0.992f) },
        popEnterTransition = {
            fadeIn(tween(DashDesign.motionMillis)) +
                scaleIn(tween(DashDesign.motionMillis), initialScale = 0.992f)
        },
        popExitTransition = { fadeOut(tween(130)) + scaleOut(tween(160), targetScale = 0.985f) }) {
        MainTab.entries.forEach { tab ->
            composable(tab.name) {
                var hudVisible by rememberSaveable { mutableStateOf(true) }
                LaunchedEffect(tab) {
                    when (tab) {
                        MainTab.HOME -> if (beaconRepository.requiresBluetoothSetup()) {
                            if (beaconState is BeaconState.NotConfigured && !nearbyDismissedForSession &&
                                hasBeaconPermissions()) beaconRepository.startDiscovery()
                        } else beaconRepository.triggerAutoSync("home")
                        MainTab.BEACON -> if (beaconRepository.requiresBluetoothSetup()) {
                            if (beaconState is BeaconState.NotConfigured && !nearbyDismissedForSession &&
                                hasBeaconPermissions()) beaconRepository.startDiscovery()
                        } else beaconRepository.triggerAutoSync("beacon_screen")
                        else -> Unit
                    }
                }
                Scaffold(
                    topBar = {
                        if (tab != MainTab.ALIGNMENT && tab != MainTab.SKY) {
                            Row(Modifier.fillMaxWidth().statusBarsPadding()
                                .expressiveReveal("app-header-${tab.name}", 20, 6.dp)
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_dish_antenna), null,
                                        modifier = Modifier.size(31.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                Text("L-Shell Orbit", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    },
                    bottomBar = {
                        AnimatedVisibility(tab != MainTab.ALIGNMENT && (tab != MainTab.SKY || hudVisible),
                            enter = fadeIn(tween(DashDesign.motionMillis)) + slideInVertically(
                                animationSpec = spring(dampingRatio = 0.82f, stiffness = 300f),
                                initialOffsetY = { it / 3 }),
                            exit = fadeOut(tween(140)) + slideOutVertically(
                                animationSpec = tween(180), targetOffsetY = { it / 4 })) {
                            DashNavigation(tab, ::navigate)
                        }
                    }
                ) { padding ->
                    when (tab) {
                        MainTab.HOME -> Box(Modifier.padding(padding)) { DashboardScreen(snapshot, samples, beaconState,
                            onOpenBeacon = { navigate(MainTab.BEACON) }) { metric ->
                            navigate(when (metric) {
                                MetricDetailKind.DOWNLOAD -> MainTab.DOWNLOAD
                                MetricDetailKind.UPLOAD -> MainTab.UPLOAD
                                MetricDetailKind.LATENCY -> MainTab.LATENCY
                                MetricDetailKind.SIGNAL -> MainTab.SIGNAL
                            })
                        } }
                        MainTab.DISH -> DishDiagnosticsScreen(
                            snapshot,
                            samples,
                            events,
                            beaconState.runtimeStatusOrNull()?.lastSyncAtMillis,
                            Modifier.padding(padding)
                        )
                        MainTab.NETWORK -> RouterScreen(Modifier.padding(padding))
                        MainTab.SPEED -> SpeedTestScreen(Modifier.padding(padding))
                        MainTab.DIAGNOSTICS -> DiagnosticsHomeScreen(Modifier.padding(padding)) { destination ->
                            navigate(when (destination) {
                                DiagnosticDestination.HEALTH -> MainTab.HEALTH
                                DiagnosticDestination.ENERGY -> MainTab.ENERGY
                                DiagnosticDestination.TIMELINE -> MainTab.TIMELINE
                                DiagnosticDestination.OUTAGES -> MainTab.OUTAGES
                                DiagnosticDestination.SINGLE_TEST -> MainTab.SPEED
                                DiagnosticDestination.EXTREME_TEST -> MainTab.EXTREME
                                DiagnosticDestination.EXTREME_HISTORY -> MainTab.EXTREME_HISTORY
                            })
                        }
                        MainTab.HEALTH -> InstallationHealthScreen(snapshot, Modifier.padding(padding)) { navigation.popBackStack() }
                        MainTab.ENERGY -> EnergyScreen(samples, Modifier.padding(padding)) { navigation.popBackStack() }
                        MainTab.TIMELINE -> SmartTimelineScreen(Modifier.padding(padding)) { navigation.popBackStack() }
                        MainTab.OUTAGES -> OutagesScreen(Modifier.padding(padding)) { navigation.popBackStack() }
                        MainTab.EXTREME -> ExtremeSpeedTestScreen(snapshot, Modifier.padding(padding)) { navigation.popBackStack() }
                        MainTab.EXTREME_HISTORY -> ExtremeTestHistoryScreen(Modifier.padding(padding)) { navigation.popBackStack() }
                        MainTab.HISTORY -> HistoryScreen(Modifier.padding(padding))
                        MainTab.REPORTS -> DailyReportsScreen(Modifier.padding(padding), initialReportEnd) {
                            navigation.popBackStack()
                        }
                        MainTab.TERMINAL -> Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text("Terminal", style = MaterialTheme.typography.headlineLarge)
                            Spacer(Modifier.height(16.dp))
                            DataCard("Alinhamento da antena") {
                                Text("Veja a rotação e a inclinação medidas pela Dishy e ajuste um eixo de cada vez.")
                                Spacer(Modifier.height(12.dp))
                                androidx.compose.material3.Button(onClick = hapticClick { navigate(MainTab.ALIGNMENT) }) { Text("Abrir alinhamento") }
                            }
                        }
                        MainTab.ALIGNMENT -> AlignmentScreen(Modifier.padding(padding)) { navigation.popBackStack() }
                        MainTab.SKY -> SkyARScreen(snapshot = snapshot, hudVisible = hudVisible, onToggleHud = { hudVisible = !hudVisible },
                            bottomInset = padding.calculateBottomPadding(), onExit = { navigation.popBackStack() })
                        MainTab.SETTINGS -> SettingsScreen(Modifier.padding(padding), onNavigate = ::navigate)
                        MainTab.BEACON -> BeaconScreen(beaconState, beaconRepository, Modifier.padding(padding),
                            onBack = { navigation.popBackStack() },
                            onOpenHistory = { navigate(MainTab.HISTORY) })
                        MainTab.DOWNLOAD, MainTab.UPLOAD, MainTab.LATENCY, MainTab.SIGNAL ->
                            MetricDetailScreen(when (tab) {
                                MainTab.DOWNLOAD -> MetricDetailKind.DOWNLOAD
                                MainTab.UPLOAD -> MetricDetailKind.UPLOAD
                                MainTab.LATENCY -> MetricDetailKind.LATENCY
                                else -> MetricDetailKind.SIGNAL
                            }, snapshot, Modifier.padding(padding)) { navigation.popBackStack() }
                    }
                }
            }
        }
    }
    if (permissionSheetVisible && beaconRepository.requiresBluetoothSetup() &&
        !nearbyDismissedForSession) {
        BeaconPermissionSheet(
            denied = permissionDenied,
            onContinue = {
                Log.i(BEACON_APP_TAG, "BEACON_PERMISSION_FLOW_START")
                permissionLauncher.launch(beaconPermissions)
            },
            onDismiss = {
                permissionSheetVisible = false
                nearbyDismissedForSession = true
                if (beaconState is BeaconState.Error) beaconRepository.clearError()
            }
        )
    }
    nearbyDevice?.takeIf { nearbySheetVisible }?.let { device ->
        LaunchedEffect(device.id) { Log.i(BEACON_APP_TAG, "BEACON_PROMPT_SHOW") }
        BeaconNearbySheet(
            device = device,
            onPair = {
                navigate(MainTab.BEACON)
                beaconRepository.beginBleProvisioning(device)
            },
            onDismiss = {
                Log.i(BEACON_APP_TAG, "BEACON_PROMPT_DISMISS")
                nearbyDismissedForSession = true
                beaconRepository.dismissFound()
            }
        )
    }
}

private const val BEACON_APP_TAG = "L-ShellBeacon"

@Composable
private fun DashNavigation(selected: MainTab, onSelect: (MainTab) -> Unit) {
    MaterialTheme(colorScheme = if (selected == MainTab.SKY) arColorScheme() else MaterialTheme.colorScheme) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center) {
            val showLabel = maxWidth >= 390.dp && LocalDensity.current.fontScale <= 1.2f
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val mainTabs = listOf(MainTab.HOME, MainTab.DISH, MainTab.NETWORK, MainTab.SETTINGS)
                val activeTab = when {
                    selected in listOf(MainTab.DOWNLOAD, MainTab.UPLOAD, MainTab.LATENCY, MainTab.SIGNAL) -> MainTab.HOME
                    selected in listOf(MainTab.HISTORY, MainTab.REPORTS, MainTab.SPEED, MainTab.TERMINAL,
                        MainTab.DIAGNOSTICS, MainTab.HEALTH, MainTab.ENERGY, MainTab.TIMELINE,
                        MainTab.OUTAGES, MainTab.EXTREME, MainTab.EXTREME_HISTORY, MainTab.BEACON) -> MainTab.SETTINGS
                    else -> selected
                }
                Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = DashDesign.pill) {
                    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(6.dp)) {
                        val cellWidth = maxWidth / mainTabs.size
                        val selectedIndex = mainTabs.indexOf(activeTab)
                        val indicatorX by animateDpAsState(cellWidth * selectedIndex.coerceAtLeast(0),
                            spring(dampingRatio = .66f, stiffness = 260f), label = "moving nav indicator")
                        Surface(Modifier.width(cellWidth).height(52.dp)
                            .offset(x = indicatorX).graphicsLayer { alpha = if (selectedIndex >= 0) 1f else 0f },
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = DashDesign.pill) {}
                        Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                        mainTabs.forEach { tab ->
                            val active = activeTab == tab
                            val interaction = remember { MutableInteractionSource() }
                            val iconScale by animateFloatAsState(if (active) 1.08f else 1f,
                                spring(dampingRatio = 0.72f, stiffness = 320f), label = "nav emphasis")
                            Column(Modifier.weight(1f).heightIn(min = 52.dp)
                                .pressMotion(interaction).clip(DashDesign.pill)
                                .selectable(active, interactionSource = interaction, indication = androidx.compose.material3.ripple(),
                                    role = Role.Tab, onClick = hapticClick { onSelect(tab) })
                                .semantics { contentDescription = tab.label }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                val iconModifier = Modifier.size(24.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                                val tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                Icon(tab.icon, null, iconModifier, tint)
                                if (active && showLabel) Text(tab.label,
                                    style = MaterialTheme.typography.labelMedium, color = tint)
                            }
                        }
                        }
                    }
                }
                val arInteraction = remember { MutableInteractionSource() }
                var arRotationTarget by remember { mutableStateOf(0f) }
                LaunchedEffect(selected == MainTab.SKY) {
                    if (selected == MainTab.SKY) arRotationTarget += 360f
                }
                val arRotation by animateFloatAsState(arRotationTarget,
                    spring(dampingRatio = 0.9f, stiffness = 220f), label = "AR activation")
                Surface(onClick = hapticClick(DashFeedback.SPECIAL) {
                    if (selected == MainTab.SKY) arRotationTarget += 360f
                    onSelect(MainTab.SKY)
                }, interactionSource = arInteraction, shape = DashDesign.cookie,
                    color = if (selected == MainTab.SKY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = if (selected == MainTab.SKY) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(60.dp).pressMotion(arInteraction, 0.9f)
                        .graphicsLayer { rotationZ = arRotation }
                        .semantics { contentDescription = "Abrir modo AR" }) {
                    Column(Modifier.graphicsLayer { rotationZ = -arRotation },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Rounded.CameraAlt, null, Modifier.size(24.dp))
                        Text("AR", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun FunctionDestination(title: String, supporting: String, icon: ImageVector, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(onClick = hapticClick(action = onClick), interactionSource = interaction, shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().expressiveReveal("function-$title", 45, 8.dp)
            .pressMotion(interaction, 0.98f)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ExpressiveIcon(icon, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DishDiagnosticsScreen(
    snapshot: DishySnapshot,
    samples: List<TelemetrySample>,
    events: List<ConnectionEvent>,
    beaconHistoryRevision: Long?,
    modifier: Modifier = Modifier
) {
    val latestPower = samples.lastOrNull {
        it.powerWatts?.let { watts -> watts.isFinite() && watts > 0f } == true &&
            System.currentTimeMillis() - it.timestamp <= 15_000L
    }?.powerWatts
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Antena", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))
        DishStatusHeroCard(snapshot, latestPower)
        Spacer(Modifier.height(16.dp))
        DishControlCard(snapshot)
        Spacer(Modifier.height(16.dp))
        if (snapshot.alerts.getActiveAlertsList().isNotEmpty()) {
            AlertsCard(snapshot.alerts.getActiveAlertsList())
            Spacer(Modifier.height(16.dp))
        }
        ObstructionCard(snapshot)
        Spacer(Modifier.height(16.dp))
        ObstructionMapCard(snapshot.deviceInfo.boresightAzimuthDeg)
        Spacer(Modifier.height(16.dp))
        HardwareInfoCard(snapshot)
        Spacer(Modifier.height(16.dp))
        DataCard("Sinal e orientação") {
            InfoRow("Qualidade do sinal", snapshot.latency.formattedSignal)
            InfoRow("Azimute", "${snapshot.deviceInfo.boresightAzimuthDeg}°")
            InfoRow("Elevação", "${snapshot.deviceInfo.boresightElevationDeg}°")
            InfoRow("Tempo válido", "${snapshot.obstruction.validSeconds.toInt()} s")
        }
        Spacer(Modifier.height(20.dp))
        TelemetryCharts(samples, beaconHistoryRevision)
        Spacer(Modifier.height(20.dp))
        DataCard("Eventos observados pelo app") {
            if (events.isEmpty()) Text("Nenhuma mudança registrada nesta sessão.")
            events.forEach { event ->
                InfoRow(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.timestamp)), event.description)
            }
        }
    }
}

@Composable
private fun DishControlCard(snapshot: DishySnapshot) {
    val client = remember { DishyGrpcClient() }
    DisposableEffect(client) { onDispose { client.close() } }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<DishyGrpcClient.Control?>(null) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val labels = mapOf(
        DishyGrpcClient.Control.REBOOT to "Reiniciar",
        DishyGrpcClient.Control.STOW to "Recolher",
        DishyGrpcClient.Control.UNSTOW to "Desrecolher",
        DishyGrpcClient.Control.INHIBIT_GPS to "Inibir GPS"
    )
    DataCard("Comandos da antena") {
        Text("Esses comandos alteram o funcionamento do terminal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DishyGrpcClient.Control.REBOOT, DishyGrpcClient.Control.STOW).forEach { action ->
                androidx.compose.material3.OutlinedButton(onClick = hapticClick { pending = action },
                    enabled = snapshot.isOnline && !busy, modifier = Modifier.weight(1f)) {
                    Text(labels.getValue(action))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DishyGrpcClient.Control.UNSTOW, DishyGrpcClient.Control.INHIBIT_GPS).forEach { action ->
                androidx.compose.material3.OutlinedButton(onClick = hapticClick { pending = action },
                    enabled = snapshot.isOnline && !busy, modifier = Modifier.weight(1f)) {
                    Text(labels.getValue(action))
                }
            }
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    pending?.let { action ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(labels.getValue(action) + " antena?") },
            text = { Text(when (action) {
                DishyGrpcClient.Control.REBOOT -> "A conexão cairá por alguns minutos."
                DishyGrpcClient.Control.STOW -> "A antena será recolhida e perderá a conexão até ser desrecolhida."
                DishyGrpcClient.Control.UNSTOW -> "A antena voltará à posição de operação."
                DishyGrpcClient.Control.INHIBIT_GPS -> "O terminal deixará de usar o GPS para fornecer a localização."
            }) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = hapticClick(DashFeedback.CONFIRM) {
                    pending = null
                    busy = true
                    scope.launch {
                        feedback = client.control(action).fold(
                            onSuccess = { "Comando enviado à antena." },
                            onFailure = { "Falha ao enviar: ${it.message ?: "sem resposta"}" }
                        )
                        busy = false
                    }
                }) { Text("Confirmar") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = hapticClick { pending = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier, onNavigate: (MainTab) -> Unit) {
    val context = LocalContext.current
    var monitoring by remember { mutableStateOf(MonitorPreferences.enabled(context)) }
    var silent by remember { mutableStateOf(MonitorPreferences.silent(context)) }
    var interval by remember { mutableStateOf(MonitorPreferences.intervalSeconds(context)) }
    var intervalMenu by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var lastSuccess by remember { mutableStateOf(MonitorPreferences.lastSuccess(context)) }
    var monitorError by remember { mutableStateOf(MonitorPreferences.lastError(context)) }
    var reportEnabled by remember { mutableStateOf(DailyReportPreferences.enabled(context)) }
    var reportHour by remember { mutableStateOf(DailyReportPreferences.hour(context)) }
    var reportMinute by remember { mutableStateOf(DailyReportPreferences.minute(context)) }
    var reportNotify by remember { mutableStateOf(DailyReportPreferences.notify(context)) }
    var keepReports by remember { mutableStateOf(DailyReportPreferences.keepHistory(context)) }
    var retention by remember { mutableStateOf(DailyReportPreferences.retention(context)) }
    var retentionMenu by remember { mutableStateOf(false) }
    var timeDialog by remember { mutableStateOf(false) }
    var creditsOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(monitoring) {
        while (monitoring) {
            lastSuccess = MonitorPreferences.lastSuccess(context)
            monitorError = MonitorPreferences.lastError(context)
            delay(5_000)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            permissionDenied = false
            monitoring = true
            MonitorPreferences.setLastAlerts(context, emptySet())
            MonitorPreferences.setEnabled(context, true)
            DishyMonitorService.start(context)
        } else permissionDenied = true
    }
    val reportPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reportNotify = granted
        DailyReportPreferences.setNotify(context, granted)
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Funções", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FunctionDestination("Diagnóstico", "Saúde, estabilidade, quedas e testes", Icons.Rounded.Troubleshoot) { onNavigate(MainTab.DIAGNOSTICS) }
            FunctionDestination("Relatórios", "Resumo diário da estabilidade da conexão", Icons.Rounded.Assessment) { onNavigate(MainTab.REPORTS) }
            FunctionDestination("Terminal e alinhamento", "Encontre a melhor direção", Icons.Rounded.Tune) { onNavigate(MainTab.TERMINAL) }
            FunctionDestination(stringResource(R.string.beacon_title), stringResource(R.string.beacon_subtitle),
                Icons.Rounded.Memory) { onNavigate(MainTab.BEACON) }
        }
        Spacer(Modifier.height(16.dp))
        DataCard("Alertas da antena") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Monitorar em segundo plano", modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = monitoring, onCheckedChange = hapticToggle { enabled ->
                    if (enabled) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else {
                            monitoring = true
                            MonitorPreferences.setLastAlerts(context, emptySet())
                            MonitorPreferences.setEnabled(context, true)
                            DishyMonitorService.start(context)
                        }
                    } else {
                        monitoring = false
                        MonitorPreferences.setEnabled(context, false)
                        DishyMonitorService.stop(context)
                    }
                })
            }
            Text("Consulta a antena em segundo plano e avisa sobre obstrução, falhas de conexão e alertas do equipamento.", style = MaterialTheme.typography.bodySmall)
            if (monitoring) Text(if (lastSuccess > 0) "Última leitura: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(lastSuccess)) else "Aguardando primeira leitura…", style = MaterialTheme.typography.bodySmall)
            if (monitoring && lastSuccess > 0 && System.currentTimeMillis() - lastSuccess > (interval + 10) * 2_000L)
                Text("Sem leituras recentes. Confira a conexão com a antena.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (monitoring && !NotificationManagerCompat.from(context).areNotificationsEnabled())
                Text("Notificações desativadas no Android; os alertas não aparecerão.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            monitorError?.let { Text("Última falha: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (monitoring) androidx.compose.material3.OutlinedButton(onClick = hapticClick { DishyMonitorService.testNotification(context) }) {
                Text("Testar notificação")
            }
            if (permissionDenied) Text("Ative a permissão de notificações para receber os alertas.", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alertas silenciosos", modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = silent, onCheckedChange = hapticToggle {
                    silent = it
                    MonitorPreferences.setSilent(context, it)
                })
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Intervalo de consulta", modifier = Modifier.weight(1f))
                androidx.compose.foundation.layout.Box {
                    androidx.compose.material3.OutlinedButton(onClick = hapticClick { intervalMenu = true }) {
                        Text(if (interval < 60) "${interval} s" else "${interval / 60} min")
                    }
                    DropdownMenu(expanded = intervalMenu, onDismissRequest = { intervalMenu = false }) {
                        listOf(1, 5, 10, 30, 60, 120, 300).forEach { seconds ->
                            DropdownMenuItem(
                                text = { Text(if (seconds < 60) "${seconds} s" else "${seconds / 60} min") },
                                onClick = hapticClick {
                                    interval = seconds
                                    intervalMenu = false
                                    MonitorPreferences.setIntervalSeconds(context, seconds)
                                    if (monitoring) DishyMonitorService.start(context)
                                }
                            )
                        }
                    }
                }
            }
            Text("Padrão: 1 min. 5 s aumenta o consumo de bateria. O Android ainda pode atrasar leituras em segundo plano.", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        DataCard("Relatórios diários") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Relatório diário", style = MaterialTheme.typography.titleMedium)
                    Text("Analisa somente dados coletados e permanece no aparelho.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(checked = reportEnabled,
                    onCheckedChange = hapticToggle { enabled ->
                        reportEnabled = enabled
                        DailyReportPreferences.setEnabled(context, enabled)
                        if (enabled) {
                            DailyReportScheduler.schedule(context)
                            if (reportNotify && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) reportPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else DailyReportScheduler.cancel(context)
                    })
            }
            if (reportEnabled && !monitoring) Text(
                "Ative o monitoramento em segundo plano para que o relatório tenha dados ao longo do dia.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (reportEnabled) Text(
                "O Android pode entregar o relatório alguns minutos depois do horário para economizar bateria.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Horário do relatório")
                    Text("O período termina aproximadamente neste horário.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.FilledTonalButton(onClick = hapticClick { timeDialog = true }) {
                    Text(String.format(java.util.Locale.getDefault(), "%02d:%02d", reportHour, reportMinute))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Notificar quando estiver pronto", Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = reportNotify,
                    onCheckedChange = hapticToggle { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) reportPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else {
                            reportNotify = enabled
                            DailyReportPreferences.setNotify(context, enabled)
                        }
                    })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Manter histórico de relatórios", Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = keepReports,
                    onCheckedChange = hapticToggle {
                        keepReports = it
                        DailyReportPreferences.setKeepHistory(context, it)
                    })
            }
            if (keepReports) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Retenção", Modifier.weight(1f))
                Box {
                    androidx.compose.material3.OutlinedButton(onClick = hapticClick { retentionMenu = true }) {
                        Text(retention.label)
                    }
                    DropdownMenu(expanded = retentionMenu, onDismissRequest = { retentionMenu = false }) {
                        ReportRetention.entries.forEach { choice ->
                            DropdownMenuItem(text = { Text(choice.label) }, onClick = hapticClick {
                                retention = choice
                                retentionMenu = false
                                DailyReportPreferences.setRetention(context, choice)
                            })
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        DataCard("Créditos") {
            Text("Bibliotecas, componentes e fontes de dados usados no L-Shell Orbit.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.OutlinedButton(onClick = hapticClick { creditsOpen = true }) {
                Text("Ver créditos")
            }
        }
    }
    if (creditsOpen) CreditsSheet(onDismiss = { creditsOpen = false })
    if (timeDialog) ReportTimeDialog(reportHour, reportMinute, onDismiss = { timeDialog = false }) { hour, minute ->
        reportHour = hour
        reportMinute = minute
        DailyReportPreferences.setTime(context, hour, minute)
        if (reportEnabled) DailyReportScheduler.schedule(context)
        timeDialog = false
    }
}

@Composable
private fun ReportTimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit,
                             onConfirm: (Int, Int) -> Unit) {
    var selectedHour by remember(hour) { mutableStateOf(hour.toFloat()) }
    var selectedMinuteStep by remember(minute) { mutableStateOf((minute / 5f).coerceIn(0f, 11f)) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Schedule, null) },
        title = { Text("Horário do relatório") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(String.format(java.util.Locale.getDefault(), "%02d:%02d",
                    selectedHour.toInt(), selectedMinuteStep.toInt() * 5),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                Text("Hora", style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.Slider(value = selectedHour,
                    onValueChange = { selectedHour = it }, valueRange = 0f..23f, steps = 22)
                Text("Minutos", style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.Slider(value = selectedMinuteStep,
                    onValueChange = { selectedMinuteStep = it }, valueRange = 0f..11f, steps = 10)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = hapticClick(DashFeedback.CONFIRM) {
                onConfirm(selectedHour.toInt(), selectedMinuteStep.toInt() * 5)
            }) { Text("Salvar") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = hapticClick(action = onDismiss)) { Text("Cancelar") }
        }
    )
}

@Composable
private fun DataCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = DashDesign.section,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(DashDesign.inset)) {
            SectionHeading(title)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
