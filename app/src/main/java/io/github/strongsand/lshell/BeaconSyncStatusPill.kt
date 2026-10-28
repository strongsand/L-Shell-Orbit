package io.github.strongsand.lshell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.beacon.BeaconErrorCode
import io.github.strongsand.lshell.beacon.BeaconState
import io.github.strongsand.lshell.beacon.BeaconUiEvent
import io.github.strongsand.lshell.beacon.BeaconUiEventKind
import io.github.strongsand.lshell.beacon.runtimeStatusOrNull

internal enum class BeaconSyncPillState { SYNCING, SYNCED, ERROR }

/** Presentation from existing evidence only; a page ACK timestamp is not sync completion. */
internal fun beaconSyncPillState(state: BeaconState, events: List<BeaconUiEvent>): BeaconSyncPillState? {
    if (state is BeaconState.Syncing) return BeaconSyncPillState.SYNCING
    if (state is BeaconState.Error && state.code in setOf(BeaconErrorCode.SYNC_FAILED,
            BeaconErrorCode.HISTORY_TIME_UNAVAILABLE)) return BeaconSyncPillState.ERROR
    val runtime = state.runtimeStatusOrNull() ?: return null
    if ((runtime.pendingRecords ?: 0L) > 0L) return null
    val outcome = events.lastOrNull { it.kind == BeaconUiEventKind.SYNC_COMPLETE || it.kind == BeaconUiEventKind.ERROR }
    return BeaconSyncPillState.SYNCED.takeIf { outcome?.kind == BeaconUiEventKind.SYNC_COMPLETE }
}

/** In-app status: no new system channel, permission, service or background sync observer. */
@Composable
internal fun BeaconSyncStatusPill(status: BeaconSyncPillState?, modifier: Modifier = Modifier) {
    if (status == null) return
    val label = stringResource(when (status) {
        BeaconSyncPillState.SYNCING -> R.string.beacon_pill_syncing
        BeaconSyncPillState.SYNCED -> R.string.beacon_pill_synced
        BeaconSyncPillState.ERROR -> R.string.beacon_pill_sync_error
    })
    val spoken = if (status == BeaconSyncPillState.SYNCED)
        stringResource(R.string.beacon_pill_synced_accessible) else label
    val palette = MaterialTheme.colorScheme
    val container = when (status) {
        BeaconSyncPillState.SYNCING -> palette.secondaryContainer
        BeaconSyncPillState.SYNCED -> palette.tertiaryContainer
        BeaconSyncPillState.ERROR -> palette.errorContainer
    }
    val foreground = when (status) {
        BeaconSyncPillState.SYNCING -> palette.onSecondaryContainer
        BeaconSyncPillState.SYNCED -> palette.onTertiaryContainer
        BeaconSyncPillState.ERROR -> palette.onErrorContainer
    }
    Surface(modifier.clearAndSetSemantics {
        contentDescription = spoken
        liveRegion = LiveRegionMode.Polite
    }, shape = DashDesign.pill, color = container, contentColor = foreground) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(OrbitSymbols.Memory, null, Modifier.size(18.dp))
            Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge)
            if (status == BeaconSyncPillState.SYNCING)
                CircularProgressIndicator(Modifier.size(14.dp), color = foreground, strokeWidth = 2.dp)
            else Icon(if (status == BeaconSyncPillState.SYNCED) OrbitSymbols.CheckCircle else OrbitSymbols.WarningAmber,
                null, Modifier.size(16.dp))
        }
    }
}
