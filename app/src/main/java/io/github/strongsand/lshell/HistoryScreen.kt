package io.github.strongsand.lshell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.beacon.BeaconSampleSource
import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private enum class HistoryFilter(val label: String) {
    ALL("Tudo"), ALERTS("Alertas"), DOWNLOAD("Download"), UPLOAD("Upload"), LATENCY("Latência")
}

private enum class HistorySourceFilter(val label: String, val source: BeaconSampleSource?) {
    ALL("Todas", null), BEACON("Beacon", BeaconSampleSource.BEACON), LOCAL("Este dispositivo", BeaconSampleSource.LOCAL_PHONE)
}

private sealed interface HistoryTimelineItem {
    val timestamp: Long
    val stableKey: String

    data class Minute(val group: HistoryMinuteGroup) : HistoryTimelineItem {
        override val timestamp = group.minuteStartMillis
        override val stableKey = "minute-${group.minuteStartMillis}"
    }

    data class Event(val entry: HistoryEntry, val ordinal: Int) : HistoryTimelineItem {
        override val timestamp = entry.timestamp
        override val stableKey = "event-${entry.timestamp}-${entry.label.hashCode()}-$ordinal"
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun HistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var groups by remember { mutableStateOf<List<HistoryMinuteGroup>>(emptyList()) }
    var events by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var minuteLimit by rememberSaveable { mutableIntStateOf(INITIAL_MINUTE_LIMIT) }
    var refreshRequest by remember { mutableIntStateOf(0) }
    var queryRevision by remember { mutableIntStateOf(0) }
    var importNote by remember { mutableStateOf<String?>(null) }
    var filterName by rememberSaveable { mutableStateOf(HistoryFilter.ALL.name) }
    var sourceName by rememberSaveable { mutableStateOf(HistorySourceFilter.ALL.name) }
    var expandedMinute by rememberSaveable { mutableStateOf<Long?>(null) }
    val filter = HistoryFilter.entries.firstOrNull { it.name == filterName } ?: HistoryFilter.ALL
    val sourceFilter = HistorySourceFilter.entries.firstOrNull { it.name == sourceName }
        ?: HistorySourceFilter.ALL
    val alertsOnly = filter == HistoryFilter.ALERTS
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshRequest) {
        importNote = withContext(Dispatchers.IO) {
            var note: String? = null
            DishyGrpcClient().use { client ->
                client.fetchStatus().onSuccess { snapshot ->
                    client.fetchHistory().onSuccess { data ->
                        HistoryStore(context).use { store ->
                            val oldBoot = MonitorPreferences.historyBoot(context)
                            val candidateBoot = if (snapshot.deviceInfo.uptimeSeconds > 0L)
                                System.currentTimeMillis() / 1_000L - snapshot.deviceInfo.uptimeSeconds
                            else oldBoot.coerceAtLeast(0L)
                            val oldCounter = MonitorPreferences.historyCounter(context)
                            val sameBoot = oldCounter < data.current &&
                                kotlin.math.abs(candidateBoot - oldBoot) <= 60L
                            val boot = if (sameBoot) oldBoot else candidateBoot
                            val cursor = if (sameBoot) oldCounter else -1L
                            val count = store.importTerminalHistory(data, cursor, boot)
                            if (data.hasTelemetrySamples()) {
                                MonitorPreferences.setHistoryCursor(context, data.current - 1, boot)
                            }
                            note = if (count > 0) "$count leituras recentes recuperadas da antena" else null
                        }
                    }.onFailure {
                        note = "Histórico da antena indisponível; exibindo registros salvos."
                    }
                }.onFailure {
                    note = "Antena indisponível; exibindo registros salvos."
                }
            }
            note
        }
        queryRevision++
    }

    LaunchedEffect(minuteLimit, queryRevision, sourceFilter, alertsOnly) {
        val loaded = withContext(Dispatchers.IO) {
            HistoryStore(context).use { store ->
                val minuteGroups = store.recentMinuteGroups(minuteLimit, sourceFilter.source)
                val eventStart = if (alertsOnly) 0L
                    else minuteGroups.lastOrNull()?.minuteStartMillis ?: 0L
                minuteGroups to store.recentEvents(eventStart, minuteLimit, sourceFilter.source)
            }
        }
        groups = loaded.first
        events = loaded.second
        expandedMinute = null
    }

