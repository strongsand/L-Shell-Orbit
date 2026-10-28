package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.beacon.BeaconSampleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.text.DateFormat
import java.util.Date

internal enum class OccurrenceCategory { ALL, CONNECTION, QUALITY, TESTS, HARDWARE, BEACON, OTHER }
internal data class HistoryOccurrence(val key: String, val time: Long, val title: String,
    val detail: String = "", val category: OccurrenceCategory, val source: BeaconSampleSource? = null,
    val test: RecordedTest? = null)

/** Presentation of existing events only: no new thresholds, inferred durations or writes. */
internal object OccurrenceHistory {
    fun category(title: String): OccurrenceCategory = HistoryEventCodec.category(title)
    fun compose(moments: List<ReportMoment>, events: List<HistoryEntry>, tests: List<RecordedTest>): List<HistoryOccurrence> {
        val consolidated = moments.filterNot {
            val type = HistoryEventCodec.parse(it.title).type
            type == CanonicalHistoryEventType.DOWNLOAD_PEAK ||
                (type == CanonicalHistoryEventType.EXTREME_TEST && tests.any { test -> it.timestamp in test.start..test.end })
        }.map { HistoryOccurrence("moment:${it.timestamp}:${it.title}:${it.detail}",
            it.timestamp, it.title, it.detail, category(it.title)) }
        val raw = events.filter { it.kind == "event" }.filterNot { event ->
            val parsed = HistoryEventCodec.parse(event.label)
            (parsed.type == CanonicalHistoryEventType.EXTREME_TEST && tests.any { event.timestamp in it.start..it.end }) ||
                consolidated.any { moment ->
                    if (moment.time != event.timestamp) false
                    else {
                        val canonical = HistoryEventCodec.parse(moment.title)
                        canonical.semanticKey == parsed.semanticKey ||
                            (parsed.type == CanonicalHistoryEventType.ALERT_RAISED &&
                                canonical.type == CanonicalHistoryEventType.ALERT && canonical.alertKey == parsed.alertKey) ||
                            // Unknown alert names are retained verbatim in consolidated moments.
                            (parsed.type == CanonicalHistoryEventType.ALERT_RAISED && moment.title == parsed.payload)
                    }
                }
        }.map { HistoryOccurrence("event:${it.timestamp}:${it.label}", it.timestamp, it.label,
            category = category(it.label), source = it.source) }
        return (consolidated + raw + tests.map { HistoryOccurrence("test:${it.id}",
            it.end.takeIf { end -> end > 0 } ?: it.start, "", category = OccurrenceCategory.TESTS, test = it) })
            .distinctBy { Triple(it.time, HistoryEventCodec.parse(it.title).semanticKey, it.test?.id ?: it.detail) }
            .sortedByDescending { it.time }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun OccurrenceHistoryScreen(modifier: Modifier, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var categoryName by rememberSaveable { mutableStateOf(OccurrenceCategory.ALL.name) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var occurrences by remember { mutableStateOf(emptyList<HistoryOccurrence>()) }
    var loadedDate by remember { mutableStateOf<LocalDate?>(null) }
    val category = OccurrenceCategory.valueOf(categoryName)
    val date = LocalDate.now().plusDays(offset.toLong())
    val request = date to revision
    var loadStatus by remember { mutableStateOf(UiLoadStatus(request)) }
    val phase = loadStatus.phaseFor(request)
    val loading = phase == UiLoadPhase.LOADING
    val failed = phase == UiLoadPhase.ERROR
    LaunchedEffect(date, revision) {
        loadStatus = UiLoadStatus(request)
        val result = readUiList { withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
            val report = DailyReportGenerator.generate(context, start, end)
            HistoryStore(context).use { store -> OccurrenceHistory.compose(report.timeline,
                store.eventsBetween(start, end), TestHistory.between(store, start, end)) }
        } }
        result.fold(onSuccess = {
            occurrences = it; loadedDate = date; loadStatus = UiLoadStatus(request).completed(it.isEmpty())
        }, onFailure = { loadStatus = UiLoadStatus(request).failed() })
    }
    val currentOccurrences = if (loadedDate == date) occurrences else emptyList()
    val shown = remember(currentOccurrences, category) { currentOccurrences.filter { category == OccurrenceCategory.ALL || it.category == category } }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item("header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) IconButton(onClick = hapticClick(action = onBack)) {
                        Icon(OrbitSymbols.ArrowBack, stringResource(R.string.antenna_back))
                    }
                    Text(stringResource(R.string.function_history), Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = hapticClick { filterOpen = true }) { Icon(OrbitSymbols.FilterList, stringResource(R.string.antenna_filters)) }
                    IconButton(enabled = !loading, onClick = hapticClick { revision++ }) { Icon(OrbitSymbols.Refresh, stringResource(R.string.traffic_refresh)) }
                }
                Text(stringResource(R.string.product_history_subtitle), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = hapticClick { offset-- }) { Icon(OrbitSymbols.ChevronLeft, stringResource(R.string.product_previous_day)) }
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(enabled = offset < 0, onClick = hapticClick { offset++ }) {
                        Icon(OrbitSymbols.ChevronRight, stringResource(R.string.product_next_day))
                    }
                }
                if (category != OccurrenceCategory.ALL) Text(stringResource(category.label()), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (loading) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (failed) item("error") {
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.general_history_error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = hapticClick { revision++ }) { Text(stringResource(R.string.health_retry)) }
            }
        }
        if (!loading && !failed && shown.isEmpty()) item("empty") {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val filtered = category != OccurrenceCategory.ALL && currentOccurrences.isNotEmpty()
                Text(stringResource(if (filtered) R.string.general_history_filter_empty else R.string.product_no_events), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (filtered) R.string.general_history_filter_help else R.string.ux_history_empty_detail), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (filtered) TextButton(onClick = hapticClick { categoryName = OccurrenceCategory.ALL.name }) {
                    Text(stringResource(R.string.general_clear_filter))
                }
            }
        }
        items(shown, key = { it.key }, contentType = { "event" }) { OccurrenceRow(it) }
        item("bottom") { Spacer(Modifier.height(16.dp)) }
    }
    if (filterOpen) ModalBottomSheet(onDismissRequest = { filterOpen = false }) {
        FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OccurrenceCategory.entries.forEach { option ->
                OrbitFilterChip(category == option, hapticClick { categoryName = option.name; filterOpen = false },
                    label = { Text(stringResource(option.label())) })
            }
        }
    }
}

