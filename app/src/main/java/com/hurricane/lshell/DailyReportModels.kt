package com.hurricane.lshell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

data class ReportMetric(
    val count: Int,
    val minimum: Float,
    val maximum: Float,
    val average: Float,
    val median: Float,
    val p95: Float,
    val minimumAt: Long,
    val maximumAt: Long
)

data class ReportInterruption(
    val start: Long,
    val end: Long,
    val reason: String,
    val resolved: Boolean
) { val durationMs: Long get() = (end - start).coerceAtLeast(0L) }

data class ReportMoment(val timestamp: Long, val title: String, val detail: String = "")

enum class ReportBucketOrigin { NONE, LOCAL, BEACON, MIXED }

data class ReportChartBucket(
    val start: Long,
    val end: Long,
    val value: Float?,
    val sampleCount: Int,
    val localCount: Int,
    val beaconCount: Int
) {
    val origin: ReportBucketOrigin
        get() = when {
            sampleCount == 0 -> ReportBucketOrigin.NONE
            localCount > 0 && beaconCount > 0 -> ReportBucketOrigin.MIXED
            beaconCount > 0 -> ReportBucketOrigin.BEACON
            else -> ReportBucketOrigin.LOCAL
        }
}

data class ReportChartCoverage(val filled: Int, val total: Int) {
    val percent: Float get() = if (total == 0) 0f else filled.toFloat() / total * 100f
}

internal fun reportChartCoverage(values: List<ReportChartBucket>) = ReportChartCoverage(
    filled = values.count { it.value?.isFinite() == true },
    total = values.size
)

data class DailyReport(
    val start: Long,
    val end: Long,
    val createdAt: Long,
    val summary: String,
    val monitoredMs: Long,
    val onlineMs: Long,
    val offlineMs: Long,
    val availabilityPercent: Float?,
    val sampleCount: Int,
    val trafficDownload: ReportMetric?,
    val trafficUpload: ReportMetric?,
    val latency: ReportMetric?,
    val latencyAbove100Ms: Long,
    val packetLoss: ReportMetric?,
    val packetLossPeriods: Int,
    val packetLossDurationMs: Long,
    val obstruction: ReportMetric?,
    val interruptions: List<ReportInterruption>,
    val alerts: List<ReportMoment>,
    val hardwareEvents: List<ReportMoment>,
    val timeline: List<ReportMoment>,
    val latencyChart: List<ReportChartBucket>,
    val lossChart: List<ReportChartBucket>,
    val obstructionChart: List<ReportChartBucket>,
    val trafficChart: List<ReportChartBucket>,
    val formatVersion: Int = CURRENT_REPORT_FORMAT
)

internal const val CURRENT_REPORT_FORMAT = 2
internal const val REPORT_CHART_BUCKETS = 48

