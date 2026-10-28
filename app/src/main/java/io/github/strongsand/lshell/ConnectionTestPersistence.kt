package io.github.strongsand.lshell

import org.json.JSONArray
import org.json.JSONObject

/** Execution recovery metadata only. The existing extreme-test archive format is unchanged. */
internal fun ExtremeSession.executionJson() = JSONObject().apply {
    put("id", id); put("requested", requestedTests); put("start", startedAt); put("end", endedAt)
    put("runs", JSONArray().apply { runs.forEach { run -> put(JSONObject().apply {
        put("index", run.index); put("time", run.timestamp)
        putFinite("down", run.downloadMbps); putFinite("up", run.uploadMbps)
        putFinite("latency", run.latencyMs); putFinite("loss", run.packetLossPercent)
        putFinite("obstruction", run.obstructionPercent); putFinite("power", run.powerWatts)
        put("state", run.dishState); put("alerts", JSONArray(run.alerts))
        put("duration", run.durationMs); put("bytes", run.estimatedBytes)
        put("failure", run.failure ?: JSONObject.NULL)
        put("download_measured", run.downloadMeasured ?: JSONObject.NULL)
        put("upload_measured", run.uploadMeasured ?: JSONObject.NULL)
    }) } })
}

private fun JSONObject.putFinite(key: String, value: Float?) {
    put(key, value?.takeIf { it.isFinite() }?.toDouble() ?: JSONObject.NULL)
}

/** Historical detail only: never used to resume a service/RPC. Missing measurements stay null. */
internal fun historicalTestDetail(state: ConnectionTestState) = JSONObject().apply {
    put("id", state.id); put("kind", state.kind.name); put("phase", state.phase.name); put("origin", state.origin.name)
    put("start", state.startedAt); put("end", state.endedAt); put("total", state.total); put("completed", state.completed)
    state.error?.let { put("error", it) }
    state.result?.let { r -> put("result", JSONObject().apply {
        putFinite("down", r.downloadMbps); putFinite("up", r.uploadMbps); putFinite("latency", r.latencyMs)
        put("down_measured", r.downloadMeasured); put("up_measured", r.uploadMeasured)
    }) }
    if (state.runs.isNotEmpty()) put("extreme", ExtremeSession(state.id, state.total, state.startedAt, state.endedAt, state.runs).executionJson())
}

internal fun executionSessionFromJson(json: JSONObject): ExtremeSession {
    val array = json.getJSONArray("runs")
    val runs = (0 until array.length()).map { index ->
        val run = array.getJSONObject(index)
        val alerts = run.getJSONArray("alerts")
        ExtremeRun(run.getInt("index"), run.getLong("time"),
            run.optDouble("down").toFloat(), run.optDouble("up").toFloat(),
            run.optDouble("latency").takeIf { it.isFinite() }?.toFloat(),
            run.optDouble("loss").toFloat(), run.optDouble("obstruction").toFloat(), run.getString("state"),
            (0 until alerts.length()).map { alerts.getString(it) }, run.getLong("duration"), run.getLong("bytes"),
            run.optDouble("power").takeIf { it.isFinite() }?.toFloat(),
            run.optString("failure").takeIf { it.isNotBlank() && it != "null" },
            if (run.has("download_measured") && !run.isNull("download_measured")) run.getBoolean("download_measured") else null,
            if (run.has("upload_measured") && !run.isNull("upload_measured")) run.getBoolean("upload_measured") else null)
    }
    return ExtremeSession(json.getLong("id"), json.getInt("requested"), json.getLong("start"), json.getLong("end"), runs)
}
