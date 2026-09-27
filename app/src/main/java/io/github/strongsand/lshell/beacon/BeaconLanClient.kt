package io.github.strongsand.lshell.beacon

import java.net.HttpURLConnection
import java.net.URL
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

interface BeaconLanClient {
    suspend fun info(device: BeaconDevice): Result<BeaconDevice>
    suspend fun status(device: BeaconDevice): Result<BeaconRuntimeStatus>
}

class AndroidBeaconLanClient : BeaconLanClient, BeaconSyncTransport {
    override suspend fun info(device: BeaconDevice): Result<BeaconDevice> = request(device, "/v1/info") { json ->
        val protocol = json.optInt("protocol_version", -1)
        require(protocol > 0) { "Beacon did not report a protocol version" }
        Log.i(TAG, "BEACON_INFO_VALID")
        Log.i(TAG, "BEACON_PROTOCOL_VERSION=$protocol")
        device.copy(
            id = json.optString("beacon_id").takeIf { it.isNotBlank() } ?: device.id,
            firmwareVersion = json.optString("firmware_version").takeIf { it.isNotBlank() }
                ?: device.firmwareVersion,
            protocolVersion = protocol
        )
    }

    override suspend fun status(device: BeaconDevice): Result<BeaconRuntimeStatus> =
        request(device, "/v1/status") { json ->
            val receivedAt = System.currentTimeMillis()
            val beaconUptime = json.optLongOrNull("beacon_uptime_ms")
            BeaconRuntimeStatus(
                device = device,
                lastSampleAtMillis = json.optLongOrNull("last_sample_ms")
                    ?: reconstructUptimeTime(receivedAt, beaconUptime, json.optLongOrNull("last_sample_uptime_ms")),
                lastCollectionAtMillis = json.optLongOrNull("last_collect_ms")
                    ?: reconstructUptimeTime(receivedAt, beaconUptime, json.optLongOrNull("last_collect_uptime_ms")),
                lastSyncAtMillis = json.optLongOrNull("last_sync_ms")
                    ?: reconstructUptimeTime(receivedAt, beaconUptime, json.optLongOrNull("last_sync_uptime_ms")),
                storageUsedBytes = json.optLongOrNull("storage_used_bytes"),
                storageCapacityBytes = json.optLongOrNull("storage_capacity_bytes"),
                estimatedRetentionHours = json.optLongOrNull("storage_capacity_bytes")
                    ?.let { ((it / 64L) * 5L / 3600L).toInt() },
                wifiConnected = json.optBoolean("wifi_connected", false),
                uptimeSeconds = json.optLongOrNull("uptime_seconds"),
                dishyAvailable = json.optBooleanOrNull("dish_reachable"),
                pendingRecords = json.optLongOrNull("pending_records"),
                recording = json.optBoolean("recording", false),
                storageHealthy = json.optBoolean("storage_healthy", true),
                oldestSequence = json.optLongOrNull("oldest_sequence"),
                newestSequence = json.optLongOrNull("newest_sequence"),
                recordCount = json.optLongOrNull("record_count")
            ).also { Log.i(TAG, "BEACON_STATUS_OK") }
        }

    override suspend fun historyInfo(device: BeaconDevice): Result<BeaconHistoryInfo> =
        request(device, "/v1/history/info") { json ->
            BeaconHistoryInfo(
                protocolVersion = json.getInt("protocol_version"),
                recordVersion = json.getInt("record_version"),
                oldestSequence = json.getLong("oldest_sequence"),
                newestSequence = json.getLong("newest_sequence"),
                recordCount = json.getLong("record_count"),
                capacity = json.getLong("capacity")
            )
        }

    override suspend fun recordsAfter(device: BeaconDevice, sequence: Long, limit: Int): Result<BeaconSyncPage> =
        request(device, "/v1/history?after=$sequence&limit=${limit.coerceIn(1, 500)}") { json ->
            val records = json.getJSONArray("records")
            val parsed = ArrayList<BeaconSample>(records.length())
            val beaconUptime = json.optLong("beacon_uptime_ms", -1L)
            val beaconBootId = json.optInt("beacon_boot_id", -1)
            val receivedAt = System.currentTimeMillis()
            repeat(records.length()) { index ->
                val row = records.getJSONObject(index)
                parsed += BeaconSample(
                    beaconId = device.id,
                    sequence = row.getLong("sequence"),
                    timestampMillis = row.optLongOrNull("timestamp_ms")
                        ?: row.optLongOrNull("capture_uptime_ms")?.let { captured ->
                            if (row.optInt("boot_id", -2) == beaconBootId && beaconUptime >= captured)
                                receivedAt - (beaconUptime - captured)
                            else receivedAt
                        } ?: receivedAt,
                    downloadMbps = row.optFiniteFloat("download_mbps"),
                    uploadMbps = row.optFiniteFloat("upload_mbps"),
                    latencyMs = row.optFiniteFloat("latency_ms"),
                    dropPercent = row.optFiniteFloat("drop_rate"),
                    powerWatts = row.optFiniteFloat("power_watts"),
                    signal = row.optFiniteFloat("signal"),
                    dishCounter = row.optLongOrNull("dish_counter"),
                    dishState = when (row.optInt("connectivity", 2)) {
                        1 -> "CONNECTED"
                        0 -> "OFFLINE"
                        else -> null
                    }
                )
            }
            BeaconSyncPage(parsed, json.getLong("next_sequence"), json.getBoolean("has_more"))
        }

    override suspend fun acknowledge(device: BeaconDevice, sequence: Long): Result<Unit> =
        request(device, "/v1/history/ack", method = "POST", body = "{\"sequence\":$sequence}") { Unit }

    private suspend fun <T> request(
        device: BeaconDevice,
        path: String,
        method: String = "GET",
        body: String? = null,
        parse: (JSONObject) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            require(device.host.isNotBlank() && device.port in 1..65535) { "Invalid Beacon address" }
            val host = if (':' in device.host && !device.host.startsWith("[")) "[${device.host}]" else device.host
            val connection = URL("http://$host:${device.port}$path").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 3_000
                connection.readTimeout = 10_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "Beacon HTTP ${connection.responseCode}"
                }
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                require(body.length <= MAX_RESPONSE_CHARS) { "Beacon response is too large" }
                parse(JSONObject(body))
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
        if (has(name) && !isNull(name)) optBoolean(name) else null

    private fun JSONObject.optFiniteFloat(name: String): Float? =
        if (!has(name) || isNull(name)) null else optDouble(name).toFloat().takeIf { it.isFinite() }

    private fun reconstructUptimeTime(receivedAt: Long, currentUptime: Long?, eventUptime: Long?): Long? =
        if (currentUptime != null && eventUptime != null && currentUptime >= eventUptime)
            receivedAt - (currentUptime - eventUptime)
        else null

    private companion object {
        const val TAG = "L-ShellBeaconLAN"
        const val MAX_RESPONSE_CHARS = 512 * 1024
    }
}