object DailyReportGenerator {
    fun generate(context: Context, start: Long, end: Long): DailyReport {
        val entries = HistoryStore(context).use { it.between(start, end) }
        val samples = entries.filter { it.kind == "reading" || it.kind == "terminal_history" || it.kind == "beacon_history" }
            .distinctBy { it.timestamp / 1_000L }
        val stateReadings = entries.filter { it.kind == "reading" ||
            (it.kind == "beacon_history" && it.dishState != null) }
        val events = entries.filter { it.kind == "event" }.sortedBy { it.timestamp }
        val interruptions = interruptions(events, end)
        val offlineMs = mergeDuration(interruptions.map { it.start to it.end })
        val onlineMs = observedCoverage(samples.map { it.timestamp })
        val monitoredMs = (onlineMs + offlineMs).coerceAtMost((end - start).coerceAtLeast(0L))
        val availability = if (monitoredMs > 0L)
            onlineMs.toDouble().div(monitoredMs).times(100.0).coerceIn(0.0, 100.0).toFloat() else null

        // These are traffic samples from the terminal, not speed-test capacity. Zero values are
        // excluded so an idle connection is never presented as the day's "minimum speed".
        val down = metric(samples.filter { it.downloadMbps.isFinite() && it.downloadMbps > 0f }
            .map { it.timestamp to it.downloadMbps })
        val up = metric(samples.filter { it.uploadMbps.isFinite() && it.uploadMbps > 0f }
            .map { it.timestamp to it.uploadMbps })
        val latencySamples = samples.filter { it.latencyMs.isFinite() && it.latencyMs > 0f }
        val latency = metric(latencySamples.map { it.timestamp to it.latencyMs })
        val lossSamples = samples.filter { it.dropPercent.isFinite() && it.dropPercent >= 0f }
        val loss = metric(lossSamples.map { it.timestamp to it.dropPercent })
        val obstruct = metric(stateReadings.filter { it.obstructionPercent.isFinite() }
            .map { it.timestamp to it.obstructionPercent.coerceAtLeast(0f) })

        val highLoss = lossSamples.filter { it.dropPercent >= 1f }
        val lossMoments = highLoss.groupByContiguous().map { period ->
            val peak = period.maxBy { it.dropPercent }
            ReportMoment(peak.timestamp, "Perda elevada de pacotes", "${one(peak.dropPercent)}%")
        }
        val alerts = events.filter { it.label.startsWith("Alerta:") }.map { alert ->
            val title = alert.label.removePrefix("Alerta: ")
            val resolved = events.firstOrNull { candidate ->
                candidate.timestamp >= alert.timestamp && candidate.label == "Alerta resolvido: $title"
            }
            ReportMoment(alert.timestamp, title, resolved?.let {
                "Resolvido após ${durationText(it.timestamp - alert.timestamp)}"
            } ?: "Sem resolução registrada")
        }
        val hardware = hardwareEvents(stateReadings)
        val timeline = buildList {
            interruptions.forEach {
                add(ReportMoment(it.start, "Conexão perdida", durationText(it.durationMs)))
                if (it.resolved) add(ReportMoment(it.end, "Conexão restaurada", "Falha durou ${durationText(it.durationMs)}"))
            }
            addAll(alerts)
            addAll(hardware)
            addAll(lossMoments)
            events.filter { it.label.startsWith("Teste Extremo") }.forEach {
                add(ReportMoment(it.timestamp, "Teste Extremo", it.label.substringAfter(": ", "Concluído")))
            }
            down?.let { add(ReportMoment(it.maximumAt, "Maior tráfego de download observado", "${one(it.maximum)} Mbps")) }
            latency?.takeIf { it.maximum >= 100f }?.let {
                add(ReportMoment(it.maximumAt, "Pico de latência", "${one(it.maximum)} ms"))
            }
            obstruct?.takeIf { it.maximum >= 0.1f }?.let {
                add(ReportMoment(it.maximumAt, "Maior obstrução", "${one(it.maximum)}%"))
            }
        }.distinctBy { Triple(it.timestamp, it.title, it.detail) }.sortedBy { it.timestamp }

        val summary = when {
            samples.isEmpty() -> "Não houve dados suficientes neste período"
            availability == null -> "O período teve monitoramento parcial"
            availability >= 99.9f && interruptions.isEmpty() && alerts.isEmpty() ->
                "Conexão estável durante o período monitorado"
            availability >= 99f -> "Conexão estável, com pequenas interrupções"
            availability >= 95f -> "Foram detectadas interrupções relevantes"
            else -> "A conexão apresentou instabilidade no período monitorado"
        }
        return DailyReport(
            start, end, System.currentTimeMillis(), summary, monitoredMs, onlineMs, offlineMs,
            availability, samples.size, down, up, latency,
            latencySamples.count { it.latencyMs > 100f } * 1_000L,
            loss, contiguousPeriods(highLoss.map { it.timestamp }), highLoss.size * 1_000L,
            obstruct, interruptions, alerts, hardware, timeline,
            chart(samples.filter { it.latencyMs.isFinite() && it.latencyMs > 0f }, start, end) { it.latencyMs },
            chart(lossSamples, start, end) { it.dropPercent },
            chart(stateReadings.filter { it.obstructionPercent.isFinite() }, start, end) { it.obstructionPercent },
            chart(samples.filter { it.downloadMbps.isFinite() }, start, end) { it.downloadMbps }
        )
    }

    private fun metric(values: List<Pair<Long, Float>>): ReportMetric? {
        if (values.isEmpty()) return null
        val sorted = values.sortedBy { it.second }
        fun q(fraction: Float): Float = sorted[((sorted.lastIndex * fraction).roundToLong().toInt())
            .coerceIn(0, sorted.lastIndex)].second
        val min = sorted.first()
        val max = sorted.last()
        return ReportMetric(values.size, min.second, max.second,
            values.sumOf { it.second.toDouble() }.div(values.size).toFloat(), q(.5f), q(.95f),
            min.first, max.first)
    }

