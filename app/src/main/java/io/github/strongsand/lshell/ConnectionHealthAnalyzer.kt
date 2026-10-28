package io.github.strongsand.lshell

import io.github.strongsand.lshell.beacon.BeaconSampleSource
import java.time.Instant
import java.time.ZoneId
import kotlin.math.*

/** Uses the traffic monitor's exact independent-metric normalization and bounded support.
 * No new polling, no interpretation of zero traffic / missing samples as an outage. */
internal object ConnectionHealthAnalyzer {
    private val offlineStates = TrafficSessionAnalyzer.outageStates
    private data class ContextPoint(val time: Long, val state: String?, val stateSupport: Long,
        val power: Double?, val powerSupport: Long)
    fun day(rows: List<HistoryEntry>, tests: List<RecordedTest>, start: Long, end: Long, zone: ZoneId): HealthDay {
        val points = TrafficSessionAnalyzer.normalize(rows).filter { it.time >= start - 5_000 && it.time < end }
        val segments = TrafficMetric.entries.associateWith { TrafficSessionAnalyzer.segments(points, it, start, end) }
        val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate().toString()
        fun window(t: Long) = Instant.ofEpochMilli(t).atZone(zone).hour / 6
        val context = rows.filter { it.kind in setOf("reading", "terminal_history", "beacon_history") }
            .groupBy { it.timestamp / 1_000 }.map { (second, group) ->
                val ordered = group.sortedWith(compareByDescending<HistoryEntry> {
                    when (it.kind) { "terminal_history" -> 2; "beacon_history" -> 1; else -> 0 }
                }.thenByDescending { it.timestamp })
                val state = ordered.firstOrNull { it.dishState == "CONNECTED" || it.dishState in offlineStates }
                val power = ordered.firstOrNull { it.powerWatts?.let { w -> w.isFinite() && w > 0 } == true }
                fun support(row: HistoryEntry?) = when (row?.kind) { "terminal_history" -> 1_000L; "beacon_history" -> 5_000L; else -> 2_000L }
                ContextPoint(second * 1_000, state?.dishState, support(state), power?.powerWatts?.toDouble(), support(power))
            }.sortedBy { it.time }
        val states = mutableListOf<TrafficSegment>(); val powers = mutableListOf<TrafficSegment>()
        context.forEachIndexed { i, p ->
            val a = max(start, p.time); val next = context.getOrNull(i + 1)?.time ?: end
            val stateEnd = min(end, min(next, p.time + p.stateSupport))
            val powerEnd = min(end, min(next, p.time + p.powerSupport))
            if (p.state != null && stateEnd > a) states += TrafficSegment(a, stateEnd, if (p.state == "CONNECTED") 1.0 else 0.0)
            if (p.power != null && powerEnd > a) powers += TrafficSegment(a, powerEnd, p.power)
        }
        val outages = union(states.filter { it.value == 0.0 }.map { it.start to it.end })
        val observed = union((segments.values.flatten() + states + powers).map { it.start to it.end })
        val gaps = gaps(observed, start, end)
        val origins = points.filter { it.time >= start }.flatMap { listOfNotNull(it.download, it.upload, it.latency, it.loss) }
        val stats = TrafficMetric.entries.associate { metric ->
            metric.name.lowercase() to HealthDistribution.of(points.filter { it.time >= start }.mapNotNull { it.metric(metric)?.value })
        } + ("power" to HealthDistribution.of(context.filter { it.time >= start }.mapNotNull { it.power }))
        val testGroups = tests.filter { it.start >= start && it.start < end }.groupBy { it.origin to window(it.start) }.map { (key, values) ->
            val valid = values.filter { it.outcome == ConnectionTestPhase.COMPLETED.name }
            HealthTests(key.first, key.second, values.size, valid.size, values.count { it.outcome == "FAILED" },
                values.count { it.outcome == "INTERRUPTED" }, if (valid.isNotEmpty()) setOf(date) else emptySet(),
                HealthDistribution.of(valid.flatMap { it.downloads }), HealthDistribution.of(valid.flatMap { it.uploads }))
        }
        // Split at local six-hour boundaries, including DST transitions, rather than assuming a 24h day.
        val hours = (0..3).map { w ->
            val localDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val a = max(start, localDate.atTime(w * 6, 0).atZone(zone).toInstant().toEpochMilli())
            val b = min(end, if (w == 3) localDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                else localDate.atTime((w + 1) * 6, 0).atZone(zone).toInstant().toEpochMilli())
            val covered = observed.sumOf { (min(b, it.second) - max(a, it.first)).coerceAtLeast(0) }
            val latencyCovered = segments.getValue(TrafficMetric.LATENCY).sumOf { (min(b, it.end) - max(a, it.start)).coerceAtLeast(0) }
            HealthHour(w, if (latencyCovered > 0) setOf(date) else emptySet(), covered,
                HealthDistribution.of(points.filter { it.time >= a && it.time < b }.mapNotNull { it.latency?.value }),
                HealthDistribution.of(points.filter { it.time >= a && it.time < b }.mapNotNull { it.loss?.value }), latencyCovered)
        }
        val reconnects = states.zipWithNext().count { (a, b) -> a.value == 0.0 && b.value == 1.0 && a.end == b.start }
        return HealthDay(start, end, rows.count { it.timestamp in start until end }, observed.sumOf { it.second - it.first },
            states.sumOf { it.end - it.start }, states.filter { it.value == 1.0 }.sumOf { it.end - it.start },
            outages.sumOf { it.second - it.first }, outages.size, outages.maxOfOrNull { it.second - it.first } ?: 0,
            reconnects, segments.getValue(TrafficMetric.LOSS).filter { it.value >= 1.0 }.sumOf { it.end - it.start },
            powers.sumOf { it.end - it.start }, powers.sumOf { it.value * (it.end - it.start) },
            origins.count { it.origin == TrafficOrigin.LOCAL_PHONE }.toLong(), origins.count { it.origin == TrafficOrigin.BEACON }.toLong(),
            stats, testGroups, hours,
            gaps.firstOrNull()?.takeIf { it.first == start }?.let { it.second - it.first } ?: 0,
            gaps.lastOrNull()?.takeIf { it.second == end }?.let { it.second - it.first } ?: 0,
            gaps.maxOfOrNull { it.second - it.first } ?: 0,
            outages.firstOrNull()?.takeIf { it.first == start }?.let { it.second - it.first } ?: 0,
            outages.lastOrNull()?.takeIf { it.second == end }?.let { it.second - it.first } ?: 0,
            eventCounts = TrafficSessionAnalyzer.events(rows, start, end).groupingBy { it.kind.name }.eachCount(),
            metricCoveredMs = segments.mapKeys { it.key.name.lowercase() }.mapValues { (_, values) -> values.sumOf { it.end - it.start } },
            leadingOnline = states.firstOrNull()?.let { it.start == start && it.value == 1.0 } == true,
            trailingOnline = states.lastOrNull()?.let { it.end == end && it.value == 1.0 } == true)
    }
    internal fun union(input: List<Pair<Long, Long>>): List<Pair<Long, Long>> = buildList {
        input.filter { it.second > it.first }.sortedBy { it.first }.forEach { range ->
            val last = lastOrNull()
            if (last != null && range.first <= last.second) this[lastIndex] = last.first to max(last.second, range.second)
            else add(range)
        }
    }
    private fun gaps(ranges: List<Pair<Long, Long>>, start: Long, end: Long): List<Pair<Long, Long>> = buildList {
        var cursor = start
        ranges.forEach { (a, b) -> if (a > cursor) add(cursor to a); cursor = max(cursor, b) }
        if (cursor < end) add(cursor to end)
    }
    fun combine(days: List<HealthDay>, start: Long, end: Long): HealthDay {
        if (days.isEmpty()) return day(emptyList(), emptyList(), start, end, ZoneId.of("UTC"))
        val ordered = days.sortedBy { it.start }
        val stats = ordered.flatMap { it.stats.keys }.distinct().associateWith { key ->
            ordered.fold(HealthDistribution()) { sum, d -> sum.merge(d.metric(key)) }
        }
        val tests = ordered.flatMap { it.tests }.groupBy { it.origin to it.window }.map { (key, values) ->
            HealthTests(key.first, key.second, values.sumOf { it.total }, values.sumOf { it.succeeded },
                values.sumOf { it.failed }, values.sumOf { it.interrupted }, values.flatMap { it.days }.toSet(),
                values.fold(HealthDistribution()) { sum, v -> sum.merge(v.download) },
                values.fold(HealthDistribution()) { sum, v -> sum.merge(v.upload) })
        }
        val hours = ordered.flatMap { it.hours }.groupBy { it.window }.map { (window, values) ->
            HealthHour(window, values.flatMap { it.days }.toSet(), values.sumOf { it.coveredMs },
                values.fold(HealthDistribution()) { sum, v -> sum.merge(v.latency) },
                values.fold(HealthDistribution()) { sum, v -> sum.merge(v.loss) }, values.sumOf { it.latencyCoveredMs })
        }
        var longestGap = 0L; var gapTail = 0L; var longestDown = 0L; var downTail = 0L; var joins = 0; var reconnectJoins = 0
        ordered.forEachIndexed { i, d ->
            val adjacent = i > 0 && ordered[i - 1].end == d.start
            if (!adjacent) { gapTail = 0; downTail = 0 }
            longestGap = max(longestGap, max(d.longestGapMs, gapTail + d.leadingGapMs))
            longestDown = max(longestDown, max(d.longestOutageMs, downTail + d.leadingDownMs))
            if (adjacent && downTail > 0 && d.leadingDownMs > 0) joins++
            if (adjacent && downTail > 0 && d.leadingOnline) reconnectJoins++
            gapTail = if (d.leadingGapMs == d.duration) gapTail + d.duration else d.trailingGapMs
            downTail = if (d.leadingDownMs == d.duration) downTail + d.duration else d.trailingDownMs
        }
        fun leading(get: (HealthDay) -> Long): Long {
            var n = 0L
            for (d in ordered) { n += get(d); if (get(d) < d.duration) break }; return n
        }
        return HealthDay(start, end, ordered.sumOf { it.rawRows }, ordered.sumOf { it.observedMs },
            ordered.sumOf { it.stateMs }, ordered.sumOf { it.onlineMs }, ordered.sumOf { it.offlineMs },
            ordered.sumOf { it.outages } - joins, longestDown, ordered.sumOf { it.reconnects } + reconnectJoins, ordered.sumOf { it.lossHighMs },
            ordered.sumOf { it.powerCoveredMs }, ordered.sumOf { it.powerWattMs }, ordered.sumOf { it.localValues }, ordered.sumOf { it.beaconValues },
            stats, tests, hours, leading { it.leadingGapMs }, gapTail, longestGap, leading { it.leadingDownMs }, downTail,
            ordered.any { it.resolutionPreserved },
            ordered.flatMap { it.eventCounts.keys }.distinct().associateWith { key -> ordered.sumOf { it.eventCounts[key] ?: 0 } },
            ordered.flatMap { it.metricCoveredMs.keys }.distinct().associateWith { key -> ordered.sumOf { it.metricCoveredMs[key] ?: 0 } },
            ordered.first().leadingOnline, ordered.last().trailingOnline)
    }
    /** Preserve a captured prefix only when raw compaction removed evidence. Tail never overlaps it. */
    fun retainPrefix(cached: HealthDay, availablePrefix: HealthDay, tail: HealthDay?, complete: HealthDay): HealthDay {
        require(cached.start == availablePrefix.start && cached.end == availablePrefix.end)
        if (availablePrefix.rawRows >= cached.rawRows || availablePrefix.observedMs > cached.observedMs) return complete
        if (tail != null) require(tail.start == cached.end && tail.end == complete.end)
        return combine(listOfNotNull(cached, tail), complete.start, complete.end)
            .copy(tests = complete.tests, resolutionPreserved = true)
    }
    fun report(period: HealthPeriod, days: List<HealthDay>, previous: HealthReport?, now: Long, history: List<HealthReport>): HealthReport {
        val through = min(now, period.end)
        val total = combine(days, period.start, through)
        val prior = previous?.total
        // Provisional reports cannot compare half a week with a full previous week.
        val comparable = now >= period.end && prior != null && total.coverage >= 40 && prior.coverage >= 40 &&
            abs(total.coverage - prior.coverage) <= 20
        val speed = HealthComparison.speed(total, prior.takeIf { comparable }, "download")
        val upload = HealthComparison.speed(total, prior.takeIf { comparable }, "upload")
        fun metricComparable(key: String) = comparable && total.metricCoverage(key) >= 40 && prior!!.metricCoverage(key) >= 40 &&
            abs(total.metricCoverage(key) - prior.metricCoverage(key)) <= 20
        val enoughLatency = metricComparable("latency") && total.metric("latency").count >= 20 && prior!!.metric("latency").count >= 20
        val stateComparable = comparable && total.stateMs >= total.duration * .4 && prior!!.stateMs >= prior.duration * .4
        val enoughLoss = metricComparable("loss") && total.metric("loss").count >= 20 && prior!!.metric("loss").count >= 20
        fun previousMetric(key: String, ok: Boolean) = prior?.metric(key)?.takeIf { ok }
        val metricChanges = listOf(speed.first, upload.first,
            HealthComparison.change("latency", total.metric("latency").percentile(.5), previousMetric("latency", enoughLatency)?.percentile(.5), true,
                absoluteThreshold = 3.0, relativeThreshold = .1),
            HealthComparison.change("loss", total.metric("loss").mean, previousMetric("loss", enoughLoss)?.mean, true, absoluteThreshold = .5),
            HealthComparison.change("availability", total.availability, prior?.availability?.takeIf { stateComparable }, false, absoluteThreshold = .5),
            HealthComparison.change("outages", total.outages.toDouble(), prior?.outages?.toDouble()?.takeIf { stateComparable }, true, absoluteThreshold = 2.0),
            HealthComparison.change("offline", total.offlineMs.toDouble(), prior?.offlineMs?.toDouble()?.takeIf { stateComparable }, true, true, 60_000.0, .2),
            HealthComparison.change("power", total.powerMean, prior?.powerMean?.takeIf { comparable &&
                total.powerCoveredMs >= total.duration * .4 && prior.powerCoveredMs >= prior.duration * .4 }, false, absoluteThreshold = 5.0)
                .let { if (it.delta != null) it.copy(trend = HealthTrend.STABLE) else it }) // Power change is not inherently good/bad.
        val stability = metricChanges.filter { it.metric == "loss" || it.metric == "outages" }.map { it.trend }
        val stabilityTrend = when {
            HealthTrend.WORSENING in stability -> HealthTrend.WORSENING
            HealthTrend.INSUFFICIENT in stability -> HealthTrend.INSUFFICIENT
            HealthTrend.IMPROVING in stability -> HealthTrend.IMPROVING
            else -> HealthTrend.STABLE
        }
        val changes = metricChanges + HealthChange("stability", null, stabilityTrend)
        val observedDays = days.count { it.observedMs >= it.duration * .4 }
        val confidence = HealthComparison.confidence(total, observedDays, period.days(through).size.coerceAtLeast(1), prior)
        var report = HealthReport(period, now, through, now >= period.end, days, changes, confidence,
            HealthComparison.grade(total), prior?.coverage, speed.second, emptyList(), aggregate = total)
        val insights = buildList {
            if (HealthComparison.latencyStreak(report, history)) add("latency_streak")
            changes.filter { it.metric in setOf("download", "latency", "loss", "outages", "power") &&
                it.trend in setOf(HealthTrend.IMPROVING, HealthTrend.WORSENING) }.take(3).forEach { add(it.metric) }
            if (total.tests.any { it.origin == TestOrigin.SMART && it.total > 0 }) add("smart_bias")
        }.take(5)
        report = report.copy(insights = insights)
        return report
    }
    fun bestLatencyWindow(day: HealthDay): HealthHour? {
        val windows = day.hours.filter { it.days.size >= 3 && it.latencyCoveredMs >= 1_800_000 && it.latency.count >= 20 }
        if (windows.size < 2) return null
        val best = windows.minBy { it.latency.percentile(.5)!! }
        val worst = windows.maxBy { it.latency.percentile(.5)!! }
        return best.takeIf { HealthComparison.change("latency", best.latency.percentile(.5), worst.latency.percentile(.5),
            true, absoluteThreshold = 3.0, relativeThreshold = .1).trend == HealthTrend.IMPROVING }
    }
}
