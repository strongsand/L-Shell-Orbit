package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.beacon.*
import java.text.DateFormat
import java.util.Date

@Composable
fun BeaconRecentEvents(events: List<BeaconUiEvent>) {
    if (events.isEmpty()) return
    Surface(shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading(stringResource(R.string.beacon_recent_events), stringResource(R.string.beacon_events_session))
            events.takeLast(6).asReversed().forEach { event ->
                val text = stringResource(when (event.kind) {
                    BeaconUiEventKind.ONLINE -> R.string.beacon_event_online
                    BeaconUiEventKind.SYNC_COMPLETE -> R.string.beacon_event_sync
                    BeaconUiEventKind.ACK -> R.string.beacon_event_ack
                    BeaconUiEventKind.ERROR -> R.string.beacon_event_error
                    BeaconUiEventKind.NEW_COLLECTION -> R.string.beacon_event_collection
                    BeaconUiEventKind.STORAGE_RECOVERED -> R.string.beacon_event_recovered
                }) + (event.count?.let { " · $it" } ?: "")
                InfoRow(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.timestamp)), text)
            }
        }
    }
}