    private fun interruptions(events: List<HistoryEntry>, reportEnd: Long): List<ReportInterruption> {
        val result = mutableListOf<ReportInterruption>()
        events.forEachIndexed { index, event ->
            if (!event.label.contains("conexão com a antena perdida", ignoreCase = true)) return@forEachIndexed
            val recovery = events.drop(index + 1).firstOrNull {
                it.label.contains("restabelecida", ignoreCase = true) ||
                    it.label.contains("restaurada", ignoreCase = true)
            }
            result += ReportInterruption(event.timestamp, recovery?.timestamp ?: reportEnd,
                "Conexão perdida", recovery != null)
        }
        return result
    }

    private fun hardwareEvents(readings: List<HistoryEntry>): List<ReportMoment> {
        val result = mutableListOf<ReportMoment>()
        var previousUptime: Long? = null
        var previousEthernet: Int? = null
        readings.sortedBy { it.timestamp }.forEach { entry ->
            val uptime = entry.uptimeSeconds
            if (uptime != null && previousUptime != null && uptime + 60L < previousUptime!!)
                result += ReportMoment(entry.timestamp, "Dishy reiniciada")
            if (uptime != null) previousUptime = uptime
            val ethernet = entry.ethernetMbps
            if (ethernet != null && previousEthernet != null && ethernet != previousEthernet)
                result += ReportMoment(entry.timestamp, "Velocidade Ethernet alterada", "$ethernet Mbps")
            if (ethernet != null) previousEthernet = ethernet
        }
        return result
    }

    private fun observedCoverage(timestamps: List<Long>): Long {
        val times = timestamps.distinctBy { it / 1_000L }.sorted()
        if (times.isEmpty()) return 0L
        return times.indices.sumOf { index ->
            val gap = if (index < times.lastIndex) times[index + 1] - times[index] else 1_000L
            if (gap in 0L..5_000L) gap.coerceAtLeast(1_000L) else 1_000L
        }
    }

    private fun mergeDuration(ranges: List<Pair<Long, Long>>): Long {
        if (ranges.isEmpty()) return 0L
        val sorted = ranges.sortedBy { it.first }
        var start = sorted.first().first
        var end = sorted.first().second
        var total = 0L
        sorted.drop(1).forEach { (nextStart, nextEnd) ->
            if (nextStart <= end) end = maxOf(end, nextEnd)
            else { total += (end - start).coerceAtLeast(0L); start = nextStart; end = nextEnd }
        }
        return total + (end - start).coerceAtLeast(0L)
    }

    private fun contiguousPeriods(timestamps: List<Long>): Int {
        val sorted = timestamps.distinctBy { it / 1_000L }.sorted()
        if (sorted.isEmpty()) return 0
        return 1 + sorted.zipWithNext().count { (a, b) -> b - a > 5_000L }
    }

