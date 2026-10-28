package io.github.strongsand.lshell

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.*

internal enum class HealthPeriodKind { WEEK, MONTH }
internal data class HealthPeriod(val kind: HealthPeriodKind, val start: Long, val end: Long, val zone: String) {
    val key get() = "${kind.name}:$start:$end:$zone"
    fun previous(): HealthPeriod = containing(kind, start - 1, ZoneId.of(zone))
    fun days(until: Long = end): List<Pair<Long, Long>> {
        var date = Instant.ofEpochMilli(start).atZone(ZoneId.of(zone)).toLocalDate()
        return buildList {
            while (true) {
                val a = date.atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
                if (a >= min(end, until)) break
                val b = date.plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
                add(max(a, start) to min(b, min(end, until))); date = date.plusDays(1)
            }
        }
    }
    companion object {
        fun containing(kind: HealthPeriodKind, now: Long, zone: ZoneId): HealthPeriod {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val start = if (kind == HealthPeriodKind.WEEK) today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else today.withDayOfMonth(1)
            val end = if (kind == HealthPeriodKind.WEEK) start.plusWeeks(1) else start.plusMonths(1)
            fun millis(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
            return HealthPeriod(kind, millis(start), millis(end), zone.id)
        }
    }
}

/** Mergeable logarithmic distribution: <1% relative quantile error, exact count/sum/extrema.
 * Zero has a separate bucket. This is explicitly approximate, never a median of daily medians. */
internal data class HealthDistribution(
    val bins: Map<Int, Long> = emptyMap(), val count: Long = 0, val sum: Double = 0.0,
    val minimum: Double? = null, val maximum: Double? = null
) {
    val mean get() = if (count > 0) sum / count else null
    fun percentile(p: Double, minimumCount: Int = 1): Double? {
        require(p in 0.0..1.0)
        if (count < minimumCount) return null
        val rank = max(1L, ceil(count * p).toLong())
        var cumulative = 0L
        val bin = bins.toSortedMap().entries.first { cumulative += it.value; cumulative >= rank }.key
        return (if (bin == Int.MIN_VALUE) 0.0 else exp((bin + .5) * LN_BASE))
            .coerceIn(minimum!!, maximum!!)
    }
    fun merge(other: HealthDistribution): HealthDistribution {
        if (count == 0L) return other
        if (other.count == 0L) return this
        val merged = bins.toMutableMap()
        other.bins.forEach { (k, v) -> merged[k] = merged.getOrDefault(k, 0) + v }
        return HealthDistribution(merged, count + other.count, sum + other.sum,
            min(minimum!!, other.minimum!!), max(maximum!!, other.maximum!!))
    }
    companion object {
        private val LN_BASE = ln(1.02)
        fun of(values: Iterable<Double>): HealthDistribution {
            val bins = mutableMapOf<Int, Long>(); var count = 0L; var sum = 0.0
            var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
            values.forEach { value -> if (value.isFinite() && value >= 0) {
                val key = if (value == 0.0) Int.MIN_VALUE else floor(ln(value) / LN_BASE).toInt()
                bins[key] = bins.getOrDefault(key, 0) + 1; count++; sum += value
                lo = min(lo, value); hi = max(hi, value)
            } }
            return HealthDistribution(bins, count, sum, lo.takeIf { count > 0 }, hi.takeIf { count > 0 })
        }
    }
}

internal data class HealthTests(
    val origin: TestOrigin, val window: Int, val total: Int, val succeeded: Int,
    val failed: Int, val interrupted: Int, val days: Set<String>,
    val download: HealthDistribution, val upload: HealthDistribution
)
internal data class HealthHour(val window: Int, val days: Set<String>, val coveredMs: Long,
    val latency: HealthDistribution, val loss: HealthDistribution, val latencyCoveredMs: Long = 0)
internal data class HealthDay(
    val start: Long, val end: Long, val rawRows: Int, val observedMs: Long,
    val stateMs: Long, val onlineMs: Long, val offlineMs: Long, val outages: Int,
    val longestOutageMs: Long, val reconnects: Int, val lossHighMs: Long,
    val powerCoveredMs: Long, val powerWattMs: Double,
    val localValues: Long, val beaconValues: Long,
    val stats: Map<String, HealthDistribution>, val tests: List<HealthTests>, val hours: List<HealthHour>,
    val leadingGapMs: Long, val trailingGapMs: Long, val longestGapMs: Long,
    val leadingDownMs: Long, val trailingDownMs: Long, val resolutionPreserved: Boolean = false,
    val eventCounts: Map<String, Int> = emptyMap(),
    val metricCoveredMs: Map<String, Long> = emptyMap(),
    val leadingOnline: Boolean = false, val trailingOnline: Boolean = false
) {
    /** Calendar bounds/gaps alone are not observations. Retained aggregates survive raw compaction. */
    val hasPreservedData get() = rawRows > 0 || observedMs > 0 || stateMs > 0 || powerCoveredMs > 0 ||
        localValues > 0 || beaconValues > 0 || stats.values.any { it.count > 0 } ||
        tests.any { it.total > 0 } || eventCounts.values.any { it > 0 } ||
        metricCoveredMs.values.any { it > 0 } || hours.any {
            it.coveredMs > 0 || it.latencyCoveredMs > 0 || it.latency.count > 0 || it.loss.count > 0
        }
    val duration get() = end - start
    val coverage get() = if (duration > 0) observedMs * 100.0 / duration else 0.0
    val availability get() = if (stateMs > 0) onlineMs * 100.0 / stateMs else null
    val powerMean get() = if (powerCoveredMs > 0) powerWattMs / powerCoveredMs else null
    val wattHours get() = if (powerCoveredMs > 0) powerWattMs / 3_600_000 else null
    fun metric(key: String) = stats[key] ?: HealthDistribution()
    fun metricCoverage(key: String) = if (duration > 0) metricCoveredMs.getOrDefault(key, 0L) * 100.0 / duration else 0.0
}
internal enum class HealthTrend { IMPROVING, STABLE, WORSENING, INSUFFICIENT }
internal enum class HealthConfidence { LOW, MEDIUM, HIGH }
internal enum class HealthGrade { GOOD, FAIR, POOR, INSUFFICIENT }
internal data class HealthChange(val metric: String, val delta: Double?, val trend: HealthTrend)
internal data class HealthReport(
    val period: HealthPeriod, val generatedAt: Long, val through: Long, val closed: Boolean,
    val days: List<HealthDay>, val changes: List<HealthChange>, val confidence: HealthConfidence,
    val grade: HealthGrade, val previousCoverage: Double?, val comparableTests: Int,
    val insights: List<String>, val algorithmVersion: Int = 1, val schemaVersion: Int = 1,
    val aggregate: HealthDay? = null
) {
    val total get() = aggregate ?: ConnectionHealthAnalyzer.combine(days, period.start, through)
}
/** Immutable pair captured by deferred lazy content in a single composition. */
internal data class PreparedHealthReport(val report: HealthReport, val total: HealthDay) {
    companion object {
        fun from(report: HealthReport?): PreparedHealthReport? = report?.let { PreparedHealthReport(it, it.total) }
    }
}
internal data class HealthReportListing(val key: String, val period: HealthPeriod, val grade: HealthGrade, val generatedAt: Long,
    val coverage: Double? = null, val stability: HealthTrend? = null)

internal object HealthComparison {
    fun change(metric: String, a: Double?, b: Double?, lowerBetter: Boolean,
        relative: Boolean = false, absoluteThreshold: Double, relativeThreshold: Double = 0.0): HealthChange {
        if (a == null || b == null || (relative && b == 0.0)) return HealthChange(metric, null, HealthTrend.INSUFFICIENT)
        val delta = if (relative) (a - b) / b * 100 else a - b
        val significant = abs(a - b) >= absoluteThreshold &&
            (relativeThreshold == 0.0 || (b != 0.0 && abs((a - b) / b) >= relativeThreshold))
        return HealthChange(metric, delta, if (!significant) HealthTrend.STABLE
            else if ((a < b) == lowerBetter) HealthTrend.IMPROVING else HealthTrend.WORSENING)
    }
    fun confidence(day: HealthDay, observedDays: Int, expectedDays: Int, previous: HealthDay?): HealthConfidence = when {
        day.coverage >= 80 && observedDays >= ceil(expectedDays * .8).toInt() &&
            day.metric("latency").count >= 100 && day.metricCoverage("latency") >= 80 && day.longestGapMs < 6 * 3_600_000L &&
            previous != null && previous.coverage >= 80 -> HealthConfidence.HIGH
        day.coverage >= 40 && observedDays >= ceil(expectedDays * .5).toInt() &&
            day.metric("latency").count >= 20 -> HealthConfidence.MEDIUM
        else -> HealthConfidence.LOW
    }
    fun grade(day: HealthDay): HealthGrade {
        if (day.coverage < 40 || day.stateMs < (day.duration * .4) || day.metricCoverage("latency") < 40 || day.metricCoverage("loss") < 40 || day.metric("loss").count < 20 || day.metric("latency").count < 20)
            return HealthGrade.INSUFFICIENT
        val availability = day.availability ?: return HealthGrade.INSUFFICIENT
        val loss = day.metric("loss").mean!!
        val latency = day.metric("latency").percentile(.95, 20)!!
        return when {
            availability < 95 || loss >= 5 || latency >= 200 || day.outages * 86_400_000.0 / day.duration >= 20 || day.longestOutageMs >= 300_000 -> HealthGrade.POOR
            availability < 99 || loss >= 1 || latency >= 100 || day.outages * 86_400_000.0 / day.duration >= 5 -> HealthGrade.FAIR
            else -> HealthGrade.GOOD
        }
    }
    /** Balanced time strata, only SCHEDULED, >=3 valid tests on >=2 days in each matched stratum. */
    fun speed(current: HealthDay, previous: HealthDay?, metric: String): Pair<HealthChange, Int> {
        if (previous == null) return HealthChange(metric, null, HealthTrend.INSUFFICIENT) to 0
        fun valid(day: HealthDay) = day.tests.filter { it.origin == TestOrigin.SCHEDULED && it.days.size >= 2 }
            .associateBy { it.window }
        val a = valid(current); val b = valid(previous)
        var count = 0
        val changes = a.keys.intersect(b.keys).mapNotNull { window ->
            val x = a.getValue(window); val y = b.getValue(window)
            val xd = if (metric == "download") x.download else x.upload
            val yd = if (metric == "download") y.download else y.upload
            if (xd.count < 3 || yd.count < 3 || yd.percentile(.5)!! <= 0) return@mapNotNull null
            val change = change(metric, xd.percentile(.5), yd.percentile(.5), false, true,
                if (metric == "download") 5.0 else 1.0, .1)
            count += min(x.succeeded, y.succeeded)
            change
        }
        if (changes.isEmpty()) return HealthChange(metric, null, HealthTrend.INSUFFICIENT) to 0
        val delta = TrafficSessionAnalyzer.median(changes.mapNotNull { it.delta }.sorted())
        val trends = changes.map { it.trend }.toSet()
        val trend = if (trends.size == 1) trends.single() else HealthTrend.INSUFFICIENT
        return HealthChange(metric, delta, trend) to count
    }
    fun latencyStreak(current: HealthReport, history: List<HealthReport>): Boolean {
        var report = current
        repeat(3) {
            if (!report.closed || report.changes.none { it.metric == "latency" && it.trend == HealthTrend.IMPROVING }) return false
            if (it < 2) report = history.firstOrNull { candidate -> candidate.period == report.period.previous() } ?: return false
        }
        return true
    }
}
