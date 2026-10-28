package io.github.strongsand.lshell

import kotlin.math.asinh
import kotlin.math.sinh

/** Presentation only. No value is clipped, persisted, interpolated or replaced by an aggregate. */
internal data class MetricChartScale(val maximum: Double, val pivot: Double? = null) {
    val compressed get() = pivot != null
    private fun transform(value: Double) = pivot?.let { asinh(value / it) } ?: value
    fun fraction(value: Double) = transform(value) / transform(maximum)
    val ticks: List<Double> get() = (0..4).map { index ->
        val position = transform(maximum) * index / 4
        pivot?.let { sinh(position) * it } ?: position
    }
    companion object {
        fun from(values: List<Double>): MetricChartScale {
            val sorted = values.filter { it.isFinite() && it >= 0 }.sorted()
            val peak = sorted.lastOrNull() ?: 0.0
            val typical = sorted.getOrNull(((sorted.size - 1) * .9).toInt()) ?: 0.0
            val pivot = typical.takeIf { sorted.size >= 10 && it > 0 && peak > it * 8 }
            return MetricChartScale(if (peak > 0) peak * 1.08 else 1.0, pivot)
        }
    }
}

internal data class MetricTimeViewport(val start: Long, val end: Long) {
    val duration get() = (end - start).coerceAtLeast(1)
    fun time(fraction: Double) = start + (fraction.coerceIn(0.0, 1.0) * duration).toLong()
    companion object {
        fun focus(start: Long, end: Long, anchor: Long, fraction: Double): MetricTimeViewport {
            val span = (end - start).coerceAtLeast(1)
            val width = maxOf(30_000L, span / 6).coerceAtMost(span)
            val left = (anchor - (width * fraction.coerceIn(0.0, 1.0)).toLong()).coerceIn(start, end - width)
            return MetricTimeViewport(left, left + width)
        }
    }
}

internal object MetricChartPresentation {
    /** Four real representatives per temporal bucket: endpoints plus true min/max.
     * Run IDs preserve *every* missing support boundary, including gaps inside a bucket.
     * A fragmented bucket may show disconnected dots, never a fabricated continuous line. */
    fun reduce(series: MetricHistorySeries, viewport: MetricTimeViewport, buckets: Int = 360): List<TrafficPlotPoint> {
        require(buckets > 0)
        val width = maxOf(1L, (viewport.duration + buckets - 1) / buckets)
        var run = 0
        var previous: MetricHistoryPoint? = null
        val groups = linkedMapOf<Long, MutableList<Pair<MetricHistoryPoint, Int>>>()
        series.points.forEach { point ->
            val before = previous
            if (!chartConnected(before, point)) run++
            previous = point
            if (point.measurement != null && point.time >= viewport.start && point.time < viewport.end)
                groups.getOrPut((point.time - viewport.start) / width) { mutableListOf() }.add(point to run)
        }
        val representatives = groups.values.flatMap { group ->
            listOf(group.first(), group.minBy { it.first.measurement!!.value },
                group.maxBy { it.first.measurement!!.value }, group.last()).distinctBy { it.first.time }.sortedBy { it.first.time }
        }
        var previousRun: Int? = null
        return representatives.map { (point, id) ->
            val value = point.measurement!!
            TrafficPlotPoint(point.time, value.value, value.value, value.value, value.origin,
                connect = (previousRun == id)).also { previousRun = id }
        }
    }

    /** Snap only within actual temporal support. An empty interval stays empty even near a point. */
    fun inspect(series: MetricHistorySeries, time: Long): MetricHistoryPoint? {
        if (time < series.start || time >= series.end) return null
        val points = series.points
        var low = 0; var high = points.size
        while (low < high) {
            val mid = (low + high) / 2
            if (points[mid].time <= time) low = mid + 1 else high = mid
        }
        val index = low - 1
        val before = points.getOrNull(index) ?: return null
        val measurement = before.measurement ?: return null
        val next = points.getOrNull(index + 1)
        val supportEnd = minOf(series.end, before.time + measurement.supportMs, next?.time ?: series.end)
        if (time >= supportEnd) return null
        // Prefer the nearest real timestamp, but only inside the same supported run.
        return if (next?.measurement != null && next.time <= before.time + measurement.supportMs &&
            next.time - time < time - before.time) next else before
    }
}
