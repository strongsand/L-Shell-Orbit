package io.github.strongsand.lshell

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Read-only presentation decisions; never replaces the report engine or persisted decisions. */
internal object HealthReportUiPolicy {
    fun validChanges(report: HealthReport) = report.changes.filter {
        it.delta?.isFinite() == true && it.trend != HealthTrend.INSUFFICIENT
    }
    fun stability(report: HealthReport): HealthTrend? = report.changes
        .firstOrNull { it.metric == "stability" && it.trend != HealthTrend.INSUFFICIENT }?.trend
    fun chartPoints(values: List<Double?>) = values.count { it?.isFinite() == true }
    fun showChart(values: List<Double?>) = chartPoints(values) >= 3
    fun lowCoverage(coverage: Double) = coverage < 40
    fun twoColumns(widthDp: Float, fontScale: Float, minimumWidthDp: Float = 360f) = widthDp / fontScale.coerceAtLeast(1f) >= minimumWidthDp
    fun scheduledValue(groups: List<HealthTests>, download: Boolean): Double? {
        val scheduled = groups.filter { it.origin == TestOrigin.SCHEDULED }
        fun metric(g: HealthTests) = if (download) g.download else g.upload
        if (scheduled.none { it.days.size >= 2 && metric(it).count >= 3 }) return null
        return scheduled.fold(HealthDistribution()) { a, g -> a.merge(metric(g)) }.percentile(.5)
    }
    fun periodLabel(period: HealthPeriod, locale: Locale): String {
        val zone = ZoneId.of(period.zone)
        val start = Instant.ofEpochMilli(period.start).atZone(zone).toLocalDate()
        val end = Instant.ofEpochMilli(period.end - 1).atZone(zone).toLocalDate()
        fun format(pattern: String, date: java.time.LocalDate) = date.format(DateTimeFormatter.ofPattern(pattern, locale))
        return when {
            period.kind == HealthPeriodKind.MONTH -> format("MMMM yyyy", start).replaceFirstChar { it.titlecase(locale) }
            start.year != end.year -> "${format("d MMM yyyy", start)} – ${format("d MMM yyyy", end)}"
            start.month != end.month -> "${format("d MMM", start)} – ${format("d MMM yyyy", end)}"
            else -> "${start.dayOfMonth}–${format("d MMM yyyy", end)}"
        }
    }
    /** Same persisted stability decision and coverage; no raw queries or quantile decoding. */
    fun listingSummary(json: JSONObject): Pair<Double?, HealthTrend?> {
        val aggregate = json.optJSONObject("aggregate")
        val duration = json.optLong("through") - json.optLong("start")
        val observed = aggregate?.optLong("observed") ?: json.optJSONArray("days")?.let { days ->
            (0 until days.length()).sumOf { days.getJSONObject(it).optLong("observed") }
        }
        val coverage = observed?.takeIf { duration > 0 }?.let { (it * 100.0 / duration).coerceIn(0.0, 100.0) }
        val changes = json.optJSONArray("changes")
        val trend = changes?.let { values -> (0 until values.length()).asSequence().map { values.getJSONObject(it) }
            .firstOrNull { it.optString("metric") == "stability" }?.optString("trend") }
            ?.let { name -> HealthTrend.entries.firstOrNull { it.name == name && it != HealthTrend.INSUFFICIENT } }
        return coverage to trend
    }
}

internal data class HealthTestUiSummary(val origin: TestOrigin, val groups: List<HealthTests>) {
    val count = groups.sumOf { it.total }
    val succeeded = groups.sumOf { it.succeeded }
    val failed = groups.sumOf { it.failed }
    val interrupted = groups.sumOf { it.interrupted }
    val download = groups.fold(HealthDistribution()) { a, g -> a.merge(g.download) }
    val upload = groups.fold(HealthDistribution()) { a, g -> a.merge(g.upload) }
}
internal data class HealthReportUi(val prepared: PreparedHealthReport) {
    val report = prepared.report
    val total = prepared.total
    val changes = HealthReportUiPolicy.validChanges(report)
    val primaryChanges = changes.filter { it.metric in setOf("download", "upload", "latency", "availability", "outages") }
    val relevantEvents = total.eventCounts.filter { (kind, count) -> count > 0 && kind in setOf("DOWNLINK", "UNREACHABLE", "STATE") }
    val stability = HealthReportUiPolicy.stability(report)
    val tests = listOf(TestOrigin.SCHEDULED, TestOrigin.EXTREME, TestOrigin.SMART, TestOrigin.MANUAL, TestOrigin.UNKNOWN)
        .map { origin -> HealthTestUiSummary(origin, total.tests.filter { it.origin == origin }) }.filter { it.count > 0 }
    val scheduledDownload = HealthReportUiPolicy.scheduledValue(total.tests, true)
    val scheduledUpload = HealthReportUiPolicy.scheduledValue(total.tests, false)
    val bestLatencyWindow = ConnectionHealthAnalyzer.bestLatencyWindow(total)
    // Only existing evidence codes; never manufacture a trend or a causal statement.
    val insights = report.insights.distinct().filter { key ->
        key == "latency_streak" || key == "smart_bias" || changes.any { it.metric == key }
    }.take(5)
    val hasQuality = total.stateMs > 0 || total.metric("latency").count > 0 || total.metric("loss").count > 0
    val hasSpeed = tests.any { it.origin != TestOrigin.UNKNOWN }
    val hasEnergy = total.powerCoveredMs > 0 || total.eventCounts.isNotEmpty()
}
