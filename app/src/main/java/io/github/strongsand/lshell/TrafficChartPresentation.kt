package io.github.strongsand.lshell

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Display geometry only. Never changes sample values or statistical calculations. */
internal data class TrafficAxis(val maximum: Double, val step: Double) {
    val ticks get() = (4 downTo 0).map { it * step }
    fun label(value: Double, locale: Locale = Locale.getDefault()): String {
        if (value != 0.0 && (value < .00001 || value >= 1e9)) return String.format(locale, "%.1e", value)
        val decimals = if (step >= 10) 0 else (ceil(-log10(step)).toInt() + 1).coerceIn(0, 6)
        return NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = decimals; minimumFractionDigits = 0; isGroupingUsed = false
        }.format(value)
    }
}

internal fun trafficAxis(realMaximum: Double?): TrafficAxis {
    val maximum = realMaximum?.takeIf { it.isFinite() && it > 0 } ?: 1.0
    val rough = maximum * 1.1 / 4
    val magnitude = 10.0.pow(floor(log10(rough)))
    val normalized = rough / magnitude
    val factor = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it >= normalized }
    val step = factor * magnitude
    return TrafficAxis(step * 4, step)
}

internal fun trafficPlotLimit(durationMs: Long) = if (durationMs <= 1_800_000) 1800 else 600

internal fun trafficTime(time: Long, zone: String? = null, seconds: Boolean = false): String =
    DateTimeFormatter.ofPattern(if (seconds) "HH:mm:ss" else "HH:mm", Locale.getDefault())
        .withZone(zone?.let(ZoneId::of) ?: ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))

internal fun trafficPeriodDate(start: Long, end: Long, zone: String? = null): String {
    val timezone = zone?.let(ZoneId::of) ?: ZoneId.systemDefault()
    val a = Instant.ofEpochMilli(start).atZone(timezone).toLocalDate()
    val b = Instant.ofEpochMilli(end).atZone(timezone).toLocalDate()
    val format = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    return if (a == b) format.format(a) else format.format(a) + " – " + format.format(b)
}

internal fun trafficNearestPoint(points: List<TrafficPoint>, time: Long): Int? {
    if (points.isEmpty()) return null
    val found = points.binarySearch { it.time.compareTo(time) }
    if (found >= 0) return found
    val right = (-found - 1).coerceIn(0, points.lastIndex)
    val left = (right - 1).coerceAtLeast(0)
    return if (kotlin.math.abs(points[left].time - time) <= kotlin.math.abs(points[right].time - time)) left else right
}

/** The renderer reduces paths only; inspection always uses original normalized samples. */
internal object TrafficChartPresentation {
    fun series(a: TrafficAnalysis, metric: TrafficMetric) = MetricHistorySeries(a.start, a.end,
        a.points.map { MetricHistoryPoint(it.time, it.metric(metric)) }, a.summary(metric).coveredMs, emptyList())
    fun exactPoint(points: List<TrafficPoint>, time: Long): TrafficPoint? =
        points.binarySearchBy(time) { it.time }.takeIf { it >= 0 }?.let(points::get)
}
