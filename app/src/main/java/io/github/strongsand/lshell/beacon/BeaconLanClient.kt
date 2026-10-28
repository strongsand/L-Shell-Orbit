package io.github.strongsand.lshell.beacon

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import android.content.Context
import android.util.Log
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

interface BeaconLanClient {
    suspend fun info(device: BeaconDevice): Result<BeaconDevice>
    suspend fun status(device: BeaconDevice): Result<BeaconRuntimeStatus>
}

class AndroidBeaconLanClient(private val network: Network? = null, context: Context? = null) : BeaconLanClient, BeaconSyncTransport {
    private val managementCredentials = context?.let(::BeaconManagementCredentials)

    override suspend fun info(device: BeaconDevice): Result<BeaconDevice> = request(device, "/v1/info") { json ->
        val beaconId = json.optString("beacon_id")
        require(beaconId.isNotBlank()) { "Beacon identity missing" }
        val protocol = json.optInt("protocol_version", -1)
        require(protocol > 0) { "Beacon did not report a protocol version" }
        Log.i(TAG, "BEACON_INFO_VALID")
        Log.i(TAG, "BEACON_PROTOCOL_VERSION=$protocol")
        device.copy(
            id = beaconId,
            implementation = json.optString("implementation").takeIf { it.isNotBlank() } ?: device.implementation,
            firmwareVersion = json.optString("firmware_version").takeIf { it.isNotBlank() }
                ?: device.firmwareVersion,
            protocolVersion = protocol,
            capabilities = json.optJSONArray("capabilities")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
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
                    ?.takeUnless { device.implementation == "android" }
                    ?.let { ((it / 64L) * 5L / 3600L).toInt() },
                wifiConnected = json.optBoolean("wifi_connected", false),
                uptimeSeconds = json.optLongOrNull("uptime_seconds"),
                dishyAvailable = json.optBooleanOrNull("dish_reachable"),
                pendingRecords = json.optLongOrNull("pending_records"),
                recording = json.optBoolean("recording", false),
                storageHealthy = json.optBoolean("storage_healthy", true),
                oldestSequence = json.optLongOrNull("oldest_sequence"),
                newestSequence = json.optLongOrNull("newest_sequence"),
                recordCount = json.optLongOrNull("record_count"),
                lastContactAtMillis = receivedAt,
                bootId = json.optLongOrNull("boot_id"),
                wifiRssi = json.optLongOrNull("wifi_rssi")?.toInt(),
                acknowledgedSequence = json.optLongOrNull("acknowledged_sequence"),
                electrical = BeaconPowerParser.parse(json, device.capabilities)
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
                capacity = json.getLong("capacity"),
                nextSequence = json.optLongOrNull("next_sequence"),
                acknowledgedSequence = json.optLongOrNull("acknowledged_sequence")
            )
        }

    override suspend fun recordsAfter(device: BeaconDevice, sequence: Long, limit: Int): Result<BeaconSyncPage> =
        request(device, "/v1/history?after=$sequence&limit=${limit.coerceIn(1, 500)}&time_policy=strict") { json ->
            if (json.has("current_session")) require(json.getString("current_session") == device.id) { "Beacon lineage changed" }
            val records = json.getJSONArray("records")
            val parsed = ArrayList<BeaconSample>(records.length())
            val receivedAt = System.currentTimeMillis()
            repeat(records.length()) { index ->
                val row = records.getJSONObject(index)
                parsed += BeaconSample(
                    beaconId = device.id,
                    sequence = row.getLong("sequence"),
                    timestampMillis = BeaconHistoryClock.timestamp(row, json, receivedAt, "absolute_timestamp_v1" in device.capabilities),
                    downloadMbps = row.optFiniteFloat("download_mbps"),
                    uploadMbps = row.optFiniteFloat("upload_mbps"),
                    latencyMs = row.optFiniteFloat("latency_ms"),
                    dropPercent = row.optFiniteFloat("drop_rate"),
                    powerWatts = row.optFiniteFloat("power_watts"),
                    signal = row.optFiniteFloat("signal"),
                    dishCounter = row.optLongOrNull("dish_counter"),
                    dishState = (if ("absolute_timestamp_v1" in device.capabilities)
                        row.optString("dish_state").takeIf { it.isNotBlank() && it != "null" } else null) ?: when (row.optInt("connectivity", 2)) {
                        1 -> "CONNECTED"
                        0 -> "OFFLINE"
                        else -> null
                    }
                )
            }
            BeaconSyncPage(parsed, json.getLong("next_sequence"), json.getBoolean("has_more"))
        }

    override suspend fun acknowledge(device: BeaconDevice, sequence: Long): Result<Unit> = runCatching {
        require(sequence > 0L) { "Invalid Beacon ACK sequence" }
        val verified = info(device).getOrThrow()
        if (BeaconAckPolicy.mode(device, verified) == BeaconAckPolicy.Mode.AUTHENTICATED) {
            val challenge = managementChallenge(verified, "ack").getOrThrow()
            val token = managementCredentials?.read(device.id)
                ?: error("Secure BLE setup required for authenticated ACK")
            val nonce = BeaconManagementProtocol.challenge(challenge, verified)
            val body = "beacon_id=${URLEncoder.encode(device.id, "UTF-8")}&nonce=$nonce&sequence=$sequence"
            request(verified, "/v1/history/ack", method = "POST", body = body, token = token, form = true,
                authMessage = BeaconManagementAuth.historyAck(device.id, nonce, sequence)) { json ->
                check(json.getBoolean("ok") && json.getLong("through_sequence") == sequence)
                BeaconManagementAuth.verify(token,
                    BeaconManagementAuth.accepted("ack", device.id, nonce, sequence),
                    json.getString("response_mac"))
            }.getOrThrow()
        } else {
            request(verified, "/v1/history/ack", method = "POST", body = "{\"sequence\":$sequence}") { Unit }
                .getOrThrow()
        }
    }

