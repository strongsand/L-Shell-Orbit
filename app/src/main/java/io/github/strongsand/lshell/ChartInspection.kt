package io.github.strongsand.lshell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Index original readings once. No per-drag scan, no snapping across missing support. */
internal class ChartSampleIndex(val series: MetricHistorySeries) {
    private val valid = series.points.filter { it.time >= series.start && it.time < series.end && it.measurement != null }
    // O(n) once per data revision, not a scan on each animation frame.
    private val segments = buildMap<Long, Long> {
        var previous: MetricHistoryPoint? = null
        var segment = series.start
        for (point in series.points) {
            if (!chartConnected(previous, point)) segment = point.time
            if (point.measurement != null) put(point.time, segment)
            previous = point
        }
    }
    fun segmentAt(time: Long): Long? = segments[time]
    fun inspect(time: Long) = MetricChartPresentation.inspect(series, time)
    /** Interval aggregates belong to their containing bucket, never the next bucket's mean. */
    fun containing(time: Long): MetricHistoryPoint? {
        if (time < series.start || time >= series.end) return null
        val points = series.points
        val found = points.binarySearch { it.time.compareTo(time) }
        val i = if (found >= 0) found else -found - 2
        val point = points.getOrNull(i) ?: return null
        val value = point.measurement ?: return null
        return point.takeIf { time < minOf(series.end, point.time + value.supportMs, points.getOrNull(i + 1)?.time ?: series.end) }
    }
    fun adjacent(time: Long?, forward: Boolean): MetricHistoryPoint? {
        if (time == null) return if (forward) valid.firstOrNull() else valid.lastOrNull()
        var low = 0; var high = valid.size
        while (low < high) {
            val mid = (low + high) / 2
            if (valid[mid].time < time || (forward && valid[mid].time == time)) low = mid + 1 else high = mid
        }
        return valid.getOrNull(if (forward) low else low - 1)
    }
}

internal fun chartTimeFraction(time: Long, start: Long, end: Long): Float =
    ((time - start).toDouble() / (end - start).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)

internal fun chartConnected(before: MetricHistoryPoint?, point: MetricHistoryPoint): Boolean =
    before?.measurement != null && point.measurement != null &&
        point.time <= before.time + before.measurement.supportMs

/** Decoration only; selected value and sample coordinates never animate. */
@Composable
internal fun rememberChartPulse(sampleTime: Long?): Animatable<Float, androidx.compose.animation.core.AnimationVector1D> {
    val pulse = remember { Animatable(0f) }
    val motion = LocalOrbitMotion.current
    LaunchedEffect(sampleTime, motion) {
        if (motion && sampleTime != null) {
            pulse.animateTo(1f, spring(dampingRatio = .72f, stiffness = 650f))
            pulse.animateTo(0f, spring(dampingRatio = .95f, stiffness = 500f))
        } else pulse.snapTo(0f)
    }
    return pulse
}

internal fun DrawScope.drawChartMarker(point: Offset, color: Color, surface: Color, pulse: Float = 0f, focus: Float = 0f) {
    val p = pulse.coerceIn(0f, 1.08f); val f = focus.coerceIn(0f, 1.08f)
    drawCircle(color.copy(alpha = .04f + .10f * f + .04f * p), (9 + 5 * f + 2 * p).dp.toPx(), point)
    drawCircle(color.copy(alpha = .16f + .12f * p), (6 + 2 * f + p).dp.toPx(), point, style = Stroke(.7.dp.toPx()))
    drawCircle(surface, (5 + 2 * f + .5f * p).dp.toPx(), point)
    drawCircle(color, (3 + 1.5f * f + .3f * p).dp.toPx(), point)
}

/** Spoken labels are resolved outside the non-composable semantics receiver. */
@Composable
internal fun Modifier.chartInspectionAccess(description: String, selection: String, inspect: () -> Boolean,
    step: (Boolean) -> Boolean, close: () -> Boolean): Modifier {
    val action = stringResource(R.string.metric_detail_inspect_action)
    val previous = stringResource(R.string.metric_detail_previous)
    val next = stringResource(R.string.metric_detail_next)
    val dismiss = stringResource(R.string.traffic_close_inspection)
    var focused by remember { mutableStateOf(false) }
    val outline = androidx.compose.material3.MaterialTheme.colorScheme.outline
    return semantics {
        contentDescription = description
        stateDescription = selection
        onClick(label = action, action = inspect)
        customActions = listOf(CustomAccessibilityAction(previous) { step(false) },
            CustomAccessibilityAction(next) { step(true) }, CustomAccessibilityAction(dismiss, close))
    }.onFocusChanged { focused = it.isFocused }.drawWithContent {
        drawContent()
        if (focused) drawRect(outline.copy(alpha = .6f), style = Stroke(1.dp.toPx()))
    }.onKeyEvent {
        if (it.type != KeyEventType.KeyDown) false else when (it.key) {
            Key.DirectionLeft -> step(false)
            Key.DirectionRight -> step(true)
            Key.Enter, Key.Spacebar -> inspect()
            Key.Escape -> close()
            else -> false
        }
    }.focusable()
}