    val timeline = remember(groups, events, filter) {
        when (filter) {
            HistoryFilter.ALERTS -> events.mapIndexed { index, entry -> HistoryTimelineItem.Event(entry, index) }
            HistoryFilter.ALL -> (groups.map { HistoryTimelineItem.Minute(it) } +
                events.mapIndexed { index, entry -> HistoryTimelineItem.Event(entry, index) })
                .sortedByDescending { it.timestamp }
            else -> groups.map { HistoryTimelineItem.Minute(it) }
        }
    }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item("history-header") {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpressiveIcon(
                    Icons.Rounded.History,
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text("Histórico", style = MaterialTheme.typography.headlineLarge)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Leituras e alertas salvos nos últimos 30 dias.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            importNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = hapticClick { refreshRequest++ }) { Text("Atualizar histórico") }
            Spacer(Modifier.height(10.dp))
            Text("Mostrar", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                HistoryFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = hapticClick { filterName = option.name },
                        label = { Text(option.label) }
                    )
                }
            }
            Text("Origem", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                HistorySourceFilter.entries.forEach { option ->
                    FilterChip(
                        selected = sourceFilter == option,
                        onClick = hapticClick { sourceName = option.name },
                        label = { Text(option.label) }
                    )
                }
            }
            if (timeline.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Ir para", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Agora" to 0, "−15 min" to 15, "−1 h" to 60, "−3 h" to 180).forEach { (label, minutesAgo) ->
                        FilterChip(selected = false, onClick = hapticClick {
                            val wanted = System.currentTimeMillis() - minutesAgo * 60_000L
                            val target = if (minutesAgo == 0) 0 else timeline.indexOfFirst { it.timestamp <= wanted }
                                .takeIf { it >= 0 } ?: timeline.lastIndex
                            scope.launch { listState.animateScrollToItem(target + 1) }
                        }, label = { Text(label) })
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }

        if (timeline.isEmpty()) item("history-empty") {
            Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ExpressiveIcon(Icons.Rounded.History, MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(if (groups.isEmpty() && events.isEmpty())
                        "Ainda não há registros para esta origem."
                    else "Nenhum registro de ${filter.label.lowercase()} neste período.")
                }
            }
        }

        itemsIndexed(timeline, key = { _, item -> item.stableKey }, contentType = { _, item ->
            when (item) {
                is HistoryTimelineItem.Minute -> "minute"
                is HistoryTimelineItem.Event -> "event"
            }
        }) { index, item ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (index == 0 || dayKey(item.timestamp) != dayKey(timeline[index - 1].timestamp)) {
                    Text(timelineDay(item.timestamp), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = if (index == 0) 2.dp else 10.dp, start = 4.dp))
                }
                when (item) {
                    is HistoryTimelineItem.Minute -> MinuteHistoryItem(
                        group = item.group,
                        filter = filter,
                        source = sourceFilter.source,
                        expanded = expandedMinute == item.group.minuteStartMillis,
                        onToggle = {
                            expandedMinute = if (expandedMinute == item.group.minuteStartMillis)
                                null else item.group.minuteStartMillis
                        }
                    )
                    is HistoryTimelineItem.Event -> HistoryEventItem(item.entry)
                }
            }
        }

        if ((filter == HistoryFilter.ALERTS && events.size >= minuteLimit) ||
            (filter != HistoryFilter.ALERTS && groups.size >= minuteLimit)
        ) item("history-load-more") {
            Button(
                onClick = hapticClick {
                    minuteLimit = (minuteLimit + INITIAL_MINUTE_LIMIT).coerceAtMost(MAX_MINUTE_LIMIT)
                },
                enabled = minuteLimit < MAX_MINUTE_LIMIT,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Carregar minutos anteriores") }
        }
        item("history-bottom-space") { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MinuteHistoryItem(
    group: HistoryMinuteGroup,
    filter: HistoryFilter,
    source: BeaconSampleSource?,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = .82f, stiffness = 380f))
            .clickable(onClick = hapticClick(action = onToggle)),
        shape = DashDesign.section,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(minuteTime(group.minuteStartMillis), style = MaterialTheme.typography.titleMedium)
                    Text(minuteDate(group.minuteStartMillis), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SourceBadge(group.predominantSource, group.hasMixedSources)
                Icon(
                    Icons.Rounded.ExpandMore,
                    if (expanded) "Recolher minuto" else "Ver amostras do minuto",
                    Modifier.padding(start = 6.dp).size(20.dp).rotate(if (expanded) 180f else 0f)
                )
            }
            when (filter) {
                HistoryFilter.DOWNLOAD -> FocusMetric(
                    "↓", group.averageDownloadMbps, "Mbps",
                    group.minimumDownloadMbps, group.maximumDownloadMbps
                )
                HistoryFilter.UPLOAD -> FocusMetric(
                    "↑", group.averageUploadMbps, "Mbps",
                    group.minimumUploadMbps, group.maximumUploadMbps
                )
                HistoryFilter.LATENCY -> FocusMetric(
                    "Latência", group.averageLatencyMs, "ms",
                    group.minimumLatencyMs, group.maximumLatencyMs
                )
                else -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    CompactMetric("↓", group.averageDownloadMbps, "Mbps")
                    CompactMetric("↑", group.averageUploadMbps, "Mbps")
                    CompactMetric("", group.averageLatencyMs, "ms")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${group.sampleCount} ${if (group.sampleCount == 1) "amostra" else "amostras"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Perda média ${fmt(group.averageDropPercent)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(expanded) {
                MinuteSamples(group.minuteStartMillis, source, filter)
            }
        }
    }
}

@Composable
private fun MinuteSamples(minuteStart: Long, source: BeaconSampleSource?, filter: HistoryFilter) {
    val context = LocalContext.current
    var samples by remember(minuteStart, source) { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    LaunchedEffect(minuteStart, source) {
        samples = withContext(Dispatchers.IO) {
            HistoryStore(context).use { it.entriesInMinute(minuteStart, source) }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 3.dp))
        samples.forEach { sample ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(exactTime(sample.timestamp), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(.55f))
                Text(sampleMetric(sample, filter), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1.45f))
                SourceBadge(sample.source, mixed = false, compact = true)
            }
        }
    }
}

