package io.github.strongsand.lshell

import io.github.strongsand.lshell.beacon.BeaconSampleSource
import kotlin.math.max
import kotlin.math.min

/** A value and its own sampling support; optional arrays do not borrow another metric's origin. */
data class TrafficValue(val value: Double, val origin: TrafficOrigin, val supportMs: Long)
enum class TrafficOrigin { LOCAL_PHONE, BEACON, MIXED, UNKNOWN }
enum class TrafficMetric { DOWNLOAD, UPLOAD, LATENCY, LOSS }
data class TrafficPoint(val time: Long, val download: TrafficValue?, val upload: TrafficValue?,
    val latency: TrafficValue?, val loss: TrafficValue?, val power: TrafficValue? = null) {
    fun metric(metric: TrafficMetric) = when (metric) {
        TrafficMetric.DOWNLOAD -> download; TrafficMetric.UPLOAD -> upload
        TrafficMetric.LATENCY -> latency; TrafficMetric.LOSS -> loss
    }
}
data class TrafficSegment(val start: Long, val end: Long, val value: Double)
data class TrafficGap(val start: Long, val end: Long)
data class TrafficSummary(val count: Int = 0, val latest: Double? = null, val mean: Double? = null,
    val median: Double? = null, val peak: Double? = null, val usefulMinimum: Double? = null,
    val sustained30s: Double? = null, val sustained1m: Double? = null, val sustained5m: Double? = null,
    val estimatedBytes: Double? = null, val activeMs: Long = 0, val coveredMs: Long = 0,
    val aboveThresholdMs: Map<Double, Long> = emptyMap())
enum class TrafficEventKind { DOWNLINK, LOSS, OBSTRUCTION, UNREACHABLE, RECONNECT, STATE, ALERT }
data class TrafficEvent(val start: Long, val end: Long, val kind: TrafficEventKind,
    val detail: String? = null, val observations: Int = 1, val members: List<TrafficEvent> = emptyList())
data class TrafficAnalysis(val start: Long, val end: Long, val points: List<TrafficPoint>,
    val download: TrafficSummary, val upload: TrafficSummary, val latency: TrafficSummary,
    val loss: TrafficSummary, val coveredMs: Long, val gaps: List<TrafficGap>,
    val origin: TrafficOrigin, val events: List<TrafficEvent>, val downtimeMs: Long,
    val interruptionCount: Int, val averageIntervalMs: Double?,
    val power: TrafficSummary = TrafficSummary()) {
    val durationMs get() = (end - start).coerceAtLeast(0)
    val coveragePercent get() = if (durationMs > 0) coveredMs * 100.0 / durationMs else 0.0
    fun summary(metric: TrafficMetric) = when (metric) {
        TrafficMetric.DOWNLOAD -> download; TrafficMetric.UPLOAD -> upload
        TrafficMetric.LATENCY -> latency; TrafficMetric.LOSS -> loss
    }
}
data class TrafficPlotPoint(val time: Long, val value: Double?, val minimum: Double? = value,
    val maximum: Double? = value, val origin: TrafficOrigin = TrafficOrigin.UNKNOWN, val connect: Boolean = true)

object TrafficSessionAnalyzer {
    // Presentation threshold: above 100 kbit/s, not a claim of application activity/capacity.
    const val ACTIVE_MBPS = 0.1
    // Same 1% diagnostic threshold used by DailyReportGenerator (notifications use 25%).
    const val ELEVATED_LOSS_PERCENT = 1.0
    private val sampleKinds = setOf("reading", "terminal_history", "beacon_history")
    internal val outageStates = setOf("NO_DOWNLINK", "NO_PINGS", "OBSTRUCTED", "NO_SATS", "SEARCHING",
        "BOOTING", "THERMAL_SHUTDOWN", "UNREACHABLE", "STOWED", "INHIBIT_RF", "SLEEPING")