    private fun List<HistoryEntry>.groupByContiguous(): List<List<HistoryEntry>> {
        if (isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<HistoryEntry>>()
        sortedBy { it.timestamp }.forEach { entry ->
            val current = groups.lastOrNull()
            if (current == null || entry.timestamp - current.last().timestamp > 5_000L)
                groups += mutableListOf(entry)
            else current += entry
        }
        return groups
    }

    private fun chart(
        entries: List<HistoryEntry>,
        start: Long,
        end: Long,
        value: (HistoryEntry) -> Float
    ): List<ReportChartBucket> = aggregateReportChart(entries, start, end, value)
}

internal fun aggregateReportChart(
    entries: List<HistoryEntry>,
    start: Long,
    end: Long,
    value: (HistoryEntry) -> Float
): List<ReportChartBucket> {
        val finiteEntries = entries.filter { value(it).isFinite() }
        val width = ((end - start).coerceAtLeast(REPORT_CHART_BUCKETS.toLong()) /
            REPORT_CHART_BUCKETS).coerceAtLeast(1L)
        return (0 until REPORT_CHART_BUCKETS).map { index ->
            val from = start + index * width
            val until = if (index == REPORT_CHART_BUCKETS - 1) end else from + width
            val bucketEntries = finiteEntries.filter { it.timestamp >= from && it.timestamp < until }
            ReportChartBucket(
                start = from,
                end = until,
                value = bucketEntries.map(value).takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                sampleCount = bucketEntries.size,
                localCount = bucketEntries.count { it.source == com.hurricane.lshell.beacon.BeaconSampleSource.LOCAL_PHONE },
                beaconCount = bucketEntries.count { it.source == com.hurricane.lshell.beacon.BeaconSampleSource.BEACON }
            )
        }
}

private fun one(value: Float) = String.format(java.util.Locale.getDefault(), "%.1f", value)
private fun durationText(ms: Long): String = when {
        ms < 60_000L -> "${ms / 1_000L} s"
        ms < 3_600_000L -> "${ms / 60_000L} min ${ms / 1_000L % 60L} s"
        else -> "${ms / 3_600_000L} h ${ms / 60_000L % 60L} min"
}

class DailyReportStore internal constructor(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "daily_reports"))

    init { directory.mkdirs() }

    fun save(report: DailyReport) {
        val destination = File(directory, "report_${report.end}.json")
        val temporary = File(directory, "report_${report.end}.tmp")
        temporary.writeText(report.toJson().toString())
        if (!temporary.renameTo(destination)) {
            destination.writeText(temporary.readText())
            temporary.delete()
        }
        staleFile(report.end).delete()
    }

    fun get(end: Long): DailyReport? = runCatching {
        File(directory, "report_$end.json").takeIf { it.exists() }?.readText()?.let {
            reportFromJson(JSONObject(it))
        }
    }.getOrNull()

    fun all(): List<DailyReport> = directory.listFiles { file ->
        file.name.startsWith("report_") && file.extension == "json"
    }.orEmpty().mapNotNull { runCatching { reportFromJson(JSONObject(it.readText())) }.getOrNull() }
        .sortedByDescending { it.end }

    fun markStaleForRange(start: Long, endExclusive: Long) {
        if (endExclusive <= start) return
        affectedReportEnds(all(), start, endExclusive)
            .forEach { staleFile(it).writeText("beacon_history") }
    }

    fun isStale(report: DailyReport): Boolean =
        report.formatVersion < CURRENT_REPORT_FORMAT || staleFile(report.end).exists()

    fun refreshIfStale(end: Long, generator: (start: Long, end: Long) -> DailyReport): DailyReport? {
        val existing = get(end) ?: return null
        val refreshed = refreshReportIfStale(existing, isStale(existing), generator)
        if (refreshed !== existing) save(refreshed)
        return refreshed
    }

    fun prune(keepHistory: Boolean, retentionDays: Int?) {
        val reports = all()
        if (!keepHistory) reports.drop(1).forEach { delete(it.end) }
        else if (retentionDays != null) {
            val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
            reports.filter { it.end < cutoff }.forEach { delete(it.end) }
        }
    }

    private fun delete(end: Long) {
        File(directory, "report_$end.json").delete()
        staleFile(end).delete()
    }

    private fun staleFile(end: Long) = File(directory, "report_$end.stale")
}

internal fun reportRangesOverlap(reportStart: Long, reportEnd: Long, dataStart: Long, dataEnd: Long): Boolean =
    reportStart < dataEnd && dataStart < reportEnd

internal fun affectedReportEnds(reports: List<DailyReport>, dataStart: Long, dataEnd: Long): List<Long> =
    reports.filter { reportRangesOverlap(it.start, it.end, dataStart, dataEnd) }.map { it.end }

internal fun refreshReportIfStale(
    report: DailyReport,
    stale: Boolean,
    generator: (start: Long, end: Long) -> DailyReport
): DailyReport = if (stale || report.formatVersion < CURRENT_REPORT_FORMAT)
    generator(report.start, report.end) else report

