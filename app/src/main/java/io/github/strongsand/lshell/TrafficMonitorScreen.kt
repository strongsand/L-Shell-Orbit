package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.strongsand.lshell.beacon.BeaconPreferences
import io.github.strongsand.lshell.core.data.ConnectionEvent
import io.github.strongsand.lshell.core.data.LivePowerReading
import io.github.strongsand.lshell.core.data.TelemetrySample
import io.github.strongsand.lshell.core.model.DishySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Locale

internal data class TrafficView(val analysis: TrafficAnalysis, val series: Map<TrafficMetric, MetricHistorySeries>,
    val power: MetricHistorySeries = MetricHistorySeries(analysis.start, analysis.end, emptyList(), 0, emptyList()))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrafficMonitorScreen(snapshot: DishySnapshot, samples: List<TelemetrySample>, events: List<ConnectionEvent>,
    initial: MetricDetailKind = MetricDetailKind.DOWNLOAD, modifier: Modifier = Modifier,
    requestedSessionId: String? = null, requestId: Int = 0, livePower: LivePowerReading? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val store = remember { TrafficSessionStore(context) }
    val controller = remember { TrafficSessionController.get(context) }
    val activityState by controller.state.collectAsState()
    val sessionRevision by TrafficSessionStore.revision.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberDashHaptics()
    var minutes by rememberSaveable { mutableIntStateOf(15) }
    var series by rememberSaveable { mutableIntStateOf(when (initial) { MetricDetailKind.UPLOAD -> 1; MetricDetailKind.POWER -> 2; else -> 0 }) }
    var quality by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSessions by rememberSaveable { mutableStateOf(false) }
    var sessions by remember { mutableStateOf<List<TrafficSession>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var metadataFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var view by remember { mutableStateOf<TrafficView?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var nameDialog by remember { mutableStateOf(false) }
    var permissionHelp by remember { mutableStateOf(false) }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && trafficNotificationsAllowed(context)) nameDialog = true else permissionHelp = true
    }
    var name by rememberSaveable { mutableStateOf("") }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var archiveExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<TrafficEvent?>(null) }
    var exportResult by remember { mutableStateOf<Int?>(null) }
    val technical = LocalExperience.current.technical
    val active = sessions.firstOrNull { it.active }
    val session = sessions.firstOrNull { it.id == selectedId }
    val cadence = if (minutes > 15 || (session != null && now - session.startedAt > 900_000)) 30_000 else 5_000
    val refreshKey = snapshot.timestamp / cadence
    DisposableEffect(lifecycle) {
        val prefs = BeaconPreferences(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "background_completed" || key == "last_sync_at") revision++
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) revision++ }
        prefs.observeSync(listener); lifecycle.addObserver(observer)
        onDispose { prefs.stopObservingSync(listener); lifecycle.removeObserver(observer) }
    }
    // Visible clock only: no RPC, history query or statistical calculation from this timer.
    val passiveSampleTime = listOfNotNull(
        snapshot.timestamp.takeIf { series != 2 && it > 0L },
        livePower?.timestamp?.takeIf { series >= 2 && it > 0L }
    ).minOrNull()?.takeIf {
        active == null && session == null && snapshot.isOnline && !snapshot.isFromCache
    }
    LaunchedEffect(lifecycle, active?.id, session?.id, passiveSampleTime) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            now = System.currentTimeMillis()
            if (active != null) {
                while (isActive) { now = System.currentTimeMillis(); delay(1_000) }
            } else if (passiveSampleTime != null) {
                // One freshness deadline, not a ticker, for the passive current-reading panel.
                val age = now - passiveSampleTime
                if (age in 0L..15_000L) {
                    delay(15_001L - age)
                    now = System.currentTimeMillis()
                }
            }
        }
    }
    var initialSelectionDone by rememberSaveable { mutableStateOf(false) }
    var handledRequest by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(revision, sessionRevision, requestedSessionId, requestId) {
        try { sessions = withContext(Dispatchers.IO) { store.read() }; metadataFailed = false }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { metadataFailed = true }
        if (requestedSessionId != null && handledRequest != requestId && sessions.any { it.id == requestedSessionId }) {
            selectedId = requestedSessionId; showSessions = false
            series = when (sessions.first { it.id == requestedSessionId }.mode) {
                TrafficSessionMode.DOWNLOAD -> 0; TrafficSessionMode.UPLOAD -> 1; TrafficSessionMode.ENERGY -> 2; TrafficSessionMode.BOTH -> 3
            }
            initialSelectionDone = true; handledRequest = requestId
        } else if (!initialSelectionDone) {
            sessions.firstOrNull { it.active }?.let {
                selectedId = it.id
                series = when (it.mode) { TrafficSessionMode.DOWNLOAD -> 0; TrafficSessionMode.UPLOAD -> 1; TrafficSessionMode.ENERGY -> 2; TrafficSessionMode.BOTH -> 3 }
            }
            initialSelectionDone = true
        }
    }
    var viewScope by remember { mutableStateOf<Pair<Int, String?>?>(null) }
    LaunchedEffect(minutes, selectedId, session?.endedAt, session?.startedAt, refreshKey, revision) {
        loading = true
        val newScope = minutes to selectedId
        if (newScope != viewScope) { view = null; exportResult = null; viewScope = newScope }
        try {
            val end = session?.endedAt ?: System.currentTimeMillis()
            val start = session?.startedAt ?: (end - minutes * 60_000L)
            val rows = withContext(Dispatchers.IO) { HistoryStore(context).use { it.between(start - 5_000, end) } }
            view = withContext(Dispatchers.Default) {
                val live = samples.filter { it.timestamp >= start - 5_000 && it.timestamp < end }.map {
                    HistoryEntry(it.timestamp, if (it.isHistory) "terminal_history" else "reading", "",
                        it.downloadMbps, it.uploadMbps, it.latencyMs, it.dropPercent, powerWatts = it.powerWatts)
                }
                val livePowerEntry = livePower?.takeIf { snapshot.isOnline && !snapshot.isFromCache && it.timestamp in (start - 5_000) until end }?.let {
                    listOf(HistoryEntry(it.timestamp, "terminal_history", "", powerWatts = it.powerWatts))
                }.orEmpty()
                val status = snapshot.takeIf { it.isOnline && !it.isFromCache && it.timestamp in start until end }?.let {
                    listOf(HistoryEntry(it.timestamp,"reading","",it.throughput.downlinkMbps,it.throughput.uplinkMbps,
                        it.latency.popPingLatencyMs,it.latency.popPingDropRate * 100,it.obstruction.obstructionPercentage,
                        dishState = it.state.name, activeAlerts = it.alerts.getActiveAlertsList().joinToString("|")))
                }.orEmpty()
                val liveEvents = events.filter { it.timestamp in start until end }.map { HistoryEntry(it.timestamp, "event", it.description) }
                val allRows = rows + live + livePowerEntry + status + liveEvents
                val analyzed = TrafficSessionAnalyzer.analyze(allRows, start, end)
                TrafficView(analyzed, TrafficMetric.entries.associateWith {
                    TrafficChartPresentation.series(analyzed, it)
                }, MetricHistorySeriesBuilder.build(allRows, HistoricalMetric.POWER, start, end))
            }
            failed = false
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    fun operation(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    fun openSession(id: String, mode: TrafficSessionMode? = sessions.firstOrNull { it.id == id }?.mode) {
        selectedId = id; showSessions = false
        series = when (mode) { TrafficSessionMode.DOWNLOAD -> 0; TrafficSessionMode.UPLOAD -> 1; TrafficSessionMode.ENERGY -> 2; else -> 3 }
        quality = true
    }
    androidx.activity.compose.BackHandler(showSessions || selectedId != null) {
        if (showSessions) showSessions = false else selectedId = null
    }

    val defaultChartOrder = remember { listOf(TrafficMetric.DOWNLOAD.name, TrafficMetric.UPLOAD.name, "POWER", TrafficMetric.LATENCY.name, TrafficMetric.LOSS.name) }
    val chartArrangement = rememberCardArrangement("traffic", defaultChartOrder, eligible = emptySet())
    val chartOrder = chartArrangement.order
    val listScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val editorScroll = rememberCardEditorScroll(listScroll)
    val primary = remember(series) {
        when (series) {
            1 -> listOf(TrafficMetric.UPLOAD)
            2 -> emptyList()
            3 -> listOf(TrafficMetric.DOWNLOAD,TrafficMetric.UPLOAD)
            else -> listOf(TrafficMetric.DOWNLOAD)
        }
    }
    val visibleCharts = remember(primary, series, quality) {
        val base = primary.map { it.name } + if (series == 2 || series == 3) listOf("POWER") else emptyList()
        base + if (quality) listOf(TrafficMetric.LATENCY.name, TrafficMetric.LOSS.name) else emptyList()
    }
    val orderedCharts = remember(chartOrder, visibleCharts) {
        chartOrder.filter { it in visibleCharts }
    }
    val shownView = view
    LazyColumn(modifier.fillMaxSize().cardEditorViewport(editorScroll), state = listScroll, contentPadding = PaddingValues(start = 16.dp,end = 16.dp,top = 8.dp,bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = hapticClick {
                    if (showSessions) showSessions = false else if (selectedId != null) selectedId = null else onBack()
                }) { Icon(OrbitSymbols.ArrowBack,stringResource(R.string.traffic_back)) }
                Text(stringResource(R.string.traffic_title),Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = hapticClick { showHelp = true }) {
                    Icon(OrbitSymbols.Info, stringResource(R.string.traffic_how))
                }
            }
        }
        if (failed || metadataFailed) item(key = "error") {
            Text(stringResource(R.string.traffic_error),color = MaterialTheme.colorScheme.error)
            TextButton(onClick = hapticClick { revision++ }) { Text(stringResource(R.string.traffic_retry)) }
        }
        item(key = "recording-controls") {
            TrafficRecordingControls(active,activityState,now,busy,
                canRecord = !failed && !metadataFailed,recordingsShown = showSessions,
                canOpenActive = active != null && (selectedId != active.id || showSessions),
                onRecord = hapticClick {
                    name = ""
                    if (trafficNotificationsAllowed(context)) nameDialog = true
                    else if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                            context,android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else permissionHelp = true
                },
                onRecordings = hapticClick { showSessions = !showSessions },
                onOpenActive = hapticClick { active?.let { openSession(it.id) } },
                onFinish = {
                    active?.let { recording -> operation {
                        controller.finish(recording.id)
                        openSession(recording.id); revision++; haptics.perform(DashFeedback.CONFIRM)
                    } }
                },
                onRetry = hapticClick { operation { controller.restoreFromVisibleApp() } })
        }
        if (showSessions) {
            if (sessions.isEmpty()) item {
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (loading) Text(stringResource(R.string.traffic_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        Text(stringResource(R.string.traffic_no_sessions), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.ux_sessions_empty_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(sessions,key = { it.id }) { saved ->
                Surface(onClick = hapticClick { openSession(saved.id) },shape = DashDesign.section,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(saved.name,style = MaterialTheme.typography.titleMedium)
                        Text(trafficDate(saved.startedAt,saved.timezone),style = MaterialTheme.typography.bodySmall)
                        Text(if (saved.active) stringResource(R.string.traffic_session_active)
                            else trafficShortDuration(saved.endedAt!! - saved.startedAt),style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        } else {
            if (session != null && !session.active) item(key = "session") {
                Surface(Modifier.fillMaxWidth(),shape = DashDesign.section,color = amoledCardColor(MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(session.name,style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(if (session.active) R.string.traffic_session_active else R.string.traffic_session_done)
                            + " • " + trafficShortDuration((session.endedAt ?: now) - session.startedAt),
                            style = MaterialTheme.typography.labelLarge)
                        Text(trafficDate(session.startedAt,session.timezone),style = MaterialTheme.typography.bodySmall)
                        if (session.interrupted) Text(stringResource(R.string.session_interrupted),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (session != null) item(key = "live-window") {
                TextButton(onClick = hapticClick { selectedId = null }) { Text(stringResource(R.string.traffic_live_window)) }
            }
            item(key = "controls") {
                Surface(Modifier.fillMaxWidth(), shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (session == null || session.active) {
                            val current = snapshot.takeIf { it.isOnline && !it.isFromCache && now - it.timestamp in 0..15_000 }
                            val currentPower = if (snapshot.isOnline && !snapshot.isFromCache) recentAntennaPower(livePower, now) else null
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val narrow = maxWidth / LocalDensity.current.fontScale < 320.dp
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (series == 3 && !narrow) Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        TrafficCurrent(stringResource(R.string.traffic_download),current?.throughput?.downlinkMbps,Modifier.weight(1f))
                                        TrafficCurrent(stringResource(R.string.traffic_upload),current?.throughput?.uplinkMbps,Modifier.weight(1f))
                                    } else {
                                        if (series == 0 || series == 3) TrafficCurrent(stringResource(R.string.traffic_download),current?.throughput?.downlinkMbps)
                                        if (series == 1 || series == 3) TrafficCurrent(stringResource(R.string.traffic_upload),current?.throughput?.uplinkMbps)
                                    }
                                    if (series == 2 || series == 3) TrafficCurrent(stringResource(R.string.editorial_dish_power),currentPower,unit = "W")
                                }
                            }
                        }

                        if (session == null) {
                            Text(stringResource(R.string.traffic_window),Modifier.semantics { heading() },style = MaterialTheme.typography.labelLarge)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(listOf(5,15,30,60,120,360,1440),key = { it }) { m ->
                                    OrbitFilterChip(minutes == m,hapticClick { minutes = m },modifier = Modifier.heightIn(min = 48.dp),label = {
                                        Text(stringResource(if (m < 60) R.string.traffic_minutes else R.string.traffic_hours,if (m < 60) m else m / 60))
                                    },leadingIcon = if (minutes == m) {{ Icon(OrbitSymbols.Check,null,Modifier.size(18.dp)) }} else null)
                                }
                            }
                        }
                        TrafficSeriesSelector(series) { series = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.traffic_quality),Modifier.weight(1f),style = MaterialTheme.typography.bodyMedium)
                            val qualityLabel = stringResource(R.string.traffic_quality)
                            Switch(quality,hapticToggle { quality = it }, modifier = Modifier.semantics { contentDescription = qualityLabel })
                            IconButton(enabled = !loading, onClick = hapticClick { revision++ }) {
                                Icon(OrbitSymbols.Refresh,stringResource(R.string.traffic_refresh))
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(4.dp)) {
                            if (loading) {
                                val loadingLabel = stringResource(R.string.traffic_loading)
                                LinearProgressIndicator(Modifier.fillMaxSize().semantics { contentDescription = loadingLabel })
                            }
                        }
                        shownView?.let { v ->
                            val a = v.analysis
                            val powerOrigin = TrafficSessionAnalyzer.origin(v.power.points.filter {
                                it.time >= v.power.start && it.measurement != null
                            }.map { it.measurement!!.origin })
                            val coverage = if (series == 2) v.power.coveragePercent else a.coveragePercent
                            val coveredMs = if (series == 2) v.power.coveredMs else a.coveredMs
                            val origin = when (series) {
                                2 -> powerOrigin
                                3 -> TrafficSessionAnalyzer.origin(listOf(a.origin, powerOrigin))
                                else -> a.origin
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.traffic_coverage,trafficNumber(coverage)),style = MaterialTheme.typography.labelMedium)
                                TrafficSourceBadge(origin)
                                Text(stringResource(R.string.traffic_gap_short,trafficShortDuration(a.durationMs - coveredMs)),
                                    style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            shownView?.let { data ->
                val a = data.analysis
                orderedCharts.forEachIndexed { index, chartId ->
                    item(key = "chart:$chartId") {
                        val label = if (chartId == "POWER") stringResource(R.string.editorial_dish_power)
                            else trafficMetricName(TrafficMetric.valueOf(chartId))
                        ReorderableChart(chartId, label, chartArrangement, visibleCharts, listScroll, editorScroll,
                            modifier = Modifier.animateItem(placementSpec = OrbitMotion.spec(OrbitMotionRole.SPATIAL, LocalOrbitMotion.current))) {
                            if (chartId == "POWER") {
                                TrafficPowerChart(data, session?.timezone, onEvent = { selectedEvent = it })
                            } else {
                                TrafficChart(data, TrafficMetric.valueOf(chartId), session?.timezone, onEvent = { selectedEvent = it })
                            }
                        }
                    }
                }
                item(key = "summary") { SectionHeading(stringResource(R.string.traffic_summary)) }
                primary.forEach { metric ->
                    item(key = "stats:$metric") {
                        if (primary.size > 1) Text(trafficMetricName(metric),Modifier.padding(bottom = 10.dp),style = MaterialTheme.typography.titleMedium)
                        TrafficStats(a.summary(metric))
                    }
                }
                if (series == 2 || series == 3) {
                    item(key = "stats:POWER") {
                        if (series == 3) Text(stringResource(R.string.editorial_dish_power),Modifier.padding(bottom = 10.dp),style = MaterialTheme.typography.titleMedium)
                        TrafficPowerStats(data.power)
                    }
                }
                if (quality || session != null) item(key = "quality-summary") {
                    TrafficStatGrid(listOf(
                        stringResource(R.string.traffic_latency) + " · " + stringResource(R.string.traffic_median) to trafficValue(a.latency.median,"ms"),
                        stringResource(R.string.traffic_loss) + " · " + stringResource(R.string.traffic_mean) to trafficValue(a.loss.mean,"%")))
                }
                item(key = "events") { TrafficEventSection(a,session?.timezone) { selectedEvent = it } }
                if (technical) item(key = "technical") { TrafficTechnical(a,primary,data.power.takeIf { series == 2 || series == 3 }) }
                if (session != null) item(key = "export") {
                    OrbitActionButton(style = OrbitActionStyle.SECONDARY, onClick = hapticClick {
                        operation {
                            try {
                                ReportArchive.exportTraffic(context,session,a)
                                exportResult = R.string.traffic_export_ok; haptics.perform(DashFeedback.CONFIRM)
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { exportResult = R.string.traffic_export_error }
                        }
                    },enabled = !busy && !loading) { Text(stringResource(R.string.traffic_export)) }
                    exportResult?.let { Text(stringResource(it)) }
                    TextButton(onClick = hapticClick { archiveExpanded = !archiveExpanded }) {
                        Text(stringResource(R.string.traffic_export_options))
                    }
                    if (archiveExpanded) ReportArchiveControls()
                }
            }
            if (shownView == null && !loading) item { Text(stringResource(R.string.traffic_empty)) }
        }

    }
    if (showHelp) TrafficHelp { showHelp = false }
    if (nameDialog) AlertDialog(onDismissRequest = { if (!busy) nameDialog = false },
        title = { Text(stringResource(R.string.traffic_session)) }, text = {
            OutlinedTextField(name,{ name = it.take(120) },label = { Text(stringResource(R.string.traffic_name)) },singleLine = true)
        }, confirmButton = { TextButton(onClick = {
            operation {
                val started = controller.start(name, when (series) { 0 -> TrafficSessionMode.DOWNLOAD; 1 -> TrafficSessionMode.UPLOAD; 2 -> TrafficSessionMode.ENERGY; else -> TrafficSessionMode.BOTH },
                    snapshot.deviceInfo.hardwareVersion.takeIf { snapshot.isOnline && it.isNotBlank() },
                    BeaconPreferences(context).configuredBeacon() != null)
                openSession(started.id, started.mode); revision++; nameDialog = false
                haptics.perform(DashFeedback.CONFIRM)
            }
        },enabled = !busy) { Text(stringResource(R.string.traffic_start)) } },
        dismissButton = { TextButton(onClick = { nameDialog = false },enabled = !busy) { Text(stringResource(R.string.traffic_cancel)) } })
    selectedEvent?.let { event -> TrafficEventDialog(event,session?.timezone) { selectedEvent = null } }
    if (permissionHelp) AlertDialog(onDismissRequest = { permissionHelp = false },
        title = { Text(stringResource(R.string.session_notification_title)) },
        text = { Text(stringResource(R.string.session_notification_help)) },
        confirmButton = { TextButton(onClick = {
            permissionHelp = false
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName))
        }) { Text(stringResource(R.string.session_notification_settings)) } },
        dismissButton = { TextButton(onClick = { permissionHelp = false }) { Text(stringResource(R.string.traffic_cancel)) } })
}


@Composable
private fun TrafficCurrent(title: String, value: Float?, modifier: Modifier = Modifier, unit: String = "Mbps") {
    val reading = value?.takeIf { it.isFinite() && it >= 0 }
    val readingStyle = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum")
    val readingHeight = with(LocalDensity.current) { readingStyle.lineHeight.toDp() }
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // One font-scaled slot for both measured values and the unavailable dash.
        // Different glyph/font metrics must not change the height of the surrounding card.
        Box(Modifier.fillMaxWidth().height(readingHeight), contentAlignment = Alignment.CenterStart) {
            Text(reading?.let { trafficNumber(it.toDouble()) } ?: "\u2014",
                style = readingStyle, minLines = 1, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrafficSeriesSelector(series: Int,onSelect: (Int) -> Unit) {
    val labels = listOf(R.string.traffic_download,R.string.traffic_upload,R.string.function_energy,R.string.traffic_both)
    val description = stringResource(R.string.traffic_series)
    BoxWithConstraints(Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        if (maxWidth / LocalDensity.current.fontScale >= 320.dp) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { index,label ->
                    OrbitSegmentedButton(selected = series == index,onClick = hapticClick { onSelect(index) },
                        shape = SegmentedButtonDefaults.itemShape(index,labels.size)) {
                        Text(stringResource(label), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        } else FlowRow(Modifier.selectableGroup(),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { index,label ->
                OrbitFilterChip(series == index,hapticClick { onSelect(index) },modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(stringResource(label)) },
                    leadingIcon = if (series == index) {{ Icon(OrbitSymbols.Check,null,Modifier.size(18.dp)) }} else null)
            }
        }
    }
}

@Composable
private fun TrafficHelp(dismiss: () -> Unit) {
    val sections = listOf(
        R.string.traffic_help_estimates to R.string.traffic_estimate_note,
        R.string.traffic_help_coverage to R.string.traffic_help_coverage_body,
        R.string.traffic_help_sustained to R.string.traffic_help_sustained_body,
        R.string.traffic_help_events to R.string.traffic_state_note,
        R.string.traffic_help_sessions to R.string.traffic_session_retention)
    AlertDialog(onDismissRequest = dismiss,title = { Text(stringResource(R.string.traffic_how)) },text = {
        LazyColumn(Modifier.heightIn(max = 480.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.traffic_passive),style = MaterialTheme.typography.bodyMedium) }
            items(sections) { (title,body) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(title),Modifier.semantics { heading() },style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(body),style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    },confirmButton = { TextButton(onClick = hapticClick(action = dismiss)) { Text(stringResource(R.string.traffic_back)) } })
}
internal fun trafficNumber(value: Double) = String.format(Locale.getDefault(),"%.1f",value)
@Composable
internal fun trafficValue(value: Double?, unit: String) = value?.takeIf { it.isFinite() }?.let { "${trafficNumber(it)} $unit" } ?: stringResource(R.string.editorial_no_reading)
internal fun trafficBytes(bytes: Double): String = when {
    bytes >= 1_000_000_000 -> trafficNumber(bytes / 1_000_000_000) + " GB"
    bytes >= 1_000_000 -> trafficNumber(bytes / 1_000_000) + " MB"
    bytes >= 1_000 -> trafficNumber(bytes / 1_000) + " kB"
    else -> trafficNumber(bytes) + " B" }
