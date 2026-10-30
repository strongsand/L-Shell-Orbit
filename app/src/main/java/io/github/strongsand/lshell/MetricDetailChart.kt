package io.github.strongsand.lshell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MetricDetailChart(series: MetricHistorySeries, title: String, unit: String,
    zone: String? = null, eventMarkers: List<Long> = emptyList(), initialSelectedTime: Long? = null,
    selectionContent: (@Composable (MetricHistoryPoint?, Long, () -> Unit) -> Unit)? = null) {
    val palette = MaterialTheme.colorScheme
    val monochrome = LocalExperience.current.monochrome
    val density = LocalDensity.current
    val rawValues = remember(series) { series.values }
    val truePeak = remember(rawValues) { rawValues.maxOrNull() }
    val scale = remember(rawValues) { MetricChartScale.from(rawValues) }
    val full = remember(series.start, series.end) { MetricTimeViewport(series.start, series.end) }
    val parameters = remember { MetricFocusParameters() }
    val ticks = remember(scale) { scale.ticks }
    val measurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = palette.onSurfaceVariant)
    val timeStyle = MaterialTheme.typography.labelSmall.copy(color = palette.onSurface)
    val labels = remember(ticks, tickStyle, density) { ticks.map { measurer.measure(metricAxisLabel(it), tickStyle) } }
    val gutter = with(density) { (labels.maxOf { it.size.width } + 8.dp.toPx()).toDp() }
    var measuredWidth by remember { mutableIntStateOf(0) }
    val widthDp = (measuredWidth / density.density - gutter.value - 8).toDouble()
    val focusDensity = remember(series, widthDp) { MetricFocusDensity.from(series, widthDp, parameters) }
    val motion = LocalOrbitMotion.current
    var anchor by remember(title) { mutableStateOf<MetricFocusAnchor?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var targetIntensity by remember { mutableFloatStateOf(0f) }
    val focusTarget = remember(title) { MetricFocusTarget(parameters) }
    var targetZoom by remember(title) { mutableStateOf(1.0) }
    val logZoom by animateFloatAsState(MetricPrecisionLens.logZoom(targetZoom),
        if (motion) spring(dampingRatio = 1f, stiffness = parameters.springStiffness) else snap(), label = "metric precision lens")
    val zoom = MetricPrecisionLens.zoomFromLog(logZoom).coerceIn(1.0, parameters.maximumZoom)
    val focusAmount by animateFloatAsState(targetIntensity,
        if (motion) spring(dampingRatio = .82f, stiffness = 380f) else snap(), label = "metric focus emphasis")
    var selectedTime by rememberSaveable(title) { mutableStateOf<Long?>(initialSelectedTime) }
    var snapTime by remember(title) { mutableStateOf<Long?>(null) }
    val visible = anchor?.takeIf { dragging || zoom > 1.001f }?.let {
        MetricPrecisionLens.viewport(it, zoom.toDouble(), parameters.minimumWindowMs)
    } ?: full
    val snapScale = if (dragging && targetIntensity > 0 && zoom > 1.1 && widthDp > 0) visible.duration / widthDp else 0.0
    val selected = remember(series, selectedTime, snapTime, snapScale) {
        selectedTime?.let { MetricFocusSnap.inspect(series, it, snapTime, snapScale, parameters) }
    }
    LaunchedEffect(dragging, zoom) { if (!dragging && zoom <= 1.001f) anchor = null }
    val normalPlot = remember(series) { MetricChartPresentation.reduce(series, full) }
    val precisionPlot = remember(series) { MetricPrecisionPlot(series) }
    val detailed = zoom > 4
    val plotted = remember(normalPlot, precisionPlot, visible, detailed) {
        if (detailed) precisionPlot.reduce(visible) else MetricPrecisionLens.visible(normalPlot, visible)
    }
    // A dedicated non-interactive rail keeps the clock above the finger even at the top of the plot.
    val timeRail = with(density) { (timeStyle.fontSize.toPx() * 1.5f + 12.dp.toPx()).toDp() } + parameters.fingerClearanceDp.dp
    val height = 230.dp + (24 * (density.fontScale - 1).coerceAtLeast(0f)).dp + timeRail
    val haptics = rememberDashHaptics()
    val feedback = remember(title) { MetricInspectionFeedback() }
    var keyboardFocused by remember { mutableStateOf(false) }
    val description = stringResource(R.string.metric_detail_chart_description, title,
        trafficNumber(series.coveragePercent), trafficValue(truePeak, unit))
    val accessHint = stringResource(R.string.metric_detail_inspect_hint)
    val inspectLabel = stringResource(R.string.metric_detail_inspect_action)
    val previousLabel = stringResource(R.string.metric_detail_previous)
    val nextLabel = stringResource(R.string.metric_detail_next)
    val noReading = stringResource(R.string.metric_detail_gap)
    val pointerTime = selected?.time ?: selectedTime
    val selectionDescription = selected?.let { chartSelectionDescription(trafficDate(it.time, zone) + " · " + metricInspectionTime(it.time, zone),
        trafficNumber(it.measurement!!.value) + " " + unit, trafficOrigin(it.measurement.origin)) }
        ?: selectedTime?.let { metricInspectionTime(it, zone) + ". " + noReading } ?: description
    val pointerFraction = pointerTime?.let { ((it - visible.start).toDouble() / visible.duration).toFloat().coerceIn(0f, 1f) } ?: .5f
    val snapPulse = rememberChartPulse(selected?.time)
    val timeLabel = remember(pointerTime, timeStyle, density, zone) {
        pointerTime?.let { measurer.measure(metricInspectionTime(it, zone), timeStyle) }
    }
    val valid = remember(series) { series.points.filter { it.time >= series.start && it.measurement != null } }
    val selectionIndex = remember(series) { ChartSampleIndex(series) }
    val travelTarget = selected?.measurement?.let { measurement ->
        selectionIndex.segmentAt(selected.time)?.let { segment ->
            ChartMotionTarget(selected.time, Offset(pointerFraction,
                (1 - scale.fraction(measurement.value)).toFloat()), segment)
        }
    }
    val travel = rememberChartTravel(travelTarget, dragging,
        Triple(title, visible, scale), Size(widthDp.toFloat().coerceAtLeast(1f),
            (height.value - maxOf(timeRail.value, labels.maxOf { it.size.height } / density.density / 2 + 4) -
                labels.maxOf { it.size.height } / density.density / 2 - 4).coerceAtLeast(1f)))
    fun inspect(time: Long) {
        val probe = time.coerceIn(series.start, maxOf(series.start, series.end - 1))
        snapTime = MetricFocusSnap.inspect(series, probe, snapTime, snapScale, parameters)?.time
        selectedTime = probe
        if (feedback.sample(snapTime, android.os.SystemClock.uptimeMillis())) haptics.perform(DashFeedback.DRAG_SNAP)
    }
    fun closeInspection(): Boolean {
        selectedTime = null; snapTime = null; targetIntensity = 0f; targetZoom = 1.0; focusTarget.end()
        feedback.sample(null, android.os.SystemClock.uptimeMillis())
        return true
    }
    fun step(forward: Boolean): Boolean {
        targetIntensity = 0f
        targetZoom = 1.0; focusTarget.end(); snapTime = null
        val current = selected?.time ?: selectedTime
        val point = selectionIndex.adjacent(current, forward)
        point ?: return false
        inspect(point.time); return true
    }
    val latestVisible by rememberUpdatedState(visible)
    val latestFull by rememberUpdatedState(full)
    val latestZoom by rememberUpdatedState(zoom)
    val latestDensity by rememberUpdatedState(focusDensity)
    val latestInspect by rememberUpdatedState<(Long) -> Unit>({ inspect(it) })
    // Live axis labels can change width. Refresh geometry without cancelling an owned gesture.
    val latestGutterPx by rememberUpdatedState(with(density) { gutter.toPx() })
    val latestTimeRailPx by rememberUpdatedState(with(density) { timeRail.toPx() })
    val latestPixelDensity by rememberUpdatedState(density.density)
    val edge = anchor?.let { MetricPrecisionLens.edgeStrength(it.fraction, parameters.edgeMargin) } ?: 0.0
    val panActive = dragging && targetIntensity > 0 && zoom > 1.001 && edge != 0.0
    LaunchedEffect(panActive, edge < 0) {
        if (panActive) {
            var previous = withFrameNanos { it }
            var edgeAgeMs = 0.0
            while (true) {
                val now = withFrameNanos { it }
                val elapsedMs = ((now - previous) / 1_000_000.0).coerceIn(0.0, 50.0)
                previous = now; edgeAgeMs += elapsedMs
                if (elapsedMs <= 0) continue
                val current = anchor ?: break
                val next = MetricPrecisionLens.pan(current, latestZoom, elapsedMs, edgeAgeMs, parameters)
                if (next.time == current.time) break // No idle frame loop at a real observation boundary.
                anchor = next
                latestInspect(next.time.toLong())
            }
        }
    }
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero,
        color = if (LocalExperience.current.theme == OrbitTheme.AMOLED) palette.surface else palette.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Text(unit, style = MaterialTheme.typography.labelLarge, color = palette.onSurfaceVariant)
            }
            Text(trafficPeriodDate(series.start, series.end), style = MaterialTheme.typography.labelSmall,
                color = palette.onSurfaceVariant)
            Text(stringResource(R.string.metric_detail_coverage, trafficNumber(series.coveragePercent),
                trafficShortDuration(series.coveredMs)), style = MaterialTheme.typography.labelMedium)
            if (valid.isEmpty()) {
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.metric_no_readings), color = palette.onSurfaceVariant)
                }
            } else {
                Box(Modifier.fillMaxWidth().height(height).onSizeChanged { measuredWidth = it.width }) {
                    // Cached paths never read selection. Moving the crosshair redraws only its overlay.
                    val labelRoom = with(density) { timeRail.toPx() }
                    val staticPlot = remember(plotted, visible, scale, gutter, labels, palette, monochrome, labelRoom, eventMarkers) {
                        Modifier.fillMaxSize().clipToBounds().drawWithCache {
                        val left = gutter.toPx(); val right = maxOf(left + 1, size.width - 8.dp.toPx())
                        val top = maxOf(labelRoom, labels.maxOf { it.size.height } / 2f + 4.dp.toPx())
                        val bottom = size.height - labels.maxOf { it.size.height } / 2f - 4.dp.toPx()
                        fun x(time: Long) = left + ((time - visible.start).toDouble() / visible.duration * (right - left)).toFloat()
                        fun y(value: Double) = bottom - (scale.fraction(value) * (bottom - top)).toFloat()
                        fun tint(origin: TrafficOrigin) = when (origin) {
                            TrafficOrigin.BEACON -> palette.tertiary
                            TrafficOrigin.MIXED -> palette.secondary
                            else -> palette.primary
                        }
                        val paths = mutableMapOf<Color, Path>()
                        plotted.forEachIndexed { index, point ->
                            val value = point.value ?: return@forEachIndexed
                            val before = plotted.getOrNull(index - 1)
                            val color = tint(point.origin)
                            if (point.connect && before?.value != null) paths.getOrPut(color) { Path() }.apply {
                                moveTo(x(before.time), y(before.value)); lineTo(x(point.time), y(value))
                            }
                        }
                        onDrawBehind {
                            ticks.forEachIndexed { index, value ->
                                val yy = y(value)
                                drawLine(palette.outlineVariant.copy(alpha = .4f), Offset(left, yy), Offset(right, yy), .5.dp.toPx())
                                drawText(labels[index], topLeft = Offset(0f, yy - labels[index].size.height / 2))
                            }
                            clipRect(left = left, top = top, right = right, bottom = bottom) {
                                paths.forEach { (color, path) ->
                                    val pattern = if (!monochrome) null else when (color) {
                                        palette.secondary -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 3.dp.toPx()))
                                        palette.tertiary -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
                                        else -> null
                                    }
                                    drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = pattern))
                                }
                                // Bounded grouped events: ticks retain timing without covering the series.
                                eventMarkers.filter { it in visible.start until visible.end }.forEach { time ->
                                    drawLine(palette.onSurfaceVariant, Offset(x(time), bottom - 5.dp.toPx()),
                                        Offset(x(time), bottom), 1.dp.toPx())
                                }
                            }
                        }
                        }
                    }
                    Box(staticPlot)
                    Canvas(Modifier.fillMaxSize().clipToBounds().semantics {
                        contentDescription = "$description $accessHint"
                        stateDescription = selectionDescription
                        onClick(label = inspectLabel) { if (selectedTime == null) step(true) else true }
                        customActions = listOf(CustomAccessibilityAction(previousLabel) { step(false) }, CustomAccessibilityAction(nextLabel) { step(true) })
                    }.onFocusChanged { keyboardFocused = it.isFocused }.onKeyEvent {
                        if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                            Key.DirectionLeft -> step(false)
                            Key.DirectionRight -> step(true)
                            Key.Enter, Key.Spacebar -> if (selectedTime == null) step(true) else true
                            Key.Escape -> closeInspection()
                            else -> false
                        }
                    }.focusable().pointerInput(title) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (down.position.y < latestTimeRailPx) return@awaitEachGesture
                            val period = latestFull
                            val ownership = MetricGestureOwnership(viewConfiguration.touchSlop)
                            val velocity = MetricDragFocus(parameters)
                            focusTarget.end(); snapTime = null
                            val pixelDensity = latestPixelDensity.toDouble()
                            velocity.begin(down.position.x / pixelDensity, down.uptimeMillis)
                            fun fraction(x: Float) = ((x - latestGutterPx) /
                                (size.width - latestGutterPx - 8 * latestPixelDensity).coerceAtLeast(1f)).toDouble().coerceIn(0.0, 1.0)
                            try {
                                while (true) {
                                    // Leave pending/vertical input alone. Once acquired, reserve the
                                    // stream before the parent's Main-pass scroll detector sees it.
                                    val initial = awaitPointerEvent(PointerEventPass.Initial)
                                    if (ownership.inspecting) initial.changes.firstOrNull { it.id == down.id }?.consume()
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val delta = change.position - down.position
                                    if (change.pressed || change.isConsumed)
                                        ownership.move(delta.x, delta.y, change.isConsumed)
                                    if (ownership.yielded) break
                                    if (ownership.inspecting) {
                                        dragging = true
                                        change.consume()
                                        val position = fraction(change.position.x)
                                        // Anchor the unsnapped time. Reanchoring to a rounded sample would lock
                                        // small movements to that sample instead of letting the finger advance.
                                        val time = latestVisible.time(position)
                                        val bounds = latestDensity
                                        anchor = MetricFocusAnchor(period, time.toDouble(), position, bounds.firstTime, bounds.lastSupportedTime)
                                        if (change.pressed) {
                                            val reading = velocity.move(change.position.x / pixelDensity, change.uptimeMillis)
                                            if (targetIntensity == 0f && reading.intensity > 0 &&
                                                feedback.focus(android.os.SystemClock.uptimeMillis())) haptics.perform(DashFeedback.SPECIAL)
                                            targetIntensity = reading.intensity.toFloat()
                                            // Slow accepted movement advances approach/precision. The
                                            // spring only smooths that target; reduced motion keeps
                                            // the same small functional steps instead of a full jump.
                                            targetZoom = focusTarget.zoom(reading, bounds)
                                        }
                                        latestInspect(time)
                                    }
                                    if (!change.pressed) {
                                        if (!ownership.inspecting) latestInspect(latestVisible.time(fraction(change.position.x)))
                                        break
                                    }
                                }
                            } finally {
                                // Keep the captured real sample after lifting, rather than snapping
                                // back across its midpoint when precision hysteresis is disabled.
                                if (targetIntensity > 0 && latestZoom > 1.1) snapTime?.let { selectedTime = it }
                                ownership.end()
                                velocity.end(); focusTarget.end(); dragging = false; targetIntensity = 0f; targetZoom = 1.0
                            }
                        }
                    }) {
                        if (keyboardFocused) drawRoundRect(palette.outline.copy(alpha = .6f),
                            cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(1.dp.toPx()))
                        val time = pointerTime
                        if (time != null && time >= visible.start && time < visible.end) {
                            val left = gutter.toPx(); val right = maxOf(left + 1, size.width - 8.dp.toPx())
                            val top = maxOf(labelRoom, labels.maxOf { it.size.height } / 2f + 4.dp.toPx())
                            val bottom = size.height - labels.maxOf { it.size.height } / 2f - 4.dp.toPx()
                            val x = left + ((time - visible.start).toDouble() / visible.duration * (right - left)).toFloat()
                            drawLine(palette.onSurface.copy(alpha = .7f + .2f * focusAmount), Offset(x, top), Offset(x, bottom),
                                (1 + .5f * focusAmount).dp.toPx())
                            selected?.measurement?.let {
                                val y = bottom - (scale.fraction(it.value) * (bottom - top)).toFloat()
                                val emphasis = focusAmount.coerceIn(0f, 1.08f)
                                val pulse = if (motion) snapPulse.value.coerceIn(0f, 1.08f) else 0f
                                drawChartMarker(Offset(x, y), palette.chartOriginColor(it.origin), palette.surface, pulse, emphasis)
                                travelTarget?.let { target ->
                                    drawChartTravel(travel.value, target.position, palette.chartOriginColor(it.origin),
                                        left, top, right - left, bottom - top)
                                }
                            }
                            timeLabel?.let { label ->
                                val padding = 5.dp.toPx()
                                val labelX = x
                                val bounds = metricPointerLabelBounds(labelX, labelRoom, label.size.width + padding * 2,
                                    label.size.height + padding * 2, left, right, labelRoom, parameters.fingerClearanceDp.dp.toPx())
                                drawLine(palette.outlineVariant, Offset(bounds.left + bounds.width / 2, bounds.top + bounds.height),
                                    Offset(x, top), 1.dp.toPx())
                                drawRoundRect(palette.surfaceContainerHighest, Offset(bounds.left, bounds.top),
                                    Size(bounds.width, bounds.height), CornerRadius(8.dp.toPx()))
                                clipRect(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height) {
                                    drawText(label, topLeft = Offset(bounds.left + padding, bounds.top + padding),
                                        alpha = if (motion) 1f - .12f * snapPulse.value.coerceIn(0f, 1f) else 1f)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = gutter, end = 8.dp)) {
                    Text(trafficTime(visible.start, zone), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Text(trafficTime(visible.end, zone), Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall)
                }
                ChartInspectionPanel(selectedTime?.let { it in series.start until series.end } == true) {
                    if (selectionContent != null) {
                        // AnimatedVisibility can still compose its content during the closing transition.
                        selectedTime?.let { time -> selectionContent(selected, selected?.time ?: time, { closeInspection() }) }
                    } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HorizontalDivider(color = palette.outlineVariant)
                        Column(Modifier.semantics(mergeDescendants = true) {}) {
                            MetricDetailValueText(selectedTime?.let { trafficDate(selected?.time ?: it, zone) } ?: "", style = MaterialTheme.typography.labelMedium)
                            MetricDetailValueText(selected?.measurement?.let { trafficNumber(it.value) + " " + unit } ?: noReading,
                                style = MaterialTheme.typography.titleLarge)
                            selected?.measurement?.let { Text(trafficOrigin(it.origin), style = MaterialTheme.typography.bodyMedium) }
                        }
                        TextButton(onClick = hapticClick { closeInspection() }) {
                            Text(stringResource(R.string.traffic_close_inspection))
                        }
                    }
                }
                val origins = remember(series) { series.points.mapNotNull { it.measurement?.origin }.toSet() }
                if (TrafficOrigin.BEACON in origins || TrafficOrigin.MIXED in origins) FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    origins.forEach { origin ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val color = when (origin) { TrafficOrigin.BEACON -> palette.tertiary; TrafficOrigin.MIXED -> palette.secondary; else -> palette.primary }
                            Surface(Modifier.size(8.dp), shape = DashDesign.pill, color = color) {}
                            Text(trafficOrigin(origin), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

internal fun metricInspectionTime(time: Long, zone: String? = null): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).apply {
        if (zone != null) timeZone = java.util.TimeZone.getTimeZone(zone)
    }.format(java.util.Date(time))

internal fun metricAxisLabel(value: Double): String = if (value != 0.0 && (value < .0001 || value >= 1e9))
    String.format(java.util.Locale.getDefault(), "%.1e", value)
else java.text.NumberFormat.getNumberInstance().apply {
    maximumFractionDigits = if (value < 1) 2 else 1
    isGroupingUsed = false
}.format(value)