    fun analyze(entries: List<HistoryEntry>, start: Long, end: Long): TrafficAnalysis {
        require(end >= start)
        val rows = entries.filter { it.timestamp >= start - 5_000 && it.timestamp < end }.sortedBy { it.timestamp }
        val points = normalize(rows)
        val segments = TrafficMetric.entries.associateWith { segments(points, it, start, end) }
        val powerSegs = powerSegments(points, start, end)
        val intervals = (segments.getValue(TrafficMetric.DOWNLOAD) + segments.getValue(TrafficMetric.UPLOAD))
            .map { it.start to it.end }.sortedBy { it.first }
        val union = mergeRanges(intervals)
        val gaps = mutableListOf<TrafficGap>()
        var cursor = start
        union.forEach { (a, b) -> if (a > cursor) gaps += TrafficGap(cursor, a); cursor = max(cursor, b) }
        if (cursor < end) gaps += TrafficGap(cursor, end)
        val origin = origin(points.flatMap { p -> listOfNotNull(p.download, p.upload)
            .filter { p.time + it.supportMs > start }.map { it.origin } })
        val events = events(rows, start, end)
        val outages = observedOutages(rows, start, end)
        val times = points.filter { it.time >= start }.map { it.time }
        val averageInterval = if (times.size >= 2) (times.last() - times.first()).toDouble() / (times.size - 1) else null
        return TrafficAnalysis(start, end, points.filter { it.time >= start },
            summary(points, TrafficMetric.DOWNLOAD, segments.getValue(TrafficMetric.DOWNLOAD), start),
            summary(points, TrafficMetric.UPLOAD, segments.getValue(TrafficMetric.UPLOAD), start),
            summary(points, TrafficMetric.LATENCY, segments.getValue(TrafficMetric.LATENCY), start),
            summary(points, TrafficMetric.LOSS, segments.getValue(TrafficMetric.LOSS), start),
            union.sumOf { it.second - it.first }, gaps, origin, events,
            outages.sumOf { it.second - it.first }, outages.size, averageInterval,
            powerSummary(points, powerSegs, start))
    }

    /** Shared per-second, independent-metric normalization for charts and historical reports. */
    fun normalize(rows: List<HistoryEntry>): List<TrafficPoint> {
        return rows.filter { it.kind in sampleKinds }.groupBy { it.timestamp / 1_000 }
            .map { (second, group) ->
                // Prefer 1 Hz history over status polls; ties are deterministic and missing fields
                // fall back independently. A second contributes only one value to each statistic.
                val ordered = group.sortedWith(compareByDescending<HistoryEntry> {
                    when (it.kind) { "terminal_history" -> 2; "beacon_history" -> 1; else -> 0 }
                }.thenByDescending { it.timestamp })
                fun value(get: (HistoryEntry) -> Float, positive: Boolean = false, percent: Boolean = false): TrafficValue? {
                    val row = ordered.firstOrNull { get(it).isFinite() &&
                        (if (positive) get(it) > 0 else get(it) >= 0) && (!percent || get(it) <= 100) } ?: return null
                    return TrafficValue(get(row).toDouble(),
                        if (row.source == BeaconSampleSource.BEACON) TrafficOrigin.BEACON else TrafficOrigin.LOCAL_PHONE,
                        when (row.kind) { "terminal_history" -> 1_000; "beacon_history" -> 5_000; else -> 2_000 })
                }
                TrafficPoint(second * 1_000, value({ it.downloadMbps }), value({ it.uploadMbps }),
                    value({ it.latencyMs }, positive = true), value({ it.dropPercent }, percent = true),
                    value({ it.powerWatts ?: Float.NaN }))
            }.sortedBy { it.time }
    }

    fun segments(points: List<TrafficPoint>, metric: TrafficMetric, start: Long, end: Long): List<TrafficSegment> = buildList {
        points.forEachIndexed { index, p ->
            val v = p.metric(metric) ?: return@forEachIndexed
            val a = max(start, p.time)
            // Missing next metric is a boundary too: never bridge over NULL/NaN or a long gap.
            val b = min(end, min(p.time + v.supportMs, points.getOrNull(index + 1)?.time ?: end))
            if (b > a) add(TrafficSegment(a, b, v.value))
        }
    }

