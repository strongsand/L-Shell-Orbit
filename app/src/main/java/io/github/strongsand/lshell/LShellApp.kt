package io.github.strongsand.lshell


import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.animation.core.snap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.navigation.compose.NavHost
import androidx.navigation.createGraph
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
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
import io.github.strongsand.lshell.ar.SkyARScreen
import io.github.strongsand.lshell.ar.arColorScheme
import io.github.strongsand.lshell.core.data.ConnectionEvent
import io.github.strongsand.lshell.core.data.TelemetrySample
import io.github.strongsand.lshell.core.data.LivePowerReading
import io.github.strongsand.lshell.core.model.DishySnapshot
import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import io.github.strongsand.lshell.beacon.BeaconRepository
import io.github.strongsand.lshell.beacon.runtimeStatusOrNull
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class MainTab(val icon: ImageVector) {
    HOME(OrbitSymbols.Home),
    DISH(OrbitSymbols.SettingsInputAntenna),
    NETWORK(OrbitSymbols.Router),
    SPEED(OrbitSymbols.Speed),
    NORMAL_TEST(OrbitSymbols.Speed),
    TEST_RESULTS(OrbitSymbols.History),
    DIAGNOSTICS(OrbitSymbols.Troubleshoot),
    HEALTH(OrbitSymbols.Troubleshoot),
    ENERGY(OrbitSymbols.Assessment),
    TIMELINE(OrbitSymbols.Schedule),
    OUTAGES(OrbitSymbols.Troubleshoot),
    EXTREME(OrbitSymbols.Speed),
    EXTREME_HISTORY(OrbitSymbols.History),
    HISTORY(OrbitSymbols.History),
    REPORTS(OrbitSymbols.Assessment),
    TERMINAL(OrbitSymbols.Tune),
    ALIGNMENT(OrbitSymbols.Tune),
    SKY(OrbitSymbols.CameraAlt),
    SETTINGS(OrbitSymbols.Widgets),
    PREFERENCES(OrbitSymbols.Tune),
    DOWNLOAD(OrbitSymbols.Download),
    UPLOAD(OrbitSymbols.Upload),
    LATENCY(OrbitSymbols.Speed),
    SIGNAL(OrbitSymbols.SignalCellularAlt),
    POWER(OrbitSymbols.Bolt),
    SCHEDULED_TESTS(OrbitSymbols.Schedule),
    SMART_TESTS(OrbitSymbols.AutoAwesome),
    BEACON(OrbitSymbols.Memory),
    TECHNICAL(OrbitSymbols.Tune),
    TRAFFIC(OrbitSymbols.Assessment)
}