/** Horizontal drag yields vertical scroll to the parent. Insets match the engine's plot. */
@Stable
internal class ChartDirectManipulation {
    var active by mutableStateOf(false)
}

internal fun Modifier.chartScrubbing(key: Any, insetPx: Float, gesture: ChartDirectManipulation? = null,
    select: (Float) -> Unit): Modifier =
    pointerInput(key, insetPx) { detectTapGestures { select(((it.x - insetPx) / (size.width - 2 * insetPx).coerceAtLeast(1f)).coerceIn(0f, 1f)) } }
        .pointerInput(key, insetPx) {
            fun inspect(x: Float) = select(((x - insetPx) / (size.width - 2 * insetPx).coerceAtLeast(1f)).coerceIn(0f, 1f))
            try {
                detectHorizontalDragGestures(
                    onDragStart = { gesture?.active = true; inspect(it.x) },
                    onDragEnd = { gesture?.active = false },
                    onDragCancel = { gesture?.active = false }
                ) { change, _ -> inspect(change.position.x); change.consume() }
            } finally { gesture?.active = false }
        }

@Composable
internal fun ChartInspectionPanel(visible: Boolean, content: @Composable () -> Unit) {
    val motion = LocalOrbitMotion.current
    AnimatedVisibility(visible,
        enter = fadeIn(tween(if (motion) 130 else 0)) + expandVertically(animationSpec = if (motion) spring(dampingRatio = 1f, stiffness = 550f) else snap()),
        exit = fadeOut(tween(if (motion) 80 else 0)) + shrinkVertically(animationSpec = if (motion) spring(dampingRatio = 1f, stiffness = 550f) else snap())) { content() }
}

/** A bucket is an aggregate of its explicit interval, never labelled as an individual reading. */
internal fun reportInspectionSeries(buckets: List<ReportChartBucket>): MetricHistorySeries {
    val points = buckets.map { bucket -> MetricHistoryPoint(bucket.start, bucket.value?.takeIf { it.isFinite() }?.let {
        TrafficValue(it.toDouble(), when (bucket.origin) {
            ReportBucketOrigin.BEACON -> TrafficOrigin.BEACON
            ReportBucketOrigin.MIXED -> TrafficOrigin.MIXED
            ReportBucketOrigin.LOCAL -> TrafficOrigin.LOCAL_PHONE
            else -> TrafficOrigin.UNKNOWN
        }, (bucket.end - bucket.start).coerceAtLeast(0))
    }) }
    return MetricHistorySeries(buckets.firstOrNull()?.start ?: 0, buckets.lastOrNull()?.end ?: 0, points, 0, emptyList())
}

/** Ordinal rounds are intentionally not a time axis. Include insets before calling. */
internal fun chartOrdinalIndex(fraction: Float, count: Int): Int {
    require(count > 0)
    return kotlin.math.round(fraction.coerceIn(0f, 1f) * (count - 1)).toInt()
}

/** Shared cooldown for a real new sample and focus acquisition; no repeated tick on a held point/gap. */
internal class MetricInspectionFeedback(private val cooldownMs: Long = 300) {
    private var lastSample: Long? = null
    private var lastFeedbackMs: Long? = null
    fun sample(time: Long?, nowMs: Long): Boolean {
        if (time == lastSample) return false
        lastSample = time
        return time != null && allow(nowMs)
    }
    fun focus(nowMs: Long) = allow(nowMs)
    private fun allow(nowMs: Long): Boolean {
        if (lastFeedbackMs?.let { nowMs - it < cooldownMs } == true) return false
        lastFeedbackMs = nowMs
        return true
    }
}

/** Same concise spoken/visible selection structure; callers supply localized labels and units. */
internal fun chartSelectionDescription(time: String, value: String, origin: String? = null): String =
    listOfNotNull(time.takeIf { it.isNotBlank() }, value.takeIf { it.isNotBlank() }, origin?.takeIf { it.isNotBlank() }).joinToString(" · ")