    fun powerSegments(points: List<TrafficPoint>, start: Long, end: Long): List<TrafficSegment> = buildList {
        points.forEachIndexed { index, p ->
            val v = p.power ?: return@forEachIndexed
            val a = max(start, p.time)
            val b = min(end, min(p.time + v.supportMs, points.getOrNull(index + 1)?.time ?: end))
            if (b > a) add(TrafficSegment(a, b, v.value))
        }
    }

    private fun powerSummary(points: List<TrafficPoint>, segments: List<TrafficSegment>, start: Long): TrafficSummary {
        val values = points.asSequence().filter { it.time >= start }.mapNotNull { it.power?.value }.toList()
        if (values.isEmpty() && segments.isEmpty()) return TrafficSummary()
        val sorted = values.sorted()
        return TrafficSummary(values.size, values.lastOrNull(), values.takeIf { it.isNotEmpty() }?.average(),
            median(sorted), sorted.lastOrNull(), sorted.firstOrNull { it > 0 },
            coveredMs = segments.sumOf { it.end - it.start })
    }

    private fun summary(points: List<TrafficPoint>, metric: TrafficMetric, segments: List<TrafficSegment>, start: Long): TrafficSummary {
        val values = points.asSequence().filter { it.time >= start }.mapNotNull { it.metric(metric)?.value }.toList()
        if (values.isEmpty() && segments.isEmpty()) return TrafficSummary()
        val sorted = values.sorted()
        val traffic = metric == TrafficMetric.DOWNLOAD || metric == TrafficMetric.UPLOAD
        val thresholds = if (metric == TrafficMetric.DOWNLOAD) listOf(50.0, 100.0, 200.0) else listOf(1.0, 5.0, 10.0)
        return TrafficSummary(values.size, values.lastOrNull(), values.takeIf { it.isNotEmpty() }?.average(),
            median(sorted), sorted.lastOrNull(), sorted.firstOrNull { it > 0 },
            if (traffic) sustainedMaximum(segments, 30_000) else null,
            if (traffic) sustainedMaximum(segments, 60_000) else null,
            if (traffic) sustainedMaximum(segments, 300_000) else null,
            if (traffic && segments.isNotEmpty()) segments.sumOf { it.value * (it.end - it.start) * 125.0 } else null,
            if (traffic) segments.filter { it.value > ACTIVE_MBPS + 1e-7 }.sumOf { it.end - it.start } else 0,
            segments.sumOf { it.end - it.start },
            if (traffic) thresholds.associateWith { t -> segments.filter { it.value > t }.sumOf { it.end - it.start } } else emptyMap())
    }

    fun median(sorted: List<Double>): Double? = if (sorted.isEmpty()) null else
        if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2

    /** Exact maxima of the documented bounded step estimate, O(n). Only complete contiguous windows. */
    fun sustainedMaximum(segments: List<TrafficSegment>, windowMs: Long): Double? {
        require(windowMs > 0)
        var best: Double? = null
        var from = 0
        while (from < segments.size) {
            var until = from + 1
            while (until < segments.size && segments[until].start == segments[until - 1].end) until++
            val groupStart = segments[from].start
            val groupEnd = segments[until - 1].end
            if (groupEnd - groupStart >= windowMs) {
                val prefix = DoubleArray(until - from + 1)
                for (i in from until until) prefix[i - from + 1] = prefix[i - from] + segments[i].value * (segments[i].end - segments[i].start)
                var leftIndex = from; var rightIndex = from
                fun integral(x: Long, left: Boolean): Double {
                    var i = if (left) leftIndex else rightIndex
                    while (i + 1 < until && segments[i].end <= x) i++
                    if (left) leftIndex = i else rightIndex = i
                    return prefix[i - from] + segments[i].value * (x - segments[i].start).coerceIn(0, segments[i].end - segments[i].start)
                }
                // Extremum occurs at an edge of the piecewise-constant signal or edge + window.
                var boundary = from; var shifted = from
                var candidate = groupStart + windowMs
                while (candidate <= groupEnd) {
                    val avg = (integral(candidate, false) - integral(candidate - windowMs, true)) / windowMs
                    best = best?.let { max(it, avg) } ?: avg
                    while (boundary < until && segments[boundary].end <= candidate) boundary++
                    while (shifted < until && segments[shifted].end + windowMs <= candidate) shifted++
                    val a = if (boundary < until) segments[boundary].end else Long.MAX_VALUE
                    val b = if (shifted < until) segments[shifted].end + windowMs else Long.MAX_VALUE
                    val next = min(a, b)
                    if (next > groupEnd || next <= candidate) break
                    candidate = next
                }
            }
            from = until
        }
        return best
    }