@Composable
private fun MainTab.localizedLabel(): String = androidx.compose.ui.res.stringResource(when (this) {
    MainTab.HOME -> R.string.function_home
    MainTab.DISH -> R.string.function_dish
    MainTab.NETWORK -> R.string.editorial_router
    MainTab.SPEED -> R.string.function_speed
    MainTab.NORMAL_TEST -> R.string.product_normal_test
    MainTab.TEST_RESULTS -> R.string.polish_test_history
    MainTab.DIAGNOSTICS -> R.string.function_diagnostics
    MainTab.HEALTH -> R.string.function_health
    MainTab.ENERGY -> R.string.function_energy
    MainTab.TIMELINE -> R.string.function_history
    MainTab.OUTAGES -> R.string.function_outages
    MainTab.EXTREME -> R.string.function_extreme
    MainTab.EXTREME_HISTORY -> R.string.editorial_extreme_history
    MainTab.HISTORY -> R.string.function_history
    MainTab.REPORTS -> R.string.function_reports
    MainTab.TERMINAL -> if (LocalExperience.current.technical) R.string.editorial_term else R.string.function_dish
    MainTab.ALIGNMENT -> R.string.function_alignment
    MainTab.SKY -> R.string.editorial_sky
    MainTab.SETTINGS -> R.string.settings_functions
    MainTab.PREFERENCES -> R.string.settings_title
    MainTab.DOWNLOAD -> R.string.traffic_download
    MainTab.UPLOAD -> R.string.traffic_upload
    MainTab.LATENCY -> R.string.traffic_latency
    MainTab.SIGNAL -> R.string.editorial_signal
    MainTab.POWER -> R.string.editorial_dish_power
    MainTab.SCHEDULED_TESTS -> R.string.auto_scheduled_title
    MainTab.SMART_TESTS -> R.string.auto_smart_title
    MainTab.BEACON -> R.string.beacon_title
    MainTab.TECHNICAL -> R.string.settings_technical
    MainTab.TRAFFIC -> R.string.traffic_title
})

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun LShellApp(snapshot: DishySnapshot, samples: List<TelemetrySample>, events: List<ConnectionEvent>,
                      initialTab: String? = null, initialTestId: Long? = null, initialReportEnd: Long? = null,
                      initialRequestId: Int = 0, initialSessionId: String? = null,
                      livePower: LivePowerReading? = null) {
    val appContext = LocalContext.current.applicationContext
    val beaconRepository = remember(appContext) { BeaconRepository.create(appContext) }
    val beaconState = beaconRepository.state.collectAsState().value
    val beaconWidgetRevision = beaconState.runtimeStatusOrNull()?.let {
        listOf(beaconState::class.simpleName, it.recording, it.pendingRecords, it.lastSyncAtMillis, it.dishyAvailable)
    } ?: listOf(beaconState::class.simpleName)
    LaunchedEffect(beaconWidgetRevision) { refreshBeaconWidget(appContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // BLE permissions and discovery belong exclusively to BeaconScreen's Configure action.
    LaunchedEffect(beaconRepository) {
        if (!beaconRepository.requiresBluetoothSetup()) beaconRepository.triggerAutoSync("app_open")
    }
    DisposableEffect(lifecycleOwner, beaconRepository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && !beaconRepository.requiresBluetoothSetup()) {
                beaconRepository.triggerAutoSync("foreground")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(beaconRepository) { onDispose { beaconRepository.close() } }
    // The existing destinations now use the Navigation Compose back stack.
    // NavHost owns predictive-back progress, cancellation and root back-to-home.
    val cardEditorSession = remember { CardEditorSession() }
    val navigation = rememberNavController()
    var handledLaunchRequest by rememberSaveable { mutableStateOf<Int?>(null) }
    fun navigate(tab: MainTab) {
        cardEditorSession.finish()
        if (tab == MainTab.REPORTS) navigation.currentBackStackEntry
            ?.takeIf { it.destination.route == tab.name }?.savedStateHandle?.set<Long?>(AppLaunchNavigation.REPORT_END, null)
        if (tab == MainTab.TRAFFIC) navigation.currentBackStackEntry
            ?.takeIf { it.destination.route == tab.name }?.savedStateHandle?.set<String?>(AppLaunchNavigation.SESSION_ID, null)
        if (tab == MainTab.EXTREME && navigation.currentDestination?.route == tab.name) {
            navigation.currentBackStackEntry?.savedStateHandle?.set<Long?>(ExtremeTestNavigation.REQUESTED_ID, null)
        }
        if (navigation.currentDestination?.route == tab.name) {
            if (tab == MainTab.SPEED) navigation.currentBackStackEntry?.savedStateHandle
                ?.set<Long?>(SpeedTestNavigation.REQUESTED_ID, null)
            return
        }
        navigation.navigate(tab.name) {
            if (tab in listOf(MainTab.HOME, MainTab.DISH, MainTab.NETWORK, MainTab.SETTINGS)) {
                popUpTo(MainTab.HOME.name) { inclusive = false }
            }
            launchSingleTop = true
        }
        if (tab == MainTab.REPORTS) navigation.currentBackStackEntry?.savedStateHandle
            ?.set<Long?>(AppLaunchNavigation.REPORT_END, null)
        if (tab == MainTab.TRAFFIC) navigation.currentBackStackEntry?.savedStateHandle
            ?.set<String?>(AppLaunchNavigation.SESSION_ID, null)
        // Ordinary entry must discard a saved result request even when reusing a route.
        if (tab == MainTab.SPEED) navigation.currentBackStackEntry?.savedStateHandle
            ?.set<Long?>(SpeedTestNavigation.REQUESTED_ID, null)
    }
    LaunchedEffect(initialTab, initialReportEnd, initialTestId, initialSessionId, initialRequestId) {
        AppLaunchNavigation.destination(initialTab, initialRequestId, handledLaunchRequest)?.let { tab ->
            navigate(tab)
            val handle = navigation.currentBackStackEntry?.savedStateHandle
            when (tab) {
                MainTab.SPEED -> handle?.set(SpeedTestNavigation.REQUESTED_ID, initialTestId)
                MainTab.EXTREME -> handle?.set(ExtremeTestNavigation.REQUESTED_ID, initialTestId)
                MainTab.REPORTS -> handle?.set(AppLaunchNavigation.REPORT_END, initialReportEnd)
                MainTab.TRAFFIC -> handle?.set(AppLaunchNavigation.SESSION_ID, initialSessionId)
                else -> Unit
            }
            handle?.set(AppLaunchNavigation.REQUEST_ID, initialRequestId)
            handledLaunchRequest = initialRequestId
        }
    }
    val currentEntry by navigation.currentBackStackEntryAsState()
    val selectedTab = MainTab.entries.firstOrNull { it.name == currentEntry?.destination?.route } ?: MainTab.HOME
    var hudVisible by rememberSaveable { mutableStateOf(true) }
    val motion = LocalOrbitMotion.current
    // Route structure is constant. Read live values inside destination composition,
    // so a new sample does not rebuild the entire graph or invalidate Router/Functions.
    val currentSnapshot = rememberUpdatedState(snapshot)
    val currentSamples = rememberUpdatedState(samples)
    val currentEvents = rememberUpdatedState(events)
    val currentLivePower = rememberUpdatedState(livePower)
    val currentBeaconState = rememberUpdatedState(beaconState)
    androidx.compose.animation.SharedTransitionLayout {
    val sharedScope = this
    Box(Modifier.fillMaxSize().observeCardEditorTouches(cardEditorSession)) {
    val graph = remember(navigation, beaconRepository, sharedScope) {
        navigation.createGraph(startDestination = MainTab.HOME.name) {
        MainTab.entries.forEach { tab ->
            composable(tab.name) { entry ->
                CompositionLocalProvider(LocalOrbitShared provides sharedScope, LocalOrbitVisibility provides this,
                    LocalCardEditorSession provides cardEditorSession) {
                LaunchedEffect(tab) {
                    if (!beaconRepository.requiresBluetoothSetup()) when (tab) {
                        MainTab.HOME -> beaconRepository.triggerAutoSync("home")
                        MainTab.BEACON -> beaconRepository.triggerAutoSync("beacon_screen")
                        else -> Unit
                    }
                }
                Scaffold(
                    topBar = {
                        if (tab != MainTab.ALIGNMENT && tab != MainTab.SKY) {
                            Row(Modifier.fillMaxWidth().statusBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_dish_antenna), null,
                                        modifier = Modifier.size(31.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("L-Shell Orbit", style = MaterialTheme.typography.titleLarge)
                                    orbitFunctions.firstOrNull { it.route == tab.name && tab !in listOf(MainTab.TRAFFIC, MainTab.PREFERENCES) }?.let {
                                        Text(stringResource(it.title), Modifier.orbitSharedBounds("destination:" + tab.name),
                                            style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    },
                    bottomBar = {
                        // Match the persistent bar's 64dp body + 8dp top/bottom padding.
                        if (tab != MainTab.ALIGNMENT && (tab != MainTab.SKY || hudVisible))
                            Spacer(Modifier.fillMaxWidth().navigationBarsPadding().height(80.dp))
                    }
                ) { padding ->
                    when (tab) {
                        MainTab.HOME -> Box(Modifier.padding(padding)) { DashboardScreen(currentSnapshot.value, currentSamples.value, currentBeaconState.value,
                            onOpenBeacon = { navigate(MainTab.BEACON) }, onOpenTraffic = { navigate(MainTab.TRAFFIC) },
                            onOpenSpeed = { navigate(MainTab.SPEED) }, onOpenExtreme = { navigate(MainTab.EXTREME) },
                            onOpenExtremeHistory = { navigate(MainTab.EXTREME_HISTORY) }, onOpenReports = { navigate(MainTab.REPORTS) },
                            livePower = currentLivePower.value) { metric ->
                            navigate(when (metric) {
                                MetricDetailKind.DOWNLOAD -> MainTab.DOWNLOAD
                                MetricDetailKind.UPLOAD -> MainTab.UPLOAD
                                MetricDetailKind.LATENCY -> MainTab.LATENCY
                                MetricDetailKind.SIGNAL -> MainTab.SIGNAL
                                MetricDetailKind.POWER -> MainTab.POWER
                            })
                        } }
                        MainTab.DISH -> DishDiagnosticsScreen(
                            currentSnapshot.value,
                            currentSamples.value,
                            currentEvents.value,
                            currentBeaconState.value.runtimeStatusOrNull()?.lastSyncAtMillis,
                            Modifier.padding(padding), livePower = currentLivePower.value
                        )
                        MainTab.NETWORK -> RouterScreen(Modifier.padding(padding))
                        MainTab.SPEED -> {
                            val requestedId by entry.savedStateHandle
                                .getStateFlow<Long?>(SpeedTestNavigation.REQUESTED_ID, null).collectAsState()
                            if (requestedId != null) SpeedTestScreen(Modifier.padding(padding), requestedId)
                            else SpeedTestHub(Modifier.padding(padding),
                                onNormal = { navigate(MainTab.NORMAL_TEST) },
                                onExtreme = { navigate(MainTab.EXTREME) },
                                onHistory = { navigate(MainTab.TEST_RESULTS) })
                        }
                        MainTab.NORMAL_TEST -> SpeedTestScreen(Modifier.padding(padding))
                        MainTab.TEST_RESULTS -> TestResultsScreen(Modifier.padding(padding),
                            onBack = { navigation.popBackStack() }, onExtremeArchive = { navigate(MainTab.EXTREME_HISTORY) })
                        MainTab.DIAGNOSTICS -> DiagnosticsHomeScreen(Modifier.padding(padding)) { destination ->
                            navigate(when (destination) {
                                DiagnosticDestination.HEALTH -> MainTab.HEALTH
                                DiagnosticDestination.ENERGY -> MainTab.ENERGY
                                DiagnosticDestination.TIMELINE -> MainTab.HISTORY
                                DiagnosticDestination.OUTAGES -> MainTab.OUTAGES
                                DiagnosticDestination.SINGLE_TEST -> MainTab.SPEED
                                DiagnosticDestination.EXTREME_TEST -> MainTab.EXTREME
                                DiagnosticDestination.EXTREME_HISTORY -> MainTab.EXTREME_HISTORY
                            })
                        }
                        MainTab.HEALTH -> InstallationHealthScreen(currentSnapshot.value, Modifier.padding(padding)) { navigation.popBackStack() }
                        MainTab.ENERGY -> EnergyScreen(currentSamples.value, Modifier.padding(padding), currentLivePower.value, currentSnapshot.value) { navigation.popBackStack() }
                        MainTab.TIMELINE -> SmartTimelineScreen(Modifier.padding(padding)) { navigation.popBackStack() }
                        MainTab.OUTAGES -> OutagesScreen(Modifier.padding(padding)) { navigation.popBackStack() }
                        MainTab.EXTREME -> {
                            val requestedId by entry.savedStateHandle
                                .getStateFlow<Long?>(ExtremeTestNavigation.REQUESTED_ID, null).collectAsState()
                            ExtremeSpeedTestScreen(currentSnapshot.value, Modifier.padding(padding), requestedId) { navigation.popBackStack() }
                        }
                        MainTab.EXTREME_HISTORY -> ExtremeTestHistoryScreen(Modifier.padding(padding)) { navigation.popBackStack() }
                        MainTab.HISTORY -> HistoryScreen(Modifier.padding(padding))
                        MainTab.REPORTS -> {
                            val requestedEnd by entry.savedStateHandle
                                .getStateFlow<Long?>(AppLaunchNavigation.REPORT_END, null).collectAsState()
                            val requestId by entry.savedStateHandle
                                .getStateFlow(AppLaunchNavigation.REQUEST_ID, 0).collectAsState()
                            androidx.compose.runtime.key(requestId) {
                                ConnectionHealthScreen(Modifier.padding(padding), requestedEnd) { navigation.popBackStack() }
                            }
                        }
                        MainTab.TERMINAL -> Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text(androidx.compose.ui.res.stringResource(if (LocalExperience.current.technical) R.string.editorial_term else R.string.function_dish), style = MaterialTheme.typography.headlineLarge)
                            Spacer(Modifier.height(16.dp))
                            DataCard(stringResource(R.string.terminal_alignment_title)) {
                                Text(stringResource(R.string.terminal_alignment_description))
                                Spacer(Modifier.height(12.dp))
                                OrbitActionButton(onClick = hapticClick { navigate(MainTab.ALIGNMENT) }) { Text(stringResource(R.string.terminal_alignment_open)) }
                            }
                        }
                        MainTab.ALIGNMENT -> AlignmentScreen(Modifier.padding(padding)) { navigation.popBackStack() }
                        MainTab.SKY -> SkyARScreen(snapshot = currentSnapshot.value, hudVisible = hudVisible, onToggleHud = { hudVisible = !hudVisible },
                            bottomInset = padding.calculateBottomPadding(), onExit = { navigation.popBackStack() })
                        MainTab.TECHNICAL -> TechnicalScreen(Modifier.padding(padding))
                        MainTab.TRAFFIC -> {
                            val requestedId by entry.savedStateHandle
                                .getStateFlow<String?>(AppLaunchNavigation.SESSION_ID, null).collectAsState()
                            val requestId by entry.savedStateHandle
                                .getStateFlow(AppLaunchNavigation.REQUEST_ID, 0).collectAsState()
                            TrafficMonitorScreen(currentSnapshot.value, currentSamples.value, currentEvents.value, modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                                requestedSessionId = requestedId, requestId = requestId, livePower = currentLivePower.value) { navigation.popBackStack() }
                        }
                        MainTab.SETTINGS -> FunctionsScreen(Modifier.padding(padding).consumeWindowInsets(padding)) { route -> navigate(MainTab.valueOf(route)) }
                        MainTab.PREFERENCES -> OrbitSettingsScreen(Modifier.padding(padding).consumeWindowInsets(padding),
                            onBack = { navigation.popBackStack() },onTechnical = { navigate(MainTab.TECHNICAL) })
                        MainTab.BEACON -> BeaconScreen(currentBeaconState.value, beaconRepository, Modifier.padding(padding),
                            onBack = { navigation.popBackStack() },
                            onOpenHistory = { navigate(MainTab.HISTORY) })
                        MainTab.SCHEDULED_TESTS, MainTab.SMART_TESTS -> AutomaticTestsScreen(
                            smart = tab == MainTab.SMART_TESTS, modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                            onBack = { navigation.popBackStack() })
                        MainTab.DOWNLOAD, MainTab.UPLOAD, MainTab.LATENCY, MainTab.SIGNAL, MainTab.POWER ->
                            MetricDetailScreen(when (tab) {
                                MainTab.DOWNLOAD -> MetricDetailKind.DOWNLOAD
                                MainTab.UPLOAD -> MetricDetailKind.UPLOAD
                                MainTab.LATENCY -> MetricDetailKind.LATENCY
                                MainTab.POWER -> MetricDetailKind.POWER
                                else -> MetricDetailKind.SIGNAL
                            }, currentSnapshot.value, Modifier.padding(padding).let {
                                if (tab == MainTab.DOWNLOAD || tab == MainTab.UPLOAD) it.consumeWindowInsets(padding) else it
                            }, onBack = { navigation.popBackStack() }, liveSamples = currentSamples.value, liveEvents = currentEvents.value,
                                livePower = currentLivePower.value)
                    }
                }
            }
        }
        }
        }
    }
    NavHost(navigation, graph = graph,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { fadeIn(tween(if (motion) 180 else 0)) },
        exitTransition = { fadeOut(tween(if (motion) 120 else 0)) },
        popEnterTransition = { fadeIn(tween(if (motion) 180 else 0)) },
        popExitTransition = { fadeOut(tween(if (motion) 120 else 0)) })
    AnimatedVisibility(selectedTab != MainTab.ALIGNMENT && (selectedTab != MainTab.SKY || hudVisible),
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = fadeIn(tween(if (motion) 180 else 0)) + slideInVertically(
            animationSpec = if (motion) spring(dampingRatio = .9f, stiffness = 380f) else snap(), initialOffsetY = { it / 4 }),
        exit = fadeOut(tween(if (motion) 120 else 0))) {
        DashNavigation(selectedTab, ::navigate)
    }
    } }
}

@Composable
private fun DashNavigation(selected: MainTab, onSelect: (MainTab) -> Unit) {
    MaterialTheme(colorScheme = if (selected == MainTab.SKY) arColorScheme() else MaterialTheme.colorScheme) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center) {
            val showLabel = maxWidth >= 390.dp && LocalDensity.current.fontScale <= 1.2f
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val mainTabs = listOf(MainTab.HOME, MainTab.DISH, MainTab.NETWORK, MainTab.SETTINGS)
                val selectedIndex = BottomNavigationPresentation.index(selected)
                var lastIndex by remember { mutableIntStateOf(selectedIndex.coerceAtLeast(0)) }
                LaunchedEffect(selectedIndex) { if (selectedIndex >= 0) lastIndex = selectedIndex }
                val motion = LocalOrbitMotion.current
                val indicatorIndex = animateFloatAsState(if (selectedIndex >= 0) selectedIndex.toFloat() else lastIndex.toFloat(),
                    OrbitMotion.spec(OrbitMotionRole.SPATIAL, motion), label = "moving nav indicator")
                val indicatorAlpha = animateFloatAsState(if (selectedIndex >= 0) 1f else 0f,
                    tween(if (motion) 140 else 0), label = "nav indicator visibility")
                val amoled = LocalExperience.current.theme == OrbitTheme.AMOLED
                Surface(Modifier.weight(1f), color = if (amoled) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = if (amoled) 0.dp else 2.dp, shadowElevation = if (amoled) 0.dp else 2.dp, shape = DashDesign.pill) {
                    BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp).padding(6.dp).clip(DashDesign.pill)) {
                        val cellWidth = maxWidth / mainTabs.size
                        val cellWidthPx = with(LocalDensity.current) { cellWidth.toPx() }
                        Surface(Modifier.width(cellWidth).height(52.dp)
                            .offset { IntOffset((cellWidthPx * indicatorIndex.value.coerceIn(0f, mainTabs.lastIndex.toFloat())).roundToInt(), 0) }
                            .graphicsLayer { alpha = indicatorAlpha.value },
                            color = MaterialTheme.colorScheme.primaryContainer, shape = DashDesign.pill) {}
                        Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                            mainTabs.forEachIndexed { index, tab ->
                                val tabLabel = tab.localizedLabel()
                                val active = selectedIndex == index
                                val interaction = remember(tab) { MutableInteractionSource() }
                                val iconScale = animateFloatAsState(if (active) 1.06f else 1f,
                                    OrbitMotion.spec(OrbitMotionRole.SETTLE, motion), label = "nav emphasis")
                                Column(Modifier.weight(1f).height(52.dp)
                                    .pressMotion(interaction, .97f).clip(DashDesign.pill)
                                    .selectable(active, interactionSource = interaction, indication = androidx.compose.material3.ripple(),
                                        role = Role.Tab, onClick = hapticClick(DashFeedback.NAVIGATION) { onSelect(tab) })
                                    .semantics { contentDescription = tabLabel }
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    val tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    Icon(tab.icon, null, Modifier.size(24.dp).graphicsLayer { scaleX = iconScale.value; scaleY = iconScale.value }, tint)
                                    if (active && showLabel) Text(tabLabel, Modifier.clearAndSetSemantics {},
                                        style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
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
                    if (LocalOrbitMotion.current) spring(dampingRatio = 0.9f, stiffness = 220f) else snap(), label = "AR activation")
                val arLabel = stringResource(R.string.polish_ar_open)
                Surface(onClick = hapticClick(DashFeedback.SPECIAL) {
                    if (selected == MainTab.SKY) arRotationTarget += 360f
                    onSelect(MainTab.SKY)
                }, interactionSource = arInteraction, shape = DashDesign.cookie,
                    color = if (selected == MainTab.SKY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = if (selected == MainTab.SKY) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(60.dp).pressMotion(arInteraction, 0.9f)
                        .graphicsLayer { rotationZ = arRotation }
                        .semantics { contentDescription = arLabel; this.selected = selected == MainTab.SKY }) {
                    Column(Modifier.graphicsLayer { rotationZ = -arRotation },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(OrbitSymbols.CameraAlt, null, Modifier.size(24.dp))
                        Text("AR", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DishDiagnosticsScreen(
    snapshot: DishySnapshot,
    samples: List<TelemetrySample>,
    events: List<ConnectionEvent>,
    beaconHistoryRevision: Long?,
    modifier: Modifier = Modifier,
    livePower: LivePowerReading? = null
) {
    val latestPower = recentAntennaPower(livePower, System.currentTimeMillis())
    // Keep the map request and client owned by the destination, not a recycled item.
    val obstructionMap = rememberObstructionMapState()
    val controls = rememberDishControlState()
    val listScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val editorScroll = rememberCardEditorScroll(listScroll)
    LazyColumn(modifier.fillMaxSize().cardEditorViewport(editorScroll), state = listScroll,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
        item("title") {
            Text(stringResource(R.string.function_dish), Modifier.padding(bottom = 12.dp),
                style = MaterialTheme.typography.headlineMedium)
        }
        item("status") { Column { DishStatusHeroCard(snapshot, latestPower); Spacer(Modifier.height(12.dp)) } }
        item("controls") { Column { DishControlCard(snapshot, controls); Spacer(Modifier.height(16.dp)) } }
        if (snapshot.alerts.getActiveAlertsList().isNotEmpty()) {
            item("alerts") { Column { AlertsCard(snapshot.alerts.getActiveAlertsList()); Spacer(Modifier.height(16.dp)) } }
        }
        item("field-of-view") { Column { AntennaFieldOfViewCard(snapshot); Spacer(Modifier.height(16.dp)) } }
        item("obstruction-map") {
            Column { ObstructionMapCard(snapshot.deviceInfo.boresightAzimuthDeg, obstructionMap); Spacer(Modifier.height(16.dp)) }
        }
        item("hardware") { Column { HardwareInfoCard(snapshot); Spacer(Modifier.height(16.dp)) } }
        item("orientation") {
            Column {
                DataCard(stringResource(R.string.ui_signal_and_orientation)) {
                    InfoRow(stringResource(R.string.metric_detail_signal), signalLabel(snapshot.latency.isSnrAboveNoiseFloor))
                    InfoRow(stringResource(R.string.ui_azimuth), "${snapshot.deviceInfo.boresightAzimuthDeg}°")
                    InfoRow(stringResource(R.string.ui_elevation), "${snapshot.deviceInfo.boresightElevationDeg}°")
                    InfoRow(stringResource(R.string.ui_valid_time),
                        stringResource(R.string.ui_duration_seconds_short, snapshot.obstruction.validSeconds.toInt()))
                }
                Spacer(Modifier.height(20.dp))
            }
        }
        item("telemetry-charts") { Column { TelemetryCharts(samples, beaconHistoryRevision, editorScroll, snapshot, latestPower); Spacer(Modifier.height(20.dp)) } }
        item("events") {
            DataCard(stringResource(R.string.ui_events_observed_by_the_app)) {
                if (events.isEmpty()) Text(stringResource(R.string.ui_no_changes_recorded_in_this_session))
                events.forEach { event ->
                    InfoRow(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.timestamp)), telemetryLabel(event.description))
                }
            }
        }
    }
}

private class DishControlState(val client: DishyGrpcClient, val scope: kotlinx.coroutines.CoroutineScope) {
    val pending = mutableStateOf<DishyGrpcClient.Control?>(null)
    val busy = mutableStateOf(false)
    val feedback = mutableStateOf<String?>(null)
    val feedbackDetail = mutableStateOf<String?>(null)
}

@Composable
private fun rememberDishControlState(): DishControlState {
    val client = remember { DishyGrpcClient() }
    DisposableEffect(client) { onDispose { client.close() } }
    val scope = rememberCoroutineScope()
    return remember(client, scope) { DishControlState(client, scope) }
}

@Composable
private fun DishControlCard(snapshot: DishySnapshot, controls: DishControlState) {
    val client = controls.client
    val scope = controls.scope
    var pending by controls.pending
    var busy by controls.busy
    var feedback by controls.feedback
    val context = LocalContext.current
    var feedbackDetail by controls.feedbackDetail
    val labels = mapOf(
        DishyGrpcClient.Control.REBOOT to androidx.compose.ui.res.stringResource(R.string.editorial_command_reboot),
        DishyGrpcClient.Control.STOW to androidx.compose.ui.res.stringResource(R.string.editorial_command_stow),
        DishyGrpcClient.Control.UNSTOW to androidx.compose.ui.res.stringResource(R.string.editorial_command_unstow),
        DishyGrpcClient.Control.INHIBIT_GPS to androidx.compose.ui.res.stringResource(R.string.editorial_command_gps)
    )
    DataCard(androidx.compose.ui.res.stringResource(R.string.editorial_command_title)) {
        Text(androidx.compose.ui.res.stringResource(R.string.editorial_command_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth / LocalDensity.current.fontScale >= 300.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(DishyGrpcClient.Control.REBOOT, DishyGrpcClient.Control.STOW,
                    DishyGrpcClient.Control.UNSTOW, DishyGrpcClient.Control.INHIBIT_GPS)
                    .chunked(columns).forEach { actions ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            actions.forEach { action ->
                                OrbitActionButton(style = OrbitActionStyle.COMMAND,
                                    onClick = hapticClick { pending = action },
                                    enabled = snapshot.isOnline && !busy, modifier = Modifier.weight(1f)) {
                                    Text(labels.getValue(action), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            }
                        }
                    }
            }
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (LocalExperience.current.technical) feedbackDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    pending?.let { action ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(labels.getValue(action)) },
            text = { Text(when (action) {
                DishyGrpcClient.Control.REBOOT -> androidx.compose.ui.res.stringResource(R.string.editorial_command_reboot_body)
                DishyGrpcClient.Control.STOW -> androidx.compose.ui.res.stringResource(R.string.editorial_command_stow_body)
                DishyGrpcClient.Control.UNSTOW -> androidx.compose.ui.res.stringResource(R.string.editorial_command_unstow_body)
                DishyGrpcClient.Control.INHIBIT_GPS -> androidx.compose.ui.res.stringResource(R.string.editorial_command_gps_body)
            }) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = hapticClick(DashFeedback.DESTRUCTIVE) {
                    pending = null
                    busy = true
                    scope.launch {
                        feedback = client.control(action).fold(
                            onSuccess = { feedbackDetail = null; context.getString(R.string.editorial_command_sent) },
                            onFailure = { feedbackDetail = it.message; context.getString(R.string.editorial_command_error) }
                        )
                        busy = false
                    }
                }) { Text(labels.getValue(action)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = hapticClick { pending = null }) { Text(androidx.compose.ui.res.stringResource(R.string.editorial_cancel)) }
            }
        )
    }
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
