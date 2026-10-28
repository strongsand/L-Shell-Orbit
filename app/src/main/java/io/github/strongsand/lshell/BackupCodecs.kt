package io.github.strongsand.lshell

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

internal fun JSONObject.backupRequireInteger(vararg keys: String) { keys.forEach { key ->
    val n = get(key); require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble())
} }

internal object BackupRecordCodec {
    val historyFields = listOf("time_ms", "kind", "label", "download", "upload", "latency", "drop_rate", "obstruction",
        "source_counter", "source_boot", "uptime_seconds", "ethernet_mbps", "dish_state", "active_alerts",
        "power_watts", "signal", "source", "beacon_id", "beacon_sequence")
    val historyIntegers = setOf("time_ms", "source_counter", "source_boot", "uptime_seconds", "ethernet_mbps", "beacon_sequence")
    val historyTexts = setOf("kind", "label", "dish_state", "active_alerts", "source", "beacon_id")
    fun wrap(category: BackupCategory, data: JSONObject): JSONObject = JSONObject().put("schemaVersion", 1)
        .put("key", identity(category, data)).put("data", data)

    /** Legacy v1 archives may contain empty calendar caches; validate them but do not restore them. */
    fun hasRestorableData(category: BackupCategory, data: JSONObject): Boolean =
        category != BackupCategory.DAYS || ConnectionHealthJson.day(data.getJSONObject("day")).hasPreservedData

    fun identity(category: BackupCategory, d: JSONObject): String = when (category) {
        BackupCategory.READINGS, BackupCategory.EVENTS -> when {
            d.optString("source") == "BEACON" -> "beacon:${d.getString("beacon_id")}:${d.getLong("beacon_sequence")}"
            d.optString("kind") == "terminal_history" && !d.isNull("source_boot") && !d.isNull("source_counter") ->
                "terminal:${d.getLong("source_boot")}:${d.getLong("source_counter")}"
            else -> "local:" + backupHash(backupCanonical(d).toByteArray(Charsets.UTF_8))
        }
        BackupCategory.TESTS, BackupCategory.EXTREME, BackupCategory.TRAFFIC -> d.get("id").toString()
        BackupCategory.DAILY -> "daily:${d.getLong("start")}:${d.getLong("end")}"
        BackupCategory.WEEKLY, BackupCategory.MONTHLY -> "${d.getString("kind")}:${d.getLong("start")}:${d.getLong("end")}:${d.getString("zone")}"
        BackupCategory.DAYS -> d.getString("key")
        BackupCategory.SETTINGS -> error("Settings are not JSONL")
    }