@Composable
private fun HistoryEventItem(entry: HistoryEntry) {
    Card(
        Modifier.fillMaxWidth(),
        shape = DashDesign.hero,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(entry.label, style = MaterialTheme.typography.titleMedium)
            }
            SourceBadge(entry.source, mixed = false)
        }
    }
}

@Composable
private fun SourceBadge(source: BeaconSampleSource, mixed: Boolean, compact: Boolean = false) {
    val beacon = source == BeaconSampleSource.BEACON
    val label = when {
        mixed -> "Beacon + dispositivo"
        beacon -> "Via Beacon"
        else -> "Este dispositivo"
    }
    Surface(
        shape = DashDesign.pill,
        color = if (beacon || mixed) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (beacon || mixed) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            Modifier.padding(horizontal = if (compact) 7.dp else 9.dp, vertical = if (compact) 4.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                if (beacon || mixed) Icons.Rounded.Memory else Icons.Rounded.PhoneAndroid,
                null,
                Modifier.size(if (compact) 13.dp else 15.dp)
            )
            Text(label, style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CompactMetric(label: String, value: Float?, unit: String) {
    Text("$label${fmt(value)} $unit", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun FocusMetric(label: String, average: Float?, unit: String, minimum: Float?, maximum: Float?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(fmt(average), style = MaterialTheme.typography.titleLarge)
        Text(unit, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        if (minimum != null && maximum != null) {
            Text("${fmt(minimum)}–${fmt(maximum)}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun sampleMetric(entry: HistoryEntry, filter: HistoryFilter): String = when (filter) {
    HistoryFilter.DOWNLOAD -> "↓ ${fmt(entry.downloadMbps)} Mbps"
    HistoryFilter.UPLOAD -> "↑ ${fmt(entry.uploadMbps)} Mbps"
    HistoryFilter.LATENCY -> "${fmt(entry.latencyMs)} ms · ${fmt(entry.dropPercent)}%"
    else -> "↓ ${fmt(entry.downloadMbps)} · ↑ ${fmt(entry.uploadMbps)} · ${fmt(entry.latencyMs)} ms"
}

private fun DishyGrpcClient.HistoryReading.hasTelemetrySamples(): Boolean =
    current > 0L && minOf(downloadMbps.size, uploadMbps.size, latencyMs.size, dropPercent.size) > 0

private fun minuteTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun minuteDate(timestamp: Long): String =
    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(timestamp))

private fun exactTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

private fun dayKey(timestamp: Long): String =
    SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(timestamp))

private fun timelineDay(timestamp: Long): String =
    SimpleDateFormat("EEEE, d 'de' MMMM", Locale.getDefault()).format(Date(timestamp))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

private fun fmt(value: Float?): String = value?.takeIf { it.isFinite() }
    ?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"

private const val INITIAL_MINUTE_LIMIT = 180
private const val MAX_MINUTE_LIMIT = 10000
