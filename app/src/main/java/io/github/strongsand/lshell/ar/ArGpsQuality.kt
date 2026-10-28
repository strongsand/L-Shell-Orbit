package io.github.strongsand.lshell.ar

import io.github.strongsand.lshell.OrbitSymbols

import android.location.Location
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.LocalOrbitMotion
import io.github.strongsand.lshell.R
import io.github.strongsand.lshell.hapticClick
import kotlinx.coroutines.delay

/** Horizontal fix accuracy, not RF signal strength or satellite count. */
internal enum class ArGpsQuality(val bars: Int, val label: Int) {
    NO_FIX(0, R.string.ar_gps_no_fix),
    STALE(0, R.string.ar_gps_stale),
    UNKNOWN_ACCURACY(0, R.string.ar_gps_unknown_accuracy),
    WEAK(1, R.string.ar_gps_weak),
    FAIR(2, R.string.ar_gps_fair),
    GOOD(3, R.string.ar_gps_good),
    EXCELLENT(4, R.string.ar_gps_excellent)
}

private const val GPS_FRESHNESS_MS = 30_000L

internal fun classifyArGpsQuality(accuracyMeters: Float?, ageMs: Long, available: Boolean): ArGpsQuality = when {
    !available || ageMs < 0L -> ArGpsQuality.NO_FIX
    ageMs >= GPS_FRESHNESS_MS -> ArGpsQuality.STALE
    accuracyMeters == null || !accuracyMeters.isFinite() || accuracyMeters < 0f -> ArGpsQuality.UNKNOWN_ACCURACY
    accuracyMeters <= 5f -> ArGpsQuality.EXCELLENT
    accuracyMeters <= 10f -> ArGpsQuality.GOOD
    accuracyMeters <= 25f -> ArGpsQuality.FAIR
    else -> ArGpsQuality.WEAK
}

/** One expiry per fix; no new provider, polling loop or requests for GNSS measurements. */
@Composable
internal fun rememberArGpsQuality(location: Location?, providerAvailable: Boolean): ArGpsQuality {
    val fix = location?.takeIf {
        it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 && it.elapsedRealtimeNanos > 0L
    }
    var nowNanos by remember(location, providerAvailable) { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    val ageMs = fix?.let {
        if (nowNanos >= it.elapsedRealtimeNanos) (nowNanos - it.elapsedRealtimeNanos) / 1_000_000L else -1L
    } ?: -1L
    LaunchedEffect(location, providerAvailable) {
        if (providerAvailable && fix != null && ageMs in 0L until GPS_FRESHNESS_MS) {
            delay(GPS_FRESHNESS_MS - ageMs + 1L)
            nowNanos = SystemClock.elapsedRealtimeNanos()
        }
    }
    return classifyArGpsQuality(fix?.takeIf { it.hasAccuracy() }?.accuracy, ageMs,
        providerAvailable && fix != null)
}

@Composable
internal fun ArGpsQualityChip(quality: ArGpsQuality, onClick: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    val label = stringResource(quality.label)
    val description = stringResource(R.string.ar_gps_quality_accessibility, label)
    val level by animateFloatAsState(quality.bars.toFloat(),
        if (LocalOrbitMotion.current) spring(dampingRatio = 1f, stiffness = 400f) else snap(),
        label = "GPS accuracy bars")
    val container = if (quality.bars > 0) palette.secondaryContainer else palette.surfaceContainerHigh
    val foreground = if (quality.bars > 0) palette.onSecondaryContainer else palette.onSurfaceVariant
    Surface(onClick = hapticClick(action = onClick),
        modifier = Modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) {
            contentDescription = description
        },
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 12.dp),
        color = container, contentColor = foreground) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(OrbitSymbols.MyLocation, null, Modifier.size(18.dp))
            Canvas(Modifier.size(width = 26.dp, height = 20.dp)) {
                val barWidth = 4.dp.toPx()
                val gap = 3.dp.toPx()
                for (index in 0..3) {
                    val height = (8 + index * 4).dp.toPx()
                    val x = index * (barWidth + gap)
                    val y = size.height - height
                    val corner = CornerRadius(2.dp.toPx())
                    drawRoundRect(foreground.copy(alpha = 0.4f), Offset(x, y), Size(barWidth, height),
                        cornerRadius = corner, style = Stroke(1.dp.toPx()))
                    val filled = (level - index).coerceIn(0f, 1f)
                    if (filled > 0f) drawRoundRect(foreground, Offset(x, size.height - height * filled),
                        Size(barWidth, height * filled), cornerRadius = corner)
                }
            }
            Text(label, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge)
        }
    }
}
