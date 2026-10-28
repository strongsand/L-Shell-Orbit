package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.strongsand.lshell.beacon.BeaconPreferences
import kotlinx.coroutines.CancellationException

internal enum class HomeTestDestination { HUB }
internal object HomeShortcutPolicy {
    // The card is an entry to the hub, not a request to view the last execution.
    fun destination(state: ConnectionTestState) = HomeTestDestination.HUB
    fun result(state: ConnectionTestState): TestMetrics? =
        if (!state.active && state.phase == ConnectionTestPhase.COMPLETED && state.kind == ConnectionTestKind.NORMAL)
            ConnectionTestPresentation.metrics(state).takeIf { it.download != null || it.upload != null }
        else null
}

/** Read-only shortcuts: existing controller and report cache remain the sources of truth. */
@Composable internal fun HomeFeatureCards(onSpeed: () -> Unit, onExtreme: () -> Unit,
    onExtremeHistory: () -> Unit, onReports: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember(context) { ConnectionTestController.get(context) }
    val execution by controller.state.collectAsState()
    val reportRevision by ConnectionHealthStore.revision.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<HealthReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(context, lifecycle) {
        val prefs = BeaconPreferences(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "background_completed" || key == "last_sync_at") refresh++
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) refresh++ }
        prefs.observeSync(listener); lifecycle.addObserver(observer)
        onDispose { prefs.stopObservingSync(listener); lifecycle.removeObserver(observer) }
    }
    // Once per entry/revision/resume, never on the one-second telemetry clock.
    LaunchedEffect(context, reportRevision, refresh, execution.endedAt) {
        loading = true
        try {
            report = ConnectionHealthStore.current(context, HealthPeriodKind.WEEK)
            failed = false
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true; report = null }
        finally { loading = false }
    }
    val shownReport = remember(report) { PreparedHealthReport.from(report) }
    HomeShortcutCards(execution, shownReport, loading, failed, onSpeed, onExtreme, onExtremeHistory, onReports)
}

/** Stateless presentation also used by synthetic editor previews. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun HomeShortcutCards(shownTest: ConnectionTestState, shownReport: PreparedHealthReport?,
    shownLoading: Boolean = false, shownFailed: Boolean = false, onSpeed: () -> Unit = {}, onExtreme: () -> Unit = {},
    onExtremeHistory: () -> Unit = {}, onReports: () -> Unit = {}) {
    val metrics = HomeShortcutPolicy.result(shownTest)
    ReportSummaryGrid(listOf(
        {
            HomeFeatureCard(OrbitSymbols.Speed, stringResource(R.string.function_speed), onClick = {
                when (HomeShortcutPolicy.destination(shownTest)) {
                    HomeTestDestination.HUB -> onSpeed()
                }
            }) {
                when {
                    shownTest.active -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(OrbitSymbols.PlayCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.density_test_active), style = MaterialTheme.typography.titleMedium)
                        }
                        if (shownTest.kind == ConnectionTestKind.EXTREME)
                            Text(stringResource(R.string.density_round, shownTest.currentRound, shownTest.total), style = MaterialTheme.typography.labelMedium)
                    }
                    metrics != null -> {
                        val down = metrics.download?.let { healthDecimal(it.toDouble(), 0) }
                        val up = metrics.upload?.let { healthDecimal(it.toDouble(), 0) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            DirectionalMetric(true, "${down ?: "—"} Mbps", style = MaterialTheme.typography.titleMedium)
                            DirectionalMetric(false, "${up ?: "—"} Mbps", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    shownTest.phase == ConnectionTestPhase.COMPLETED && shownTest.kind == ConnectionTestKind.EXTREME ->
                        Text(stringResource(R.string.density_rounds_done, shownTest.completed, shownTest.total), style = MaterialTheme.typography.titleMedium)
                    else -> Text(stringResource(R.string.density_start_test), style = MaterialTheme.typography.titleMedium)
                }
                if ((shownTest.active || metrics != null) && shownTest.kind == ConnectionTestKind.NORMAL &&
                    shownTest.origin in setOf(TestOrigin.SCHEDULED, TestOrigin.SMART)) {
                    Text(stringResource(healthOriginLabel(shownTest.origin)), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        {
            HomeFeatureCard(OrbitSymbols.Assessment, stringResource(R.string.function_reports), onReports) {
                when {
                    shownReport != null -> {
                        val current = shownReport.report
                        val total = shownReport.total
                        Text(stringResource(healthGradeLabel(current.grade)), style = MaterialTheme.typography.titleMedium)
                        val availability = total.availability
                        val reliable = HomePresentation.showAvailability(current.grade, current.confidence, availability)
                        val value = healthDecimal(if (reliable) availability!! else total.coverage, if (reliable) 1 else 0)
                        val spoken = stringResource(if (reliable) R.string.rich_availability_spoken else R.string.rich_coverage_spoken, value)
                        Text(stringResource(if (reliable) R.string.density_availability else R.string.rich_coverage, value),
                            Modifier.clearAndSetSemantics { contentDescription = spoken },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    shownLoading -> Text(stringResource(R.string.density_loading), style = MaterialTheme.typography.bodyMedium)
                    else -> Text(stringResource(if (shownFailed) R.string.health_unavailable else R.string.density_open_reports),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    ), minimumWidthDp = 340f)
}

@Composable private fun HomeFeatureCard(icon: ImageVector, title: String, onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Card(onClick = hapticClick(action = onClick), interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().pressMotion(interaction, .985f).heightIn(min = 48.dp)
        .semantics(mergeDescendants = true) {}, shape = DashDesign.section,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Icon(OrbitSymbols.KeyboardArrowRight, null, Modifier.size(16.dp))
            }
            content()
        }
    }
}
