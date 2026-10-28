package io.github.strongsand.lshell

import org.json.JSONArray
import org.json.JSONObject

object TrafficSessionExport {
    fun json(session: TrafficSession, analysis: TrafficAnalysis, appVersion: String): String =
        buildString { writeJson(this, session, analysis, appVersion) }

    /** Stream observations without constructing a second in-memory telemetry tree. */
    fun writeJson(out: Appendable, session: TrafficSession, analysis: TrafficAnalysis, appVersion: String) {
        val header = manifest(session, analysis, appVersion)
        header.put("statistics", JSONObject().apply {
            TrafficMetric.entries.forEach { metric -> put(metric.name.lowercase(java.util.Locale.ROOT), summary(analysis.summary(metric))) }
            put("power", summary(analysis.power))
        })
        out.append(header.toString().dropLast(1)).append(",\"samples\":[")
        analysis.points.forEachIndexed { index, p ->
            if (index > 0) out.append(',')
            out.append(JSONObject().put("timestamp_ms", p.time).apply {
                TrafficMetric.entries.forEach { m -> put(m.name.lowercase(java.util.Locale.ROOT), p.metric(m)?.let {
                    JSONObject().put("value", it.value).put("source", it.origin.name).put("support_ms", it.supportMs)
                } ?: JSONObject.NULL) }
                put("power", p.power?.let {
                    JSONObject().put("value", it.value).put("source", it.origin.name).put("support_ms", it.supportMs)
                } ?: JSONObject.NULL)
            }.toString())
        }
        out.append("],\"events\":[")
        analysis.events.forEachIndexed { index, event ->
            if (index > 0) out.append(',')
            out.append(JSONObject().put("start_ms", event.start).put("end_ms", event.end).put("type", event.kind.name)
                .put("detail", event.detail ?: JSONObject.NULL).put("observations", event.observations).toString())
        }
        out.append("]}")
    }

    fun manifest(session: TrafficSession, a: TrafficAnalysis, appVersion: String) = JSONObject().apply {
        put("type", "traffic_session"); put("format_version", 1); put("app_version", appVersion)
        put("session", TrafficSessionMetadata.json(session)); put("period_start_ms", a.start)
        put("period_end_exclusive_ms", a.end); put("timezone", session.timezone); put("source", a.origin.name)
        put("coverage_percent", a.coveragePercent); put("covered_ms", a.coveredMs)
        put("gaps", JSONArray().apply { a.gaps.forEach { put(JSONObject().put("start_ms", it.start).put("end_ms", it.end)) } })
        put("bytes_method", "bounded_step_integral_of_observed_mbps_estimate")
        put("active_threshold_mbps", TrafficSessionAnalyzer.ACTIVE_MBPS)
        put("observed_outage_ms", a.downtimeMs); put("observed_outage_episodes", a.interruptionCount)
        put("sampling_note", "1s terminal history, 5s current Beacon stride, 2s status; no hold across longer gaps")
    }
    private fun summary(s: TrafficSummary) = JSONObject().apply {
        put("sample_count", s.count); put("current", s.latest ?: JSONObject.NULL)
        put("mean", s.mean ?: JSONObject.NULL); put("median", s.median ?: JSONObject.NULL)
        put("instant_peak", s.peak ?: JSONObject.NULL); put("useful_minimum", s.usefulMinimum ?: JSONObject.NULL)
        put("sustained_30s", s.sustained30s ?: JSONObject.NULL); put("sustained_1m", s.sustained1m ?: JSONObject.NULL)
        put("sustained_5m", s.sustained5m ?: JSONObject.NULL); put("estimated_bytes", s.estimatedBytes ?: JSONObject.NULL)
        put("active_ms", s.activeMs); put("covered_ms", s.coveredMs)
    }
    fun csv(a: TrafficAnalysis): String = buildString { writeCsv(this, a) }
    fun writeCsv(out: Appendable, a: TrafficAnalysis) {
        out.append("timestamp_ms,download_mbps,download_source,upload_mbps,upload_source,latency_ms,loss_percent,power_watts,power_source\r\n")
        a.points.forEach { p -> out.append("${p.time},${p.download?.value ?: ""},${p.download?.origin?.name ?: ""},${p.upload?.value ?: ""},${p.upload?.origin?.name ?: ""},${p.latency?.value ?: ""},${p.loss?.value ?: ""},${p.power?.value ?: ""},${p.power?.origin?.name ?: ""}\r\n") }
        out.append("\r\nevent,start_ms,end_ms,observations,detail\r\n")
        a.events.forEach { out.append("${it.kind.name},${it.start},${it.end},${it.observations},${csvCell(it.detail.orEmpty())}\r\n") }
    }
}