    /** Per-pixel time buckets retain min/max and split at every missing interval before reduction. */
    fun plot(analysis: TrafficAnalysis, metric: TrafficMetric, limit: Int = 600): List<TrafficPlotPoint> {
        require(limit > 0)
        if (analysis.points.isEmpty()) return emptyList()
        val width = max(1L, (analysis.durationMs + limit - 1) / limit)
        val result = mutableListOf<TrafficPlotPoint>()
        var previous: TrafficPoint? = null
        analysis.points.groupBy { (it.time - analysis.start) / width }.values.forEach { group ->
            val valid = group.filter { it.metric(metric) != null }
            var discontinuous = false
            group.forEach { point ->
                val before = previous
                val prior = before?.metric(metric)
                if (point.metric(metric) == null || (before != null &&
                    (prior == null || point.time > before.time + prior.supportMs))) discontinuous = true
                previous = point
            }
            if (valid.isEmpty()) result += TrafficPlotPoint(group.first().time, null, connect = false)
            else {
                if (discontinuous && result.lastOrNull()?.value != null)
                    result += TrafficPlotPoint(group.first().time, null, connect = false)
                if (discontinuous && result.isEmpty()) result += TrafficPlotPoint(group.first().time, null, connect = false)
                val values = valid.map { it.metric(metric)!!.value }
                result += TrafficPlotPoint(valid[valid.size / 2].time, values.average(), values.minOrNull(), values.maxOrNull(),
                    origin(valid.map { it.metric(metric)!!.origin }), connect = !discontinuous)
            }
        }
        return result
    }

    fun groupedEvents(events: List<TrafficEvent>, start: Long, end: Long, limit: Int = 12): List<TrafficEvent> {
        val width = max(1, (end - start) / limit.coerceAtLeast(1))
        return events.groupBy { (it.start - start) / width }.values.map { group ->
            val first = group.first()
            first.copy(end = group.maxOf { it.end }, observations = group.sumOf { it.observations },
                members = group, detail = group.mapNotNull { it.detail }.distinct().take(4).joinToString("; ").ifEmpty { null })
        }.take(limit)
    }