    fun validate(category: BackupCategory, record: JSONObject) {
        record.backupRequireInteger("schemaVersion")
        if (record.getInt("schemaVersion") > 1) throw BackupException(BackupException.Reason.FUTURE_VERSION)
        require(record.getInt("schemaVersion") == 1)
        val d = record.getJSONObject("data")
        when (category) {
            BackupCategory.READINGS, BackupCategory.EVENTS -> {
                require(d.keys().asSequence().toSet() == historyFields.toSet())
                historyFields.forEach { field -> if (!d.isNull(field)) {
                    val v = d.get(field)
                    when (field) {
                        in historyTexts -> require(v is String && v.length <= if (field == "active_alerts") 8192 else 1000)
                        in historyIntegers -> require(v is Number && v.toDouble().isFinite() && v.toDouble() == v.toLong().toDouble())
                        else -> require(v is Number && v.toDouble().isFinite() && v.toDouble().toFloat().isFinite())
                    }
                } }
                require(d.getLong("time_ms") > 0 && d.getString("label").length <= 1000)
                require(d.getString("source") in setOf("LOCAL_PHONE", "BEACON"))
                require(d.getString("kind") in if (category == BackupCategory.EVENTS) setOf("event")
                    else setOf("reading", "terminal_history", "beacon_history"))
                if (d.getString("source") == "BEACON") {
                    require(d.getString("beacon_id").isNotBlank() && d.getLong("beacon_sequence") >= 0)
                    require(d.getString("kind") == "beacon_history" || d.getString("kind") == "event")
                }
            }
            BackupCategory.TESTS -> {
                d.backupRequireInteger("schema", "start", "end")
                require(d.get("id") is String)
                require(d.getInt("schema") == 1 && d.getString("id").matches(Regex("[A-Za-z0-9_-]{1,100}")))
                times(d, "start", "end")
                require(d.getString("origin").matches(Regex("[A-Z_]{1,60}")))
                require(d.getString("outcome") in setOf("COMPLETED", "FAILED", "INTERRUPTED"))
                listOf("download", "upload", "latency").forEach { key ->
                    val a = d.getJSONArray(key); require(a.length() <= 100)
                    repeat(a.length()) { require(a.get(it) is Number && a.getDouble(it).isFinite() && a.getDouble(it) >= 0) }
                }
                d.optJSONObject("detail")?.let { detail ->
                    detail.backupRequireInteger("id", "start", "end", "total", "completed")
                    require(detail.get("id").toString() == d.getString("id"))
                    require(detail.getString("phase") in setOf("COMPLETED", "FAILED", "INTERRUPTED"))
                    require(detail.getString("kind") in setOf("NORMAL", "EXTREME"))
                    times(detail, "start", "end")
                    require(detail.getLong("start") == d.getLong("start") && detail.getLong("end") == d.getLong("end"))
                    require(detail.getInt("total") in 1..100 && detail.getInt("completed") in 0..detail.getInt("total"))
                    detail.optJSONObject("extreme")?.let(::validateExtreme)
                    detail.optJSONObject("result")?.let { r -> listOf("down", "up", "latency").forEach { key ->
                        if (!r.isNull(key)) require(r.get(key) is Number && r.getDouble(key).toFloat().isFinite() && r.getDouble(key) >= 0)
                    } }
                }
            }
            BackupCategory.EXTREME -> validateExtreme(d)
            BackupCategory.TRAFFIC -> {
                d.backupRequireInteger("start_ms", "end_ms", "created_ms", "boot_count")
                require(d.getString("mode") in TrafficSessionMode.entries.map { it.name })
                val s = TrafficSessionMetadata.decode(JSONObject().put("version", 1).put("sessions", JSONArray().put(d)).toString()).single()
                require(!s.active && s.createdAt > 0 && s.bootCount == -1)
            }
            BackupCategory.DAILY -> {
                d.backupRequireInteger("formatVersion", "start", "end", "created", "samples")
                if (d.getInt("formatVersion") > CURRENT_REPORT_FORMAT) throw BackupException(BackupException.Reason.FUTURE_VERSION)
                require(d.getInt("formatVersion") in 1..CURRENT_REPORT_FORMAT)
                times(d, "start", "end"); require(d.getLong("created") > 0)
                // Reuse the production decoder without regenerating a snapshot or changing its algorithm.
                val r = reportFromJson(d)
                require(r.sampleCount >= 0 && r.monitoredMs >= 0 && r.onlineMs >= 0 && r.offlineMs >= 0)
                require(r.availabilityPercent == null || r.availabilityPercent in 0f..100f)
                listOf(r.trafficDownload, r.trafficUpload, r.latency, r.packetLoss, r.obstruction).filterNotNull().forEach {
                    require(it.count > 0 && listOf(it.minimum, it.maximum, it.average, it.median, it.p95).all(Float::isFinite))
                    require(it.maximum >= it.minimum)
                }
                listOf("latencyChart", "lossChart", "obstructionChart", "trafficChart").forEach { require(d.getJSONArray(it).length() <= 48) }
            }
            BackupCategory.WEEKLY, BackupCategory.MONTHLY -> {
                if (d.getInt("schema") > 1) throw BackupException(BackupException.Reason.FUTURE_VERSION)
                val r = ConnectionHealthJson.report(d)
                require(r.closed && r.through == r.period.end && r.generatedAt > 0 && r.period.start > 0 && r.period.end > r.period.start)
                require(r.period.kind == if (category == BackupCategory.WEEKLY) HealthPeriodKind.WEEK else HealthPeriodKind.MONTH)
                ZoneId.of(r.period.zone); require(r.days.size <= 32)
                validateDistributions(d)
            }
            BackupCategory.DAYS -> {
                require(d.getString("key").length <= 180)
                val day = ConnectionHealthJson.day(d.getJSONObject("day"))
                require(day.start > 0 && day.end >= day.start && day.end - day.start <= 26 * 3_600_000L)
                require(d.getLong("start") == day.start && d.getString("key").startsWith("${day.start}:"))
                ZoneId.of(d.getString("key").substringAfter(':'))
                validateDistributions(d)
            }
            BackupCategory.SETTINGS -> error("Settings are not records")
        }
        require(record.getString("key") == identity(category, d))
    }
    private fun times(d: JSONObject, a: String, b: String) {
        d.backupRequireInteger(a, b)
        require(d.getLong(a) > 0 && d.getLong(b) >= d.getLong(a))
    }
    private fun validateExtreme(d: JSONObject) {
        d.backupRequireInteger("id", "requested", "start", "end")
        times(d, "start", "end"); require(d.getLong("id") > 0 && d.getInt("requested") in 1..100)
        val a = d.getJSONArray("runs"); require(a.length() <= d.getInt("requested"))
        val indices = mutableSetOf<Int>()
        repeat(a.length()) { i -> val r = a.getJSONObject(i)
            r.backupRequireInteger("index", "time", "duration", "bytes")
            require(indices.add(r.getInt("index")) && r.getInt("index") in 1..100)
            require(r.getLong("time") > 0 && r.getLong("duration") >= 0 && r.getLong("bytes") >= 0)
            listOf("down", "up", "latency", "loss", "obstruction", "power").forEach { key ->
                if (!r.isNull(key)) require(r.get(key) is Number && r.getDouble(key).toFloat().isFinite()) }
            require(r.getJSONArray("alerts").length() <= 100 && r.getString("state").length <= 200)
        }
        executionSessionFromJson(d)
    }
    /** Avoid admitting inconsistent distributions that could later NPE in percentile/merge. */
    private fun validateDistributions(value: Any) {
        when (value) {
            is JSONObject -> {
                if (value.has("bins") && value.has("sum") && value.has("count")) {
                    val d = ConnectionHealthJson.distribution(value)
                    require(d.count >= 0 && d.sum.isFinite() && d.sum >= 0 && d.bins.values.all { it > 0 })
                    require(d.bins.values.sum() == d.count)
                    require(d.count == 0L || (d.minimum != null && d.maximum != null && d.minimum >= 0 && d.maximum >= d.minimum))
                }
                value.keys().forEach { key -> value.get(key).let { if (it is JSONObject || it is JSONArray) validateDistributions(it) } }
            }
            is JSONArray -> repeat(value.length()) { value.get(it).let { v -> if (v is JSONObject || v is JSONArray) validateDistributions(v) } }
        }
    }
}

