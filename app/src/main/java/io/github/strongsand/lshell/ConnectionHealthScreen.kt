package io.github.strongsand.lshell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun ConnectionHealthScreen(modifier: Modifier = Modifier, initialDailyEnd: Long? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable(initialDailyEnd) { mutableIntStateOf(if (initialDailyEnd != null) 3 else 0) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<HealthReport?>(null) }
    var history by remember { mutableStateOf<List<HealthReportListing>>(emptyList()) }
    var historyLimit by rememberSaveable { mutableIntStateOf(50) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var automatic by remember { mutableStateOf(ConnectionHealthSchedule.enabled(context)) }
    var aboutReports by rememberSaveable { mutableStateOf(false) }
    val revision by ConnectionHealthStore.revision.collectAsState()
    val shownTab = tab
    val shownKey = selectedKey
    val displayedReport = report?.takeIf { current ->
        if (shownKey != null) current.period.key == shownKey
        else shownTab < 2 && current.period.kind == (if (shownTab == 0) HealthPeriodKind.WEEK else HealthPeriodKind.MONTH)
    }
    // Lazy list content can be evaluated after a state write, before recomposition.
    // Capture the report and its aggregate together; never mix live state with an old total.
    val preparedReport = remember(displayedReport) { PreparedHealthReport.from(displayedReport)?.let(::HealthReportUi) }
    // Cache presentation summaries per report; never query raw history during composition/drawing.
    LaunchedEffect(tab, selectedKey, retry, revision, historyLimit) {
        if (tab == 3) return@LaunchedEffect
        loading = true; error = false
        try {
            history = withContext(Dispatchers.IO) { ConnectionHealthStore.list(context, historyLimit) }
            report = readDisplayedHealthReport(shownKey,
                saved = { key -> withContext(Dispatchers.IO) { ConnectionHealthStore.get(context, key) } },
                current = { if (shownTab < 2) ConnectionHealthStore.current(context,
                    if (shownTab == 0) HealthPeriodKind.WEEK else HealthPeriodKind.MONTH) else null })
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
        finally { loading = false }
    }
    BackHandler(selectedKey != null) { selectedKey = null }
    // DailyReportsScreen's detail handler takes precedence; its list returns here.
    BackHandler(tab == 3) { tab = 0 }
    if (tab == 3) {
        DailyReportsScreen(modifier, initialDailyEnd) { tab = 0 }
        return
    }
    val shownHistory = history
    val shownLimit = historyLimit
    val isLoading = loading
    val hasError = error
    val isAutomatic = automatic
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("heading") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = hapticClick { if (selectedKey != null) selectedKey = null else onBack() }) {
                    Icon(OrbitSymbols.ArrowBack, stringResource(R.string.health_back))
                }
                Text(stringResource(R.string.function_reports), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = hapticClick { aboutReports = true }, enabled = preparedReport != null) {
                    Icon(OrbitSymbols.Info, stringResource(R.string.reports_about))
                }
                IconButton(onClick = hapticClick { loading = true; retry++ }, enabled = !isLoading) { Icon(OrbitSymbols.Refresh, stringResource(R.string.health_refresh)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.health_week, R.string.health_month, R.string.health_history, R.string.health_daily).forEachIndexed { index, label ->
                    FilterChip(shownTab == index,
                        onClick = if (shownTab == index && shownKey == null) ({}) else hapticClick {
                            tab = index; selectedKey = null; loading = index != 3
                        }, modifier = Modifier.heightIn(min = 48.dp), label = { Text(stringResource(label)) })
                }
            }
        }
        item("loading") {
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (isLoading) LinearProgressIndicator(Modifier.fillMaxSize())
            }
        }
        if (hasError) item("error") {
            HealthCard(R.string.health_unavailable) {
                Text(stringResource(R.string.health_load_error))
                TextButton(enabled = !isLoading, onClick = hapticClick { loading = true; retry++ }) { Text(stringResource(R.string.health_retry)) }
            }
        }
        if (shownTab == 2 && shownKey == null) {
            if (shownHistory.isEmpty() && !isLoading && !hasError) item("empty") { HealthCard(R.string.health_history) { Text(stringResource(R.string.health_history_empty)) } }
            items(shownHistory, key = { it.key }) { saved ->
                Card(onClick = hapticClick { selectedKey = saved.key; loading = true }, shape = DashDesign.section, modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(healthPeriodLabel(saved.period), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(healthGradeLabel(saved.grade)), style = MaterialTheme.typography.titleMedium)
                        saved.stability?.let { Text(stringResource(R.string.health_ui_stability_trend, stringResource(healthTrendLabel(it))), color = MaterialTheme.colorScheme.primary) }
                        saved.coverage?.let { Text(stringResource(R.string.health_ui_coverage_chip, healthDecimal(it)), style = MaterialTheme.typography.labelLarge) }
                        Text(stringResource(if (saved.period.kind == HealthPeriodKind.WEEK) R.string.health_week else R.string.health_month), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (shownHistory.size == shownLimit) item("more") { TextButton(enabled = !isLoading, onClick = hapticClick { loading = true; historyLimit += 50 }) { Text(stringResource(R.string.health_more)) } }
        } else preparedReport?.let { ui ->
            item("hero:${ui.report.period.key}") { HealthHero(ui) }
            if (ui.total.observedMs > 0 || ui.total.tests.isNotEmpty()) item("overview") { HealthOverview(ui) }
            item("comparison") { HealthComparisonCard(ui) }
            if (ui.insights.isNotEmpty() || ui.bestLatencyWindow != null) item("insights") { HealthInsights(ui) }
            if (ui.hasSpeed) item("speed") { HealthSpeed(ui) }
            if (ui.hasQuality) item("quality") { HealthQuality(ui) }
            if (ui.hasEnergy) item("energy") { HealthEnergy(ui) }
            item("confidence") { HealthConfidenceCard(ui) }
        }
        item("automatic") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.health_automatic), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(if (isAutomatic) R.string.health_ui_auto_on else R.string.health_ui_auto_off), style = MaterialTheme.typography.bodySmall)
                    }
                    val automaticLabel = stringResource(R.string.health_automatic)
                    Switch(isAutomatic, onCheckedChange = hapticToggle { automatic = it; ConnectionHealthSchedule.setEnabled(context, it) },
                        modifier = Modifier.semantics { contentDescription = automaticLabel })
                }
                var about by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = hapticClick { about = !about }) { Text(stringResource(R.string.health_ui_about_automation)) }
                if (about) Text(stringResource(R.string.health_automatic_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (aboutReports) preparedReport?.let { ui ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { aboutReports = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item("heading") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = hapticClick { aboutReports = false }) { Icon(OrbitSymbols.ArrowBack, stringResource(R.string.health_back)) }
                            Text(stringResource(R.string.reports_about), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    item("method") { HealthMethodology(ui) }
                }
            }
        }
    }
}