internal fun OccurrenceCategory.label(): Int = when (this) {
    OccurrenceCategory.ALL -> R.string.editorial_all
    OccurrenceCategory.CONNECTION -> R.string.product_connection
    OccurrenceCategory.QUALITY -> R.string.product_quality
    OccurrenceCategory.TESTS -> R.string.product_tests
    OccurrenceCategory.HARDWARE -> R.string.product_hardware
    OccurrenceCategory.BEACON -> R.string.traffic_beacon
    OccurrenceCategory.OTHER -> R.string.product_other
}

@Composable
internal fun OccurrenceRow(item: HistoryOccurrence, initiallyExpanded: Boolean = false) {
    val test = item.test
    val title = if (test != null) stringResource(if (test.origin == TestOrigin.EXTREME) R.string.function_extreme else R.string.product_normal_test) else telemetryLabel(item.title)
    val detail = if (test == null) telemetryLabel(item.detail) else buildString {
        append(stringResource(when (test.outcome) {
            "COMPLETED" -> R.string.product_completed
            "FAILED" -> R.string.product_failed
            else -> R.string.product_interrupted
        }))
        append(" · "); append(trafficValue(test.latencies.takeIf { it.isNotEmpty() }?.average(), "ms"))
        if (test.end >= test.start) { append(" · "); append(trafficShortDuration(test.end - test.start)) }
    }
    TemporalEventRow(item.time, title, detail, initiallyExpanded = initiallyExpanded, icon = if (test != null) OrbitSymbols.Speed else timelineIcon(item.title),
        origin = if (test == null && item.source == null) null else {
            {
                if (test != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DirectionalMetric(true, trafficValue(test.downloads.takeIf { it.isNotEmpty() }?.average(), "Mbps"), style = MaterialTheme.typography.bodySmall)
                    DirectionalMetric(false, trafficValue(test.uploads.takeIf { it.isNotEmpty() }?.average(), "Mbps"), style = MaterialTheme.typography.bodySmall)
                }
                item.source?.let { source -> Text(stringResource(if (source == BeaconSampleSource.BEACON) R.string.traffic_beacon else R.string.traffic_local),
                    style = MaterialTheme.typography.labelSmall) }
            }
        })
}