internal data class BackupPreference(val group: String, val name: String, val value: Any)
internal data class BackupSettingsPlan(val compatible: List<BackupPreference>, val ignored: Int, val automationRequested: Boolean)

/** Explicit allowlist. No runtime/URI/bond/credential namespace is copied wholesale. */
internal object BackupSettingsCodec {
    data class Rule(val group: String, val name: String, val automation: Boolean = false, val accepts: (Any) -> Boolean)
    private fun bool(g: String, n: String, costly: Boolean = false) = Rule(g, n, costly) { it is Boolean }
    private fun integer(g: String, n: String, range: IntRange) = Rule(g, n) { it is Number && it.toDouble() == it.toInt().toDouble() && it.toInt() in range }
    private fun choices(g: String, n: String, vararg values: Any) = Rule(g, n) { v -> values.any { it.toString() == v.toString() && (it is Number) == (v is Number) } }
    val rules = listOf(
        choices("orbit_experience", "theme", "SYSTEM", "LIGHT", "DARK", "AMOLED"),
        choices("orbit_experience", "haptics", "OFF", "SUBTLE", "STANDARD", "EXPRESSIVE"), bool("orbit_experience", "technical"),
        bool("monitor_preferences", "silent_alerts"), integer("monitor_preferences", "interval_seconds", 1..300),
        choices("monitor_preferences", "ar_max_distance_km", 0, 500, 750, 1000, 1500, 2000),
        bool("daily_report_preferences", "enabled"), integer("daily_report_preferences", "hour", 0..23),
        integer("daily_report_preferences", "minute", 0..59), bool("daily_report_preferences", "notify"),
        bool("daily_report_preferences", "history"), choices("daily_report_preferences", "retention", "SEVEN", "THIRTY", "NINETY", "ALWAYS"),
        bool("connection_health_reports", "enabled"), choices("report_archive", "format", "JSON", "CSV", "BOTH"),
        bool("automatic_speed_tests", "scheduled", true), bool("automatic_speed_tests", "smart", true),
        choices("automatic_speed_tests", "interval", 1, 3, 4, 6, 12, 24), choices("automatic_speed_tests", "cooldown", 3, 4, 6, 12, 24),
        integer("automatic_speed_tests", "smart_limit", 1..8),
        bool("automatic_speed_tests", "custom"),
        Rule("automatic_speed_tests", "custom_times") { value -> value is String && value.length <= 40 &&
            value.split(',').let { parts -> parts.all { it.toIntOrNull() != null } &&
                AutomaticTestPolicy.validTimes(parts.map { it.toInt() }) } },
        bool("beacon_preferences", "wifi_only"), integer("beacon_preferences", "retention_days", 1..365),
        bool("beacon_preferences", "notifications"), bool("beacon_preferences", "notify_offline"), bool("beacon_preferences", "notify_dishy_unavailable"),
        choices("beacon_preferences", "sync_interval_minutes", 15, 30, 60, 180, 360),
        Rule("chart_order", "antenna") { v -> validOrder(v, setOf("latency", "loss", "download", "upload", "power")) },
        Rule("chart_order", "traffic") { v -> validOrder(v, TrafficMetric.entries.map { it.name }.toSet()) }
    )
    private fun validOrder(value: Any, ids: Set<String>): Boolean = value is String && value.length <= 500 &&
        value.split(',').let { it.size == it.distinct().size && it.all { id -> id in ids } }
    fun encode(values: List<BackupPreference>) = JSONObject().put("schemaVersion", 1).put("values", JSONArray(values.map {
        JSONObject().put("group", it.group).put("name", it.name).put("value", it.value) }))
    fun plan(json: JSONObject, allowAutomation: Boolean): BackupSettingsPlan {
        json.backupRequireInteger("schemaVersion")
        if (json.getInt("schemaVersion") > 1) throw BackupException(BackupException.Reason.FUTURE_VERSION)
        require(json.getInt("schemaVersion") == 1)
        val a = json.getJSONArray("values"); require(a.length() <= 200)
        val seen = mutableSetOf<Pair<String, String>>(); val accepted = mutableListOf<BackupPreference>()
        var ignored = 0; var automation = false
        repeat(a.length()) { i -> val j = a.getJSONObject(i)
            val group = j.getString("group"); val name = j.getString("name"); val value = j.get("value")
            require(group.length <= 100 && name.length <= 100 && seen.add(group to name))
            val rule = rules.firstOrNull { it.group == group && it.name == name }
            if (rule == null || !rule.accepts(value)) ignored++
            else if (rule.automation && value == true) {
                automation = true
                if (allowAutomation) accepted += BackupPreference(group, name, value) else ignored++
            } else accepted += BackupPreference(group, name, value)
        }
        return BackupSettingsPlan(accepted, ignored, automation)
    }
}