    override suspend fun prepareHistoryClock(device: BeaconDevice): Result<Unit> = runCatching {
        val verified = info(device).getOrThrow()
        require(verified.id == device.id && verified.protocolVersion == 1) { "Beacon identity/protocol changed" }
        if ("absolute_timestamp_v1" in verified.capabilities) return@runCatching
        if ("boot_time_anchors_v1" in verified.capabilities && managementCredentials?.read(device.id) != null) {
            val challenge = managementChallenge(verified, "time_anchor").getOrThrow()
            if (!challenge.optBoolean("time_anchor_available")) {
                command(verified, "time_anchor", challenge, mapOf(
                    "boot_id" to challenge.getLong("boot_id").toString(),
                    "epoch_ms" to System.currentTimeMillis().toString()
                )).getOrThrow()
            }
        }
    }

    suspend fun verifyAndroidPairing(device: BeaconDevice, token: String): Result<Unit> = runCatching {
        require(device.implementation == "android")
        managementChallenge(device, "ack", token).getOrThrow()
        Unit
    }

    private suspend fun managementChallenge(device: BeaconDevice, action: String, tokenOverride: String? = null): Result<JSONObject> = runCatching {
        val verified = info(device).getOrThrow()
        require(verified.id == device.id && "management_v1" in verified.capabilities) { "Management firmware unavailable" }
        val capability = when (action) {
            "factory_reset" -> "factory_reset_v1"
            "discard" -> "discard_unrecoverable_v1"
            "ack" -> "authenticated_ack_v1"
            else -> "boot_time_anchors_v1"
        }
        require(capability in verified.capabilities) { "Management command unavailable" }
        val token = tokenOverride ?: managementCredentials?.read(device.id) ?: error("Secure BLE setup required for management")
        val random = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val clientNonce = random.joinToString("") { "%02x".format(it.toInt() and 255) }
        request(verified, "/v1/management/challenge?action=$action&beacon_id=${device.id}&client_nonce=$clientNonce",
            token = token, authMessage = BeaconManagementAuth.challengeRequest(action, device.id, clientNonce)) { json ->
            BeaconManagementProtocol.challenge(json, verified)
            BeaconManagementAuth.verify(token, BeaconManagementAuth.challengeResponse(action, clientNonce, json), json.getString("response_mac"))
            json
        }.getOrThrow()
    }

    private suspend fun command(device: BeaconDevice, action: String, challenge: JSONObject,
        fields: Map<String, String>): Result<JSONObject> {
        val token = managementCredentials?.read(device.id) ?: return Result.failure(IllegalStateException("Secure BLE setup required"))
        val nonce = BeaconManagementProtocol.challenge(challenge, device)
        val values = fields + mapOf("nonce" to nonce, "beacon_id" to device.id)
        val body = values.entries.joinToString("&") { (k,v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val path = when (action) { "factory_reset" -> "/v1/factory-reset"; "discard" -> "/v1/history/discard"; else -> "/v1/time/anchor" }
        return request(device, path, method = "POST", body = body, token = token, form = true,
            authMessage = BeaconManagementAuth.command(action, device.id, nonce, fields)) {
            check(it.getBoolean("ok"))
            BeaconManagementAuth.verify(token, BeaconManagementAuth.accepted(action, device.id, nonce, it.getLong("through_sequence")), it.getString("response_mac"))
            it
        }
    }

    suspend fun factoryReset(device: BeaconDevice): Result<Unit> = runCatching {
        val challenge = managementChallenge(device, "factory_reset").getOrThrow()
        command(device, "factory_reset", challenge, mapOf("confirmation" to "FACTORY_RESET")).getOrThrow()
        Unit
    }

    suspend fun discardPreview(device: BeaconDevice): Result<BeaconDiscardPreview> = runCatching {
        val challenge = managementChallenge(device, "discard").getOrThrow()
        BeaconManagementProtocol.discardPreview(challenge)
    }

    suspend fun discard(device: BeaconDevice, preview: BeaconDiscardPreview): Result<Long> = runCatching {
        val challenge = managementChallenge(device, "discard").getOrThrow()
        BeaconManagementProtocol.verifyDiscard(preview, BeaconManagementProtocol.discardPreview(challenge))
        val response = command(device, "discard", challenge, mapOf("confirmation" to "DISCARD_UNRECOVERABLE")).getOrThrow()
        response.getLong("through_sequence").also { require(it == preview.throughSequence) }
    }

    private suspend fun <T> request(
        device: BeaconDevice,
        path: String,
        method: String = "GET",
        body: String? = null,
        token: String? = null,
        form: Boolean = false,
        authMessage: String? = null,
        parse: (JSONObject) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            require(device.host.isNotBlank() && device.port in 1..65535) { "Invalid Beacon address" }
            val host = if (':' in device.host && !device.host.startsWith("[")) "[${device.host}]" else device.host
            val url = URL("http://$host:${device.port}$path")
            val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 3_000
                connection.readTimeout = 10_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                if (token != null) connection.setRequestProperty("Authorization", "Beacon-HMAC ${BeaconManagementAuth.sign(token, requireNotNull(authMessage))}")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                if (connection.responseCode == 409) {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText().take(2048) }.orEmpty()
                    if (runCatching { JSONObject(error).optString("code") }.getOrNull() == "historical_time_unrecoverable")
                        throw BeaconHistoryTimestampException("historical_time_unrecoverable")
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
        }.onFailure {
            if (it is BeaconHistoryTimestampException)
                Log.w(TAG, "BEACON_HISTORY_TIME_UNAVAILABLE reason=${it.reason}")
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
