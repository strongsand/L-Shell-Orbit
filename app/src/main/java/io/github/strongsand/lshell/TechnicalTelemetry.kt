package io.github.strongsand.lshell

import io.github.strongsand.lshell.proto.DishGetStatusResponse
import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import org.json.JSONArray
import org.json.JSONObject

data class TechnicalField(val group: String, val name: String, val source: String, val value: String?)

/** An allowlist, not a protobuf dump. No device ID, country, network names or unknown fields. */
object TechnicalTelemetry {
    fun status(proto: DishGetStatusResponse): List<TechnicalField> = buildList {
        fun field(group: String, name: String, value: Any?) {
            add(TechnicalField(group, name, "DishGetStatus", value?.toString()))
        }
        fun number(value: Float): Float? = value.takeIf { it.isFinite() && it != 0f }
        val device = proto.deviceInfo
        field("terminal", "hardware_version", device.hardwareVersion.takeIf { it.isNotBlank() })
        field("terminal", "software_version", device.softwareVersion.takeIf { it.isNotBlank() })
        field("terminal", "manufactured_version", device.manufacturedVersion.takeIf { it.isNotBlank() })
        field("terminal", "uptime_s", if (proto.hasDeviceState()) proto.deviceState.uptimeS.takeIf { it > 0L } else null)
        field("terminal", "bootcount", device.bootcount.takeIf { it > 0 })
        field("terminal", "last_boot_reason", if (device.hasBoot()) device.boot.lastReason else null)
        field("terminal", "outage_cause", if (proto.hasOutage()) proto.outage.cause.name else null)
        field("terminal", "seconds_to_first_nonempty_slot", number(proto.secondsToFirstNonemptySlot))
        field("terminal", "eth_speed_mbps", proto.ethSpeedMbps.takeIf { it > 0 })
        field("rf", "pop_ping_latency_ms", number(proto.popPingLatencyMs))
        field("rf", "pop_ping_drop_rate", number(proto.popPingDropRate))
        field("rf", "downlink_throughput_bps", number(proto.downlinkThroughputBps))
        field("rf", "uplink_throughput_bps", number(proto.uplinkThroughputBps))
        // Proto3 scalar presence was not retained by the generated binding. False/zero
        // cannot be distinguished from absence here; report them conservatively.
        field("rf", "is_snr_above_noise_floor", true.takeIf { proto.isSnrAboveNoiseFloor })
        field("rf", "is_snr_persistently_low", true.takeIf { proto.isSnrPersistentlyLow })
        field("obstruction", "boresight_azimuth_deg", number(proto.boresightAzimuthDeg))
        field("obstruction", "boresight_elevation_deg", number(proto.boresightElevationDeg))
        field("obstruction", "fraction_obstructed", number(proto.obstructionStats.fractionObstructed))
        field("obstruction", "time_obstructed", number(proto.obstructionStats.timeObstructed))
        field("obstruction", "valid_s", number(proto.obstructionStats.validS))
        field("obstruction", "avg_prolonged_obstruction_interval_s", number(proto.obstructionStats.avgProlongedObstructionIntervalS))
        if (proto.hasAlerts()) {
            // An explicit message of alerts may legitimately have no active flags.
            field("terminal", "alerts", io.github.strongsand.lshell.core.network.grpc.DishyTelemetryMapper
                .map(proto).alerts.getActiveAlertsList().joinToString("; ").ifBlank { "[]" })
        }
    }

    fun history(history: DishyGrpcClient.HistoryReading): List<TechnicalField> = buildList {
        add(TechnicalField("history", "current", "DishGetHistory", history.current.toString()))
        val arrays = listOf("download_mbps" to history.downloadMbps, "upload_mbps" to history.uploadMbps,
            "latency_ms" to history.latencyMs, "drop_percent" to history.dropPercent,
            "power_in_watts" to history.powerWatts)
        arrays.forEach { (name, values) ->
            val ordered = historyValues(values, history.current)
            val valid = ordered.filter { it.isFinite() && it >= 0f && (name != "power_in_watts" || it > 0f) }
            val group = if (name == "power_in_watts") "energy" else "history"
            add(TechnicalField(group, "${name}_valid_count", "DishGetHistory", valid.size.toString()))
            add(TechnicalField(group, "${name}_mean", "DishGetHistory", valid.takeIf { it.isNotEmpty() }?.average()?.toString()))
            add(TechnicalField(group, "${name}_min", "DishGetHistory", valid.minOrNull()?.toString()))
            add(TechnicalField(group, "${name}_max", "DishGetHistory", valid.maxOrNull()?.toString()))
            add(TechnicalField(group, "${name}_recent_12", "DishGetHistory",
                ordered.takeLast(12).takeIf { it.isNotEmpty() }?.joinToString(", ") { if (it.isFinite()) it.toString() else "—" }))
            add(TechnicalField(group, "${name}_latest", "DishGetHistory", ordered.lastOrNull { it.isFinite() && it >= 0f && (name != "power_in_watts" || it > 0f) }?.toString()))
        }
        // power_in is a 1-second circular history; sum valid durations only, no extrapolation.
        val power = historyValues(history.powerWatts, history.current).filter { it.isFinite() && it > 0f }
        add(TechnicalField("energy", "energy_in_history_wh", "DishGetHistory / app integration",
            power.takeIf { it.isNotEmpty() }?.sumOf { it.toDouble() }?.div(3600.0)?.toString()))
    }

    internal fun historyValues(values: List<Float>, current: Long): List<Float> {
        if (values.isEmpty() || current <= 0L) return emptyList()
        val count = minOf(current, values.size.toLong()).toInt()
        return List(count) { offset -> values[((current - count + offset) % values.size).toInt()] }
    }

    fun json(fields: List<TechnicalField>): String = JSONObject().put("format_version", 1)
        .put("fields", JSONArray().apply { fields.forEach { field ->
            put(JSONObject().put("group", field.group).put("field", field.name)
                .put("source", field.source).put("value", field.value ?: JSONObject.NULL))
        } }).toString(2)
}
