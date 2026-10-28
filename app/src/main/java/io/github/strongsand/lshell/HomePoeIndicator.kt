package io.github.strongsand.lshell

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.core.model.DishySnapshot
import io.github.strongsand.lshell.core.network.grpc.visibleInputVolts
import java.util.Locale

/** Reuses the existing shared router lease; never feeds graphs or history. */
@Composable
internal fun rememberPoeInputVolts(snapshot: DishySnapshot): Float? {
    if (!CandidatePublicationPolicy.POE_INPUT) return null
    val telemetry by rememberRouterTelemetry(includeDetails = false)
    // Existing Dishy updates reevaluate age; no UI/network polling timer is added.
    val available = snapshot.isOnline && !snapshot.isFromCache &&
        System.currentTimeMillis() - snapshot.timestamp in 0..15_000L
    return telemetry.info?.poeInput.visibleInputVolts(snapshot.deviceInfo.id, available,
        SystemClock.elapsedRealtime())
}

@Composable
internal fun PoeVoltageCard(snapshot: DishySnapshot) {
    val volts = rememberPoeInputVolts(snapshot) ?: return
    val number = String.format(Locale.getDefault(), "%.1f", volts)
    val spoken = stringResource(R.string.poe_input_accessibility, number)
    Surface(Modifier.fillMaxWidth().padding(top = 12.dp), shape = DashDesign.section,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(OrbitSymbols.Bolt, null, Modifier.size(20.dp))
                Text(stringResource(R.string.poe_energy_label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(R.string.poe_input_value, number), Modifier.clearAndSetSemantics { contentDescription = spoken },
                style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.poe_energy_source), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
