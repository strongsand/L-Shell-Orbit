package io.github.strongsand.lshell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/** A small scalar overview, not a zoomable detail chart or a configurable universal engine. */
@Composable
internal fun CompactHistoryChart(series: MetricHistorySeries, title: String, unit: String) {
    val palette = MaterialTheme.colorScheme
    val index = remember(series) { ChartSampleIndex(series) }
    var probe by rememberSaveable(title) { mutableStateOf<Long?>(null) }
    val selected = remember(index, probe) { probe?.let(index::inspect) }
    val feedback = remember(title) { MetricInspectionFeedback() }
    val haptics = rememberDashHaptics()
    val pulse = rememberChartPulse(selected?.time)
    val motion = LocalOrbitMotion.current
    val gap = stringResource(R.string.metric_detail_gap)
    val point = selected ?: if (probe == null) series.points.lastOrNull() else null
    val valueText = point?.measurement?.let { trafficValue(it.value, unit) } ?: gap
    val timeText = (selected?.time ?: probe ?: point?.time)?.let { trafficDate(it) + " · " + trafficTime(it, seconds = true) }.orEmpty()
    val originText = point?.measurement?.let { trafficOrigin(it.origin) }.orEmpty()
    val spoken = chartSelectionDescription(timeText, valueText, originText)
    val peak = remember(series) { series.values.maxOrNull() }
    fun inspect(time: Long) {
        probe = time.coerceIn(series.start, maxOf(series.start, series.end - 1))
        if (feedback.sample(index.inspect(probe!!)?.time, android.os.SystemClock.uptimeMillis())) haptics.perform(DashFeedback.DRAG_SNAP)
    }
    fun step(forward: Boolean): Boolean {
        val next = index.adjacent(selected?.time ?: probe, forward) ?: return false
        inspect(next.time); return true
    }
    fun close(): Boolean { probe = null; feedback.sample(null, android.os.SystemClock.uptimeMillis()); return true }
    val latestInspect by rememberUpdatedState<(Float) -> Unit>({ f -> inspect(series.start + ((series.end - series.start) * f.toDouble()).toLong()) })
    val density = LocalDensity.current
    val inset = with(density) { 14.dp.toPx() }
    val scale = remember(series) { reportChartScale(series.values.map { it.toFloat() }) }
    val gesture = remember { ChartDirectManipulation() }
    var plotSize by remember { mutableStateOf(Size(1f, 1f)) }
    val target = selected?.measurement?.let { m ->
        index.segmentAt(selected.time)?.let { segment ->
            val y = if (scale?.constant == true) .5f else scale?.let { ((m.value.toFloat() - it.minimum) / it.range).coerceIn(0f, 1f) } ?: .5f
            ChartMotionTarget(selected.time, Offset(chartTimeFraction(selected.time, series.start, series.end), 1f - y), segment)
        }
    }
    val travel = rememberChartTravel(target, gesture.active, Triple(title, series.start to series.end, scale), plotSize)
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
        color = if (LocalExperience.current.theme == OrbitTheme.AMOLED) palette.surface else palette.surfaceContainerLow) {
        Column(Modifier.padding(DashDesign.inset), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            MetricDetailValueText(valueText, MaterialTheme.typography.titleLarge)
            Text(timeText, style = MaterialTheme.typography.labelMedium, color = palette.onSurfaceVariant)
            if (originText.isNotBlank()) Text(originText, style = MaterialTheme.typography.labelMedium, color = palette.onSurfaceVariant)
            peak?.let { Text(stringResource(R.string.editorial_peak_value, trafficNumber(it), unit), style = MaterialTheme.typography.labelSmall, color = palette.onSurfaceVariant) }
            if (scale == null) Text(stringResource(R.string.metric_no_readings)) else Box {
                PassiveHistoryPlot(series)
                Canvas(Modifier.fillMaxWidth().height(140.dp).onSizeChanged {
                    plotSize = Size((it.width / density.density - 28f).coerceAtLeast(1f), (it.height / density.density - 28f).coerceAtLeast(1f))
                }.clipToBounds()
                    .chartInspectionAccess(title, spoken, { if (probe == null) step(true) else true }, ::step, ::close)
                    .chartScrubbing(title, inset, gesture) { latestInspect(it) }) {
                    if (probe != null) {
                        val x = inset + chartTimeFraction(selected?.time ?: probe!!, series.start, series.end) * (size.width - 2 * inset)
                        drawLine(palette.onSurface.copy(alpha = .7f), Offset(x, inset), Offset(x, size.height - inset), 1.dp.toPx())
                        selected?.measurement?.let { m ->
                            val f = if (scale.constant) .5f else ((m.value.toFloat() - scale.minimum) / scale.range).coerceIn(0f, 1f)
                            drawChartMarker(Offset(x, size.height - inset - f * (size.height - 2 * inset)),
                                palette.chartOriginColor(m.origin), palette.surfaceContainerLow, if (motion) pulse.value else 0f)
                            target?.let { drawChartTravel(travel.value, it.position, palette.chartOriginColor(m.origin),
                                inset, inset, size.width - 2 * inset, size.height - 2 * inset) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(trafficTime(series.start), style = MaterialTheme.typography.labelSmall)
                Text(trafficTime(series.end), style = MaterialTheme.typography.labelSmall)
            }
            ChartInspectionPanel(probe != null) {
                TextButton(onClick = { close() }) { Text(stringResource(R.string.traffic_close_inspection)) }
            }
        }
    }
}

/** Passive stays passive. Geometry is cached independently of the interactive overlay. */
@Composable
internal fun PassiveHistoryPlot(series: MetricHistorySeries, description: String? = null, height: Dp = 140.dp) {
    val palette = MaterialTheme.colorScheme
    val scale = remember(series) { reportChartScale(series.values.map { it.toFloat() }) } ?: return
    val plotted = remember(series) { MetricChartPresentation.reduce(series, MetricTimeViewport(series.start, series.end)) }
    val drawing = remember(plotted, scale, series.start, series.end, palette, height) {
        Modifier.fillMaxWidth().height(height).clipToBounds().drawWithCache {
            val inset = 14.dp.toPx()
            fun position(p: TrafficPlotPoint): Offset {
                val f = if (scale.constant) .5f else ((p.value!!.toFloat() - scale.minimum) / scale.range).coerceIn(0f, 1f)
                return Offset(inset + chartTimeFraction(p.time, series.start, series.end) * (size.width - 2 * inset),
                    size.height - inset - f * (size.height - 2 * inset))
            }
            val paths = mutableMapOf<Color, Path>()
            val dots = mutableListOf<Pair<Color, Offset>>()
            plotted.forEachIndexed { i, point ->
                if (point.value == null) return@forEachIndexed
                val color = palette.chartOriginColor(point.origin)
                val previous = plotted.getOrNull(i - 1)
                if (point.connect && previous?.value != null) paths.getOrPut(color) { Path() }.apply {
                    val from = position(previous); val to = position(point)
                    moveTo(from.x, from.y); lineTo(to.x, to.y)
                } else dots += color to position(point)
            }
            onDrawBehind {
                repeat(3) { i ->
                    val y = inset + (size.height - 2 * inset) * (i + 1) / 4f
                    drawLine(palette.outlineVariant.copy(alpha = .32f), Offset(inset, y), Offset(size.width - inset, y), .5.dp.toPx())
                }
                paths.forEach { (color, path) -> drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)) }
                dots.forEach { (color, point) -> drawCircle(color, 2.5.dp.toPx(), point) }
            }
        }
    }
    Box(drawing.then(if (description == null) Modifier else Modifier.semantics { contentDescription = description }))
}

internal fun ColorScheme.chartOriginColor(origin: TrafficOrigin): Color = when (origin) {
    TrafficOrigin.BEACON -> tertiary
    TrafficOrigin.MIXED -> secondary
    TrafficOrigin.UNKNOWN -> onSurfaceVariant
    TrafficOrigin.LOCAL_PHONE -> primary
}