private fun DailyReport.toJson() = JSONObject().apply {
    put("formatVersion", formatVersion)
    put("start", start); put("end", end); put("created", createdAt); put("summary", summary)
    put("monitored", monitoredMs); put("online", onlineMs); put("offline", offlineMs)
    put("availability", availabilityPercent ?: JSONObject.NULL); put("samples", sampleCount)
    put("down", trafficDownload?.toJson() ?: JSONObject.NULL)
    put("up", trafficUpload?.toJson() ?: JSONObject.NULL)
    put("latency", latency?.toJson() ?: JSONObject.NULL); put("latencyHigh", latencyAbove100Ms)
    put("loss", packetLoss?.toJson() ?: JSONObject.NULL); put("lossPeriods", packetLossPeriods)
    put("lossDuration", packetLossDurationMs); put("obstruction", obstruction?.toJson() ?: JSONObject.NULL)
    put("interruptions", JSONArray().apply { interruptions.forEach { put(it.toJson()) } })
    put("alerts", momentsToJson(alerts)); put("hardware", momentsToJson(hardwareEvents))
    put("timeline", momentsToJson(timeline)); put("latencyChart", chartToJson(latencyChart))
    put("lossChart", chartToJson(lossChart)); put("obstructionChart", chartToJson(obstructionChart))
    put("trafficChart", chartToJson(trafficChart))
}

private fun ReportMetric.toJson() = JSONObject().apply {
    put("count", count); put("min", minimum.toDouble()); put("max", maximum.toDouble())
    put("avg", average.toDouble()); put("median", median.toDouble()); put("p95", p95.toDouble())
    put("minAt", minimumAt); put("maxAt", maximumAt)
}
private fun ReportInterruption.toJson() = JSONObject().apply {
    put("start", start); put("end", end); put("reason", reason); put("resolved", resolved)
}
private fun ReportMoment.toJson() = JSONObject().apply {
    put("time", timestamp); put("title", title); put("detail", detail)
}
private fun momentsToJson(values: List<ReportMoment>) = JSONArray().apply { values.forEach { put(it.toJson()) } }
private fun chartToJson(values: List<ReportChartBucket>) = JSONArray().apply {
    values.forEach { bucket -> put(JSONObject().apply {
        put("start", bucket.start); put("end", bucket.end)
        put("value", bucket.value?.toDouble() ?: JSONObject.NULL)
        put("count", bucket.sampleCount); put("local", bucket.localCount); put("beacon", bucket.beaconCount)
    }) }
}

private fun reportFromJson(json: JSONObject) = DailyReport(
    json.getLong("start"), json.getLong("end"), json.getLong("created"), json.getString("summary"),
    json.getLong("monitored"), json.getLong("online"), json.getLong("offline"),
    json.optDouble("availability").takeUnless { it.isNaN() }?.toFloat(), json.getInt("samples"),
    json.optJSONObject("down")?.toMetric(), json.optJSONObject("up")?.toMetric(),
    json.optJSONObject("latency")?.toMetric(), json.optLong("latencyHigh"),
    json.optJSONObject("loss")?.toMetric(), json.optInt("lossPeriods"), json.optLong("lossDuration"),
    json.optJSONObject("obstruction")?.toMetric(), json.getJSONArray("interruptions").mapObjects {
        ReportInterruption(it.getLong("start"), it.getLong("end"), it.getString("reason"), it.getBoolean("resolved"))
    }, json.getJSONArray("alerts").moments(), json.getJSONArray("hardware").moments(),
    json.getJSONArray("timeline").moments(), json.getJSONArray("latencyChart").chart(),
    json.getJSONArray("lossChart").chart(), json.getJSONArray("obstructionChart").chart(),
    json.getJSONArray("trafficChart").chart(), json.optInt("formatVersion", 1)
)

private fun JSONObject.toMetric() = ReportMetric(getInt("count"), getDouble("min").toFloat(),
    getDouble("max").toFloat(), getDouble("avg").toFloat(), getDouble("median").toFloat(),
    getDouble("p95").toFloat(), getLong("minAt"), getLong("maxAt"))
private fun JSONArray.moments() = mapObjects {
    ReportMoment(it.getLong("time"), it.getString("title"), it.optString("detail"))
}
private fun JSONArray.chart() = (0 until length()).map { index ->
    optJSONObject(index)?.let { bucket ->
        ReportChartBucket(
            bucket.optLong("start"), bucket.optLong("end"),
            bucket.optDouble("value").takeUnless { it.isNaN() }?.toFloat(),
            bucket.optInt("count"), bucket.optInt("local"), bucket.optInt("beacon")
        )
    } ?: ReportChartBucket(0L, 0L,
        if (isNull(index)) null else getDouble(index).toFloat(),
        if (isNull(index)) 0 else 1, if (isNull(index)) 0 else 1, 0)
}
private inline fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> =
    (0 until length()).map { block(getJSONObject(it)) }