    internal fun events(rows: List<HistoryEntry>, start: Long, end: Long): List<TrafficEvent> {
        val events = mutableListOf<TrafficEvent>()
        var state: String? = null
        var activeAlerts = emptySet<String>()
        var lossEnd = Long.MIN_VALUE
        var obstructionEnd = Long.MIN_VALUE
        rows.filter { it.timestamp >= start }.distinctBy {
            listOf(it.timestamp, it.kind, it.label, it.dishState, it.dropPercent, it.obstructionPercent)
        }.forEach { row ->
            val support = if (row.kind == "beacon_history") 5_000L else if (row.kind == "terminal_history") 1_000L else 2_000L
            val b = min(end, row.timestamp + support)
            if (row.kind == "reading" || row.kind == "beacon_history") {
                val alerts = row.activeAlerts.orEmpty().split('|').filter { it.isNotBlank() }.toSet()
                (alerts - activeAlerts).forEach { events += TrafficEvent(row.timestamp, b, TrafficEventKind.ALERT, it) }
                activeAlerts = alerts
            }
            if (row.dropPercent.isFinite() && row.dropPercent >= ELEVATED_LOSS_PERCENT && row.timestamp >= lossEnd) {
                events += TrafficEvent(row.timestamp, b, TrafficEventKind.LOSS); lossEnd = b
            }
            if (row.dishState != null && row.dishState != state) {
                if (row.dishState != "UNKNOWN") events += TrafficEvent(row.timestamp, b, when (row.dishState) {
                    "NO_DOWNLINK" -> TrafficEventKind.DOWNLINK; "UNREACHABLE" -> TrafficEventKind.UNREACHABLE
                    "CONNECTED" -> if (state != null) TrafficEventKind.RECONNECT else TrafficEventKind.STATE; "OBSTRUCTED" -> TrafficEventKind.OBSTRUCTION
                    else -> TrafficEventKind.STATE
                }, row.dishState)
                state = row.dishState
            }
            if (row.kind != "terminal_history" && row.obstructionPercent.isFinite() && row.obstructionPercent > 0 && row.timestamp >= obstructionEnd) {
                events += TrafficEvent(row.timestamp, b, TrafficEventKind.OBSTRUCTION); obstructionEnd = b
            }
            if (row.kind == "event") {
                val parsed = HistoryEventCodec.parse(row.label)
                val kind = when {
                    parsed.type == CanonicalHistoryEventType.CONNECTION_RESTORED -> TrafficEventKind.RECONNECT
                    parsed.type == CanonicalHistoryEventType.CONNECTION_LOST -> TrafficEventKind.UNREACHABLE
                    parsed.state == io.github.strongsand.lshell.core.model.DishState.NO_DOWNLINK -> TrafficEventKind.DOWNLINK
                    parsed.state == io.github.strongsand.lshell.core.model.DishState.OBSTRUCTED || parsed.alert == HistoryAlert.OBSTRUCTED -> TrafficEventKind.OBSTRUCTION
                    parsed.type == CanonicalHistoryEventType.PACKET_LOSS || parsed.alert == HistoryAlert.PACKET_LOSS -> TrafficEventKind.LOSS
                    parsed.type == CanonicalHistoryEventType.STATE_TRANSITION -> TrafficEventKind.STATE
                    else -> TrafficEventKind.ALERT
                }
                events += TrafficEvent(row.timestamp, row.timestamp, kind, row.label)
            }
        }
        // Collapse continuing conditions; markers/details remain bounded separately.
        val merged = mutableListOf<TrafficEvent>()
        events.sortedWith(compareBy<TrafficEvent> { it.kind.ordinal }.thenBy { it.start }).forEach { next ->
            val last = merged.lastOrNull()
            if (last != null && last.kind == next.kind && last.detail == next.detail && next.start <= last.end)
                merged[merged.lastIndex] = last.copy(end = max(last.end, next.end), observations = last.observations + 1)
            else merged += next
        }
        return merged.sortedBy { it.start }
    }

    private fun observedOutages(rows: List<HistoryEntry>, start: Long, end: Long): List<Pair<Long, Long>> = mergeRanges(
        rows.filter { it.dishState in outageStates }.map { row ->
            max(start, row.timestamp) to min(end, row.timestamp + if (row.kind == "beacon_history") 5_000 else 2_000)
        }.filter { it.second > it.first }.sortedBy { it.first })

    private fun mergeRanges(ranges: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val result = mutableListOf<Pair<Long, Long>>()
        ranges.forEach { next ->
            val last = result.lastOrNull()
            if (last != null && next.first <= last.second) result[result.lastIndex] = last.first to max(last.second, next.second)
            else result += next
        }
        return result
    }
    fun origin(origins: List<TrafficOrigin>): TrafficOrigin {
        val values = origins.filter { it != TrafficOrigin.UNKNOWN }.toSet()
        return when { values.isEmpty() -> TrafficOrigin.UNKNOWN; values.size > 1 -> TrafficOrigin.MIXED; else -> values.first() }
    }
}
