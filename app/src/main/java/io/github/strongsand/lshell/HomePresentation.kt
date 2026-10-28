package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.beacon.BeaconState

/** Presentation only: no new thresholds for telemetry or report grading. */
internal object HomePresentation {
    fun showAvailability(grade: HealthGrade, confidence: HealthConfidence, availability: Double?) =
        grade != HealthGrade.INSUFFICIENT && confidence != HealthConfidence.LOW && availability?.isFinite() == true
    fun reading(available: Boolean, raw: Float, formatted: String, unit: String) =
        if (available && raw.isFinite()) formatted else "— $unit"
    fun stacked(widthDp: Float, fontScale: Float, minimumWidthDp: Float = 320f) =
        widthDp / fontScale.coerceAtLeast(1f) < minimumWidthDp
}

/** Scoped home/device tones follow Monet, including light/dark/AMOLED palettes. */
@Composable internal fun homeStatusColors(tone: HealthTone): HealthColors {
    val colors = MaterialTheme.colorScheme
    return when (tone) {
        HealthTone.GOOD -> HealthColors(colors.primaryContainer, colors.onPrimaryContainer)
        HealthTone.CAUTION -> HealthColors(colors.secondaryContainer, colors.onSecondaryContainer)
        HealthTone.DANGER -> HealthColors(colors.errorContainer, colors.onErrorContainer)
        HealthTone.UNKNOWN -> HealthColors(colors.surfaceContainerHighest, colors.onSurfaceVariant)
    }
}

@Composable internal fun HomeStatusPill(label: String, tone: HealthTone) {
    val colors = homeStatusColors(tone)
    Surface(shape = DashDesign.pill, color = colors.container, contentColor = colors.foreground,
        border = BorderStroke(1.dp, colors.foreground.copy(alpha = .16f))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(when (tone) { HealthTone.GOOD -> OrbitSymbols.Check; HealthTone.UNKNOWN -> OrbitSymbols.Info; else -> OrbitSymbols.Warning },
                null, Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun HomeMetricValue(number: String, unit: String, color: Color, compact: Boolean = false,
    sparklineSpace: HomeSparklineSpace? = null) {
    val spoken = homeMetricSpoken(number, unit)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(0.dp),
        itemVerticalAlignment = Alignment.Bottom, modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }) {
        SparklineText(number, sparklineSpace, "number", style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge, color = color)
        if (unit.isNotBlank()) SparklineText(unit, sparklineSpace, "unit", Modifier.padding(bottom = 3.dp), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** Pending counters supplement a status; they never mask an offline/storage/Dishy warning. */
internal fun beaconHomeLabel(state: BeaconState): Int = when (state) {
    is BeaconState.Syncing -> R.string.beacon_online_syncing
    is BeaconState.DishyUnavailable -> R.string.beacon_online_dishy_unavailable
    is BeaconState.Recording -> R.string.rich_beacon_recording
    is BeaconState.Connected -> R.string.beacon_online_ready
    is BeaconState.StorageWarning -> if (state.status.storageHealthy) R.string.beacon_storage_warning else R.string.beacon_storage_error
    is BeaconState.BeaconOffline -> R.string.beacon_offline
    is BeaconState.DiscoveringLan -> R.string.beacon_searching
    is BeaconState.Error -> R.string.editorial_beacon_issue
    else -> R.string.beacon_pending
}

@Composable internal fun homeMetricSpoken(number: String, unit: String): String {
    val spokenUnit = when (unit) {
        "Mbps", "Mb/s" -> stringResource(R.string.rich_unit_mbps)
        "ms" -> stringResource(R.string.rich_unit_ms)
        "W" -> stringResource(R.string.rich_unit_watts)
        else -> unit
    }
    return if (number in setOf("—", "--")) stringResource(R.string.beacon_unknown) else "$number $spokenUnit"
}
