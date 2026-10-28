package io.github.strongsand.lshell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import io.github.strongsand.lshell.core.model.DishState
import io.github.strongsand.lshell.core.model.DishySnapshot
import io.github.strongsand.lshell.core.data.LivePowerReading
import java.text.NumberFormat
import java.util.Locale

internal enum class CurrentMetricState { AVAILABLE, WAITING, UNAVAILABLE, DISCONNECTED }

internal data class CurrentMetricReading(val state: CurrentMetricState, val value: Float? = null)

/** Live status/recent runtime power only, independent of chart period and stored history. */
internal fun currentMetricReading(kind: MetricDetailKind, snapshot: DishySnapshot, now: Long,
    powerSample: LivePowerReading? = null): CurrentMetricReading? {
    val raw = when (kind) {
        MetricDetailKind.DOWNLOAD -> snapshot.throughput.downlinkBps
        MetricDetailKind.UPLOAD -> snapshot.throughput.uplinkBps
        MetricDetailKind.LATENCY -> snapshot.latency.popPingLatencyMs
        MetricDetailKind.SIGNAL -> snapshot.latency.popPingDropRate * 100f
        MetricDetailKind.POWER -> powerSample?.powerWatts ?: Float.NaN
    }
    if (!snapshot.isOnline || snapshot.isFromCache) return CurrentMetricReading(
        if (!snapshot.isFromCache && snapshot.state == DishState.UNKNOWN) CurrentMetricState.WAITING
        else CurrentMetricState.DISCONNECTED
    )
    if (now - snapshot.timestamp !in 0L..10_000L || !raw.isFinite() ||
        (if (kind == MetricDetailKind.LATENCY) raw <= 0f else raw < 0f) ||
        (kind == MetricDetailKind.SIGNAL && raw > 100f) ||
        (kind == MetricDetailKind.POWER && (powerSample == null || now - powerSample.timestamp !in 0L..15_000L)))
        return CurrentMetricReading(CurrentMetricState.UNAVAILABLE)
    return CurrentMetricReading(CurrentMetricState.AVAILABLE,
        if (kind == MetricDetailKind.DOWNLOAD || kind == MetricDetailKind.UPLOAD) raw / 1_000_000f else raw)
}

internal fun formatCurrentMbps(value: Float, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    isGroupingUsed = false
    minimumFractionDigits = if (value == 0f) 1 else 0
    maximumFractionDigits = if (value >= 0.01f) 2 else 6
}.format(value)

internal fun formatCurrentLatency(value: Float, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    isGroupingUsed = false
    maximumFractionDigits = 0
}.format(value)

/** Animate emphasis, never interpolate a measurement or display an invented value. */
@Composable
internal fun MetricDetailValueText(value: String, style: TextStyle, modifier: Modifier = Modifier,
    color: Color = Color.Unspecified) {
    val motion = LocalOrbitMotion.current
    val emphasis = remember { Animatable(1f) }
    LaunchedEffect(value, motion) {
        if (motion) {
            emphasis.snapTo(.86f)
            emphasis.animateTo(1f, OrbitMotion.spec(OrbitMotionRole.STATE, true))
        } else emphasis.snapTo(1f)
    }
    Text(value, modifier.graphicsLayer { alpha = if (motion) emphasis.value.coerceIn(.86f, 1f) else 1f },
        style = style.copy(fontFeatureSettings = "tnum"), color = color)
}

