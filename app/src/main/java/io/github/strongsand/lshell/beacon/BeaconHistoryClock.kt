package io.github.strongsand.lshell.beacon

import org.json.JSONObject

/** Receipt time is an anchor for this boot's uptime, never a historical fallback. */
internal object BeaconHistoryClock {
    fun timestamp(row: JSONObject, page: JSONObject, receivedAt: Long, absolute: Boolean = false): Long {
        if (absolute) return row.longOrNull("timestamp_ms")?.takeIf {
            io.github.strongsand.lshell.beaconprotocol.BeaconProtocol.absoluteTime(it) && it <= receivedAt + 120_000L
        } ?: throw BeaconHistoryTimestampException("invalid_absolute_timestamp")
        row.longOrNull("timestamp_ms")?.takeIf { it > 0 }?.let { return it }
        val captured = row.longOrNull("capture_uptime_ms")
        val uptime = page.longOrNull("beacon_uptime_ms")
        val boot = row.longOrNull("boot_id")
        val currentBoot = page.longOrNull("beacon_boot_id")
        val anchors = page.optJSONArray("boot_anchors")
        if (anchors != null && captured != null && boot != null) {
            if (anchors.length() > 32) throw BeaconHistoryTimestampException("invalid_anchor_table")
            val matching = (0 until anchors.length()).map { anchors.getJSONObject(it) }
                .filter { it.longOrNull("boot_id") == boot }
            if (matching.size > 1) throw BeaconHistoryTimestampException("ambiguous_anchor")
            matching.singleOrNull()?.let { anchor ->
                val epoch = anchor.longOrNull("epoch_ms")
                val anchoredUptime = anchor.longOrNull("uptime_ms")
                if (epoch == null || epoch !in 1_577_836_800_001L until 4_102_444_800_000L ||
                    anchoredUptime == null || anchoredUptime < 0 || anchor.optString("source") != "host" ||
                    (boot == currentBoot && uptime != null && captured > uptime))
                    throw BeaconHistoryTimestampException("invalid_anchor")
                val resolved = try {
                    Math.addExact(epoch, Math.subtractExact(captured, anchoredUptime))
                } catch (_: ArithmeticException) { null }
                return resolved?.takeIf { it > 0 }
                    ?: throw BeaconHistoryTimestampException("invalid_clock_arithmetic")
            }
        }
        if (captured != null && uptime != null && uptime >= 0 && captured <= uptime &&
            boot != null && boot >= 0 && boot == currentBoot) {
            // Negative capture uptime is legitimate for Dishy history predating Beacon startup.
            val resolved = try {
                Math.subtractExact(receivedAt, Math.subtractExact(uptime, captured))
            } catch (_: ArithmeticException) { null }
            resolved?.takeIf { it > 0 }?.let { return it }
        }
        // Fail the whole page before persistence/ACK; its records remain pending on the Beacon.
        throw BeaconHistoryTimestampException(when {
            captured == null -> "missing_capture_uptime"
            boot == null || boot < 0 || currentBoot == null || currentBoot < 0 -> "missing_boot"
            boot != currentBoot -> "different_boot"
            uptime == null || uptime < 0 || captured > uptime -> "invalid_uptime"
            else -> "invalid_clock_arithmetic"
        })
    }

    private fun JSONObject.longOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) runCatching { getLong(key) }.getOrNull() else null
}

internal class BeaconHistoryTimestampException(val reason: String) :
    IllegalStateException("Beacon history has no recoverable capture time ($reason)")
