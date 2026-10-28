package io.github.strongsand.lshell

import org.json.JSONArray
import org.json.JSONObject

/** Explicit JSON codecs: stable snapshots, no reflection or R8-dependent field names. */
internal object ConnectionHealthJson {
    private fun JSONObject.nullable(key: String) = optDouble(key).takeIf { it.isFinite() }
    fun distribution(d: HealthDistribution) = JSONObject().apply {
        put("count", d.count); put("sum", d.sum); put("min", d.minimum); put("max", d.maximum)
        put("bins", JSONArray().apply { d.bins.toSortedMap().forEach { (key, n) -> put(JSONArray().put(key).put(n)) } })
    }
    fun distribution(j: JSONObject): HealthDistribution {
        val a = j.getJSONArray("bins")
        return HealthDistribution((0 until a.length()).associate { a.getJSONArray(it).let { v -> v.getInt(0) to v.getLong(1) } },
            j.getLong("count"), j.getDouble("sum"), j.nullable("min"), j.nullable("max"))
    }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    fun day(d: HealthDay) = JSONObject().apply {
        put("start", d.start); put("end", d.end); put("rows", d.rawRows); put("observed", d.observedMs)
        put("state", d.stateMs); put("online", d.onlineMs); put("offline", d.offlineMs); put("outages", d.outages)
        put("longest_outage", d.longestOutageMs); put("reconnects", d.reconnects); put("loss_high", d.lossHighMs)
        put("power_covered", d.powerCoveredMs); put("watt_ms", d.powerWattMs)
        put("local", d.localValues); put("beacon", d.beaconValues); put("preserved", d.resolutionPreserved)
        put("leading_gap", d.leadingGapMs); put("trailing_gap", d.trailingGapMs); put("longest_gap", d.longestGapMs)
        put("leading_down", d.leadingDownMs); put("trailing_down", d.trailingDownMs)
        put("events", JSONObject(d.eventCounts))
        put("metric_coverage", JSONObject(d.metricCoveredMs))
        put("leading_online", d.leadingOnline); put("trailing_online", d.trailingOnline)
        put("stats", JSONObject().apply { d.stats.forEach { (key, v) -> put(key, distribution(v)) } })
        put("tests", JSONArray().apply { d.tests.forEach { t -> put(JSONObject().apply {
            put("origin", t.origin.name); put("window", t.window); put("total", t.total); put("success", t.succeeded)
            put("failed", t.failed); put("interrupted", t.interrupted); put("days", JSONArray(t.days.sorted()))
            put("download", distribution(t.download)); put("upload", distribution(t.upload))
        }) } })
        put("hours", JSONArray().apply { d.hours.forEach { h -> put(JSONObject().apply {
            put("window", h.window); put("days", JSONArray(h.days.sorted())); put("covered", h.coveredMs)
            put("latency_covered", h.latencyCoveredMs)
            put("latency", distribution(h.latency)); put("loss", distribution(h.loss))
        }) } })
    }
    fun day(j: JSONObject): HealthDay {
        val stats = j.getJSONObject("stats"); val tests = j.getJSONArray("tests"); val hours = j.getJSONArray("hours")
        return HealthDay(j.getLong("start"), j.getLong("end"), j.getInt("rows"), j.getLong("observed"), j.getLong("state"),
            j.getLong("online"), j.getLong("offline"), j.getInt("outages"), j.getLong("longest_outage"), j.getInt("reconnects"),
            j.getLong("loss_high"), j.getLong("power_covered"), j.getDouble("watt_ms"), j.getLong("local"), j.getLong("beacon"),
            stats.keys().asSequence().associateWith { distribution(stats.getJSONObject(it)) },
            (0 until tests.length()).map { tests.getJSONObject(it).let { t -> HealthTests(TestOrigin.valueOf(t.getString("origin")),
                t.getInt("window"), t.getInt("total"), t.getInt("success"), t.getInt("failed"), t.getInt("interrupted"),
                t.getJSONArray("days").strings().toSet(), distribution(t.getJSONObject("download")), distribution(t.getJSONObject("upload"))) } },
            (0 until hours.length()).map { hours.getJSONObject(it).let { h -> HealthHour(h.getInt("window"),
                h.getJSONArray("days").strings().toSet(), h.getLong("covered"), distribution(h.getJSONObject("latency")), distribution(h.getJSONObject("loss")), h.optLong("latency_covered")) } },
            j.getLong("leading_gap"), j.getLong("trailing_gap"), j.getLong("longest_gap"), j.getLong("leading_down"),
            j.getLong("trailing_down"), j.optBoolean("preserved"),
            j.optJSONObject("events")?.let { e -> e.keys().asSequence().associateWith { e.getInt(it) } }.orEmpty(),
            j.optJSONObject("metric_coverage")?.let { e -> e.keys().asSequence().associateWith { e.getLong(it) } }.orEmpty(),
            j.optBoolean("leading_online"), j.optBoolean("trailing_online"))
    }
    fun report(r: HealthReport) = JSONObject().apply {
        put("schema", r.schemaVersion); put("algorithm", r.algorithmVersion); put("kind", r.period.kind.name)
        put("start", r.period.start); put("end", r.period.end); put("zone", r.period.zone)
        put("generated", r.generatedAt); put("through", r.through); put("closed", r.closed)
        put("days", JSONArray().apply { r.days.forEach { put(day(it)) } })
        put("aggregate", day(r.total))
        put("confidence", r.confidence.name); put("grade", r.grade.name); put("previous_coverage", r.previousCoverage)
        put("comparable_tests", r.comparableTests); put("insights", JSONArray(r.insights))
        put("changes", JSONArray().apply { r.changes.forEach { c -> put(JSONObject().apply {
            put("metric", c.metric); put("delta", c.delta); put("trend", c.trend.name)
        }) } })
    }
    fun report(j: JSONObject): HealthReport {
        require(j.getInt("schema") == 1) { "Unsupported report schema" }
        val days = j.getJSONArray("days"); val changes = j.getJSONArray("changes")
        return HealthReport(HealthPeriod(HealthPeriodKind.valueOf(j.getString("kind")), j.getLong("start"), j.getLong("end"), j.getString("zone")),
            j.getLong("generated"), j.getLong("through"), j.getBoolean("closed"),
            (0 until days.length()).map { day(days.getJSONObject(it)) },
            (0 until changes.length()).map { changes.getJSONObject(it).let { c ->
                HealthChange(c.getString("metric"), c.nullable("delta"), HealthTrend.valueOf(c.getString("trend"))) } },
            HealthConfidence.valueOf(j.getString("confidence")), HealthGrade.valueOf(j.getString("grade")),
            j.nullable("previous_coverage"), j.getInt("comparable_tests"), j.getJSONArray("insights").strings(), j.getInt("algorithm"), j.getInt("schema"),
            j.optJSONObject("aggregate")?.let(::day))
    }
}
