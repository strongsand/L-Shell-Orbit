package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Recording controls only; acquisition and analysis remain in the existing session pipeline. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrafficRecordingControls(active: TrafficSession?, activity: TrafficSessionActivity,
    now: Long, busy: Boolean, canRecord: Boolean, recordingsShown: Boolean,
    canOpenActive: Boolean, onRecord: () -> Unit, onRecordings: () -> Unit,
    onOpenActive: () -> Unit, onFinish: () -> Unit, onRetry: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    val recovering = active != null && activity is TrafficSessionActivity.ErrorRecoverable &&
        activity.session?.id == active.id
    Surface(Modifier.fillMaxWidth(),shape = DashDesign.section,
        color = amoledCardColor(if (active != null) palette.primaryContainer else palette.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp),verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (active != null) {
                Text(stringResource(R.string.traffic_session_actions),Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(OrbitSymbols.FiberManualRecord,null,Modifier.size(18.dp),
                        tint = if (recovering) palette.error else palette.primary)
                    Text(stringResource(when {
                        recovering -> R.string.traffic_recording_interrupted
                        activity is TrafficSessionActivity.Starting -> R.string.traffic_recording_preparing
                        activity is TrafficSessionActivity.Stopping -> R.string.traffic_recording_finishing
                        else -> R.string.traffic_session_active
                    }),style = MaterialTheme.typography.labelLarge,modifier = Modifier.weight(1f))
                    Text(trafficShortDuration(now - active.startedAt),style = MaterialTheme.typography.labelLarge)
                }
                Text(active.name,style = MaterialTheme.typography.titleMedium,maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (recovering) R.string.session_recover else R.string.session_background),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (recovering) palette.error else amoledCardContent(palette.onPrimaryContainer))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OrbitActionButton(onClick = if (active == null) onRecord else onFinish,
                    enabled = !busy && (active != null || canRecord),modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(if (active == null) OrbitSymbols.FiberManualRecord else OrbitSymbols.Stop,
                        null,Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (active == null) R.string.traffic_start else R.string.traffic_finish))
                }
                OrbitActionButton(onClick = onRecordings, style = OrbitActionStyle.SECONDARY,
                    selected = recordingsShown) {
                    Icon(if (recordingsShown) OrbitSymbols.Check else OrbitSymbols.List,null,Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.traffic_sessions))
                }
            }
            if (recovering || canOpenActive) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recovering) TextButton(onClick = onRetry,enabled = !busy) {
                    Text(stringResource(R.string.session_retry))
                }
                if (canOpenActive) TextButton(onClick = onOpenActive,enabled = !busy) {
                    Text(stringResource(R.string.traffic_recording_open))
                }
            }
        }
    }
}
