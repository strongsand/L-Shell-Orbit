package io.github.strongsand.lshell

import io.github.strongsand.lshell.beacon.BeaconSampleSource

enum class HistoricalMetric { POWER, LATENCY, LOSS, DOWNLOAD, UPLOAD }
data class MetricHistoryPoint(val time: Long, val measurement: TrafficValue?)
data class MetricHistorySeries(val start: Long, val end: Long, val points: List<MetricHistoryPoint>,
    val coveredMs: Long, val plot: List<TrafficPlotPoint>) {
    val values get() = points.filter { it.time >= start }.mapNotNull { it.measurement?.value }
    val coveragePercent get() = if (end > start) coveredMs * 100.0 / (end - start) else 0.0
}

/** Optional metrics use the same per-second priority and bounded support as the traffic monitor. */
object MetricHistorySeriesBuilder {
    fun build(rows: List<HistoryEntry>, metric: HistoricalMetric, start: Long, end: Long,
        limit: Int = 300): MetricHistorySeries {
        require(end >= start && limit > 0)
        fun reading(row: HistoryEntry): Float? = when (metric) {
            HistoricalMetric.POWER -> row.powerWatts
            HistoricalMetric.LATENCY -> row.latencyMs
            HistoricalMetric.LOSS -> row.dropPercent
            HistoricalMetric.DOWNLOAD -> row.downloadMbps
            HistoricalMetric.UPLOAD -> row.uploadMbps
        }?.takeIf { it.isFinite() && when (metric) {
            HistoricalMetric.LOSS -> it in 0f..100f
            HistoricalMetric.LATENCY -> it > 0f
            HistoricalMetric.POWER -> it >= 0f
            HistoricalMetric.DOWNLOAD, HistoricalMetric.UPLOAD -> it >= 0f
        } }
        val points = rows.asSequence().filter { it.timestamp >= start - 5_000 && it.timestamp < end &&
            it.kind in setOf("reading", "terminal_history", "beacon_history") }
            .groupBy { it.timestamp / 1_000 }.map { (second, group) ->
                val row = group.sortedWith(compareByDescending<HistoryEntry> {
                    when (it.kind) { "terminal_history" -> 2; "beacon_history" -> 1; else -> 0 }
                }.thenByDescending { it.timestamp }).firstOrNull { reading(it) != null }
                MetricHistoryPoint(second * 1_000, row?.let {
                    TrafficValue(reading(it)!!.toDouble(),
                        if (it.source == BeaconSampleSource.BEACON) TrafficOrigin.BEACON else TrafficOrigin.LOCAL_PHONE,
                        when (it.kind) { "terminal_history" -> 1_000; "beacon_history" -> 5_000; else -> 2_000 })
                })
            }.sortedBy { it.time }
        val covered = points.mapIndexed { index, point ->
            point.measurement?.let {
                (minOf(end, point.time + it.supportMs, points.getOrNull(index + 1)?.time ?: end) -
                    maxOf(start, point.time)).coerceAtLeast(0)
            } ?: 0L
        }.sum()
        val width = maxOf(1L, (end - start + limit - 1) / limit)
        val plot = mutableListOf<TrafficPlotPoint>()
        var previous: MetricHistoryPoint? = null
        points.filter { it.time >= start }.groupBy { (it.time - start) / width }.values.forEach { group ->
            var disconnected = false
            group.forEach { point ->
                val before = previous
                if (point.measurement == null || (before != null && (before.measurement == null ||
                        point.time > before.time + before.measurement.supportMs))) disconnected = true
                previous = point
            }
            val valid = group.filter { it.measurement != null }
            if (valid.isEmpty()) plot += TrafficPlotPoint(group.first().time, null, connect = false)
            else {
                // A gap inside a reduced bucket remains a break; reduction never fabricates continuity.
                if (disconnected && plot.lastOrNull()?.value != null)
                    plot += TrafficPlotPoint(group.first().time, null, connect = false)
                val values = valid.map { it.measurement!!.value }
                val origins = valid.map { it.measurement!!.origin }.distinct()
                plot += TrafficPlotPoint(valid[valid.size / 2].time, values.average(), values.min(), values.max(),
                    if (origins.size == 1) origins.single() else TrafficOrigin.MIXED, connect = !disconnected)
            }
        }
        return MetricHistorySeries(start, end, points, covered, plot)
    }
}
