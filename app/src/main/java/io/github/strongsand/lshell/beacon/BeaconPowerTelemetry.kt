package io.github.strongsand.lshell.beacon

import org.json.JSONObject
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

internal const val POWER_SIMULATION_CAPABILITY = "electrical_simulation_v1"
internal const val POWER_MAX_AGE_MS = 15_000L

internal fun formatBeaconPowerNumber(value: Double, decimals: Int = 2): String {
    require(decimals in 0..6)
    val quantum = Math.pow(10.0, -decimals.toDouble())
    return String.format(java.util.Locale.getDefault(),
        if (value != 0.0 && abs(value) < quantum) "%.4g" else "%.${decimals}f", value)
}

internal data class BeaconPowerPlotScale(val low: Double, val high: Double) {
    val span: Double get() = high - low
}
internal fun beaconPowerPlotScale(values: List<Double>): BeaconPowerPlotScale {
    val low = minOf(0.0, values.minOrNull() ?: 0.0)
    val high = maxOf(0.0, values.maxOrNull() ?: 0.0)
    return BeaconPowerPlotScale(low, if (low == high) low + 1 else high)
}

/** Same v1 read transport, no persistence, credentials, discovery, commands or ACK. */
internal suspend fun readBeaconPowerDiagnostic(device: BeaconDevice,
    info: suspend (BeaconDevice) -> Result<BeaconDevice>,
    status: suspend (BeaconDevice) -> Result<BeaconRuntimeStatus>,
    elapsed: () -> Long = { System.nanoTime() / 1_000_000 }): Result<BeaconRuntimeStatus> = try {
    val verified = info(device).getOrThrow()
    require(verified.id == device.id && verified.protocolVersion == 1)
    val started = elapsed()
    val result = status(verified).getOrThrow()
    val roundTrip = elapsed() - started
    require(roundTrip >= 0)
    // Conservative freshness: a slowly delivered response must not arrive as brand new.
    Result.success(result.copy(electrical = result.electrical?.let {
        it.copy(sourceAgeMs = it.sourceAgeMs + roundTrip.coerceAtMost(POWER_MAX_AGE_MS + 1))
    }))
} catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
catch (error: Exception) { Result.failure(error) }

enum class BeaconPowerCondition { WAITING, UNSUPPORTED, INVALID, VALID, PARTIAL, SENSOR_DISCONNECTED, STALE, OFFLINE }
enum class BeaconPowerOrigin { REAL, SIMULATED, UNAVAILABLE }

/** One cold stream per visible panel; only transport results can become observations. */
internal fun beaconPowerUpdates(
    read: suspend () -> Result<BeaconRuntimeStatus>?,
    waitForNext: suspend () -> Unit = { delay(5000) }
): Flow<Result<BeaconRuntimeStatus>> = flow {
    while (currentCoroutineContext().isActive) {
        read()?.let { emit(it) }
        waitForNext()
    }
}

/** Diagnostic-only, deliberately unrelated to BeaconSample/HistoryStore. Time is boot uptime, not UTC. */
data class BeaconElectricalReading(
    val bootId: Long, val captureUptimeMs: Long, val circuit: String, val circuitIndex: Int,
    val validity: BeaconPowerCondition, val energyBasis: String,
    val voltageV: Double?, val currentA: Double?, val powerW: Double?, val energyWh: Double?,
    val batteryPercent: Double?, val remainingWh: Double?, val charging: Boolean?, val autonomySeconds: Double?,
    val source: String, val origin: BeaconPowerOrigin
)

data class BeaconPowerPayload(
    val condition: BeaconPowerCondition,
    val reading: BeaconElectricalReading? = null,
    val sourceAgeMs: Long = 0
)

internal object BeaconPowerParser {
    fun parse(status: JSONObject, capabilities: Set<String>): BeaconPowerPayload {
        if (!status.has("electrical") || status.isNull("electrical")) return BeaconPowerPayload(
            if (POWER_SIMULATION_CAPABILITY in capabilities) BeaconPowerCondition.WAITING else BeaconPowerCondition.UNSUPPORTED)
        return runCatching {
            val json = status.get("electrical") as? JSONObject ?: error("object")
            if (integer(json, "schema_version") != 1L) return BeaconPowerPayload(BeaconPowerCondition.UNSUPPORTED)
            val source = json.opt("source") as? String ?: error("source")
            val simulated = json.opt("simulated") as? Boolean ?: error("simulation provenance")
            val origin = when {
                source == "simulator" && simulated -> {
                    // This is the capability the CURRENT v0.1 firmware actually advertises.
                    if (POWER_SIMULATION_CAPABILITY !in capabilities) return BeaconPowerPayload(BeaconPowerCondition.UNSUPPORTED)
                    BeaconPowerOrigin.SIMULATED
                }
                source in setOf("ina228", "solar_controller", "bms") && !simulated -> BeaconPowerOrigin.REAL
                else -> error("inconsistent origin")
            }
            require(!json.has("timestamp_ms") || json.isNull("timestamp_ms")) // v0.1 has no absolute electrical clock
            val captured = integer(json, "capture_uptime_ms") ?: error("capture")
            val boot = integer(json, "boot_id") ?: error("boot")
            val outerBoot = integer(status, "boot_id") ?: error("outer boot")
            val uptime = integer(status, "beacon_uptime_ms") ?: error("uptime")
            require(boot > 0 && boot == outerBoot && captured <= uptime)
            val circuit = json.get("circuit") as? String ?: error("circuit")
            require(circuit in setOf("starlink_load", "battery_bus", "solar_input"))
            val index = integer(json, "circuit_index") ?: error("index")
            require(index <= 65535)
            val basis = json.get("energy_basis") as? String ?: error("basis")
            require(basis in setOf("unknown", "net_since_sensor_start", "device_counter"))
            var malformed = false
            fun number(key: String, signed: Boolean = true, maximum: Double = 1e12): Double? {
                if (!json.has(key) || json.isNull(key)) return null
                val value = (json.opt(key) as? Number)?.toDouble()
                if (value == null || !value.isFinite() || abs(value) > maximum || !signed && value < 0) {
                    malformed = true; return null
                }
                return value
            }
            val voltage = number("voltage_v", false)
            val current = number("current_a")
            var power = number("power_w")
            val energy = number("energy_wh")
            val percent = number("battery_percent", false, 100.0)
            val remaining = number("remaining_wh", false)
            val autonomy = number("autonomy_seconds", false)
            val charging = if (!json.has("charging") || json.isNull("charging")) null else
                (json.opt("charging") as? Boolean).also { if (it == null) malformed = true }
            if (voltage != null && current != null && power != null &&
                abs(voltage * current - power) > .02 + abs(voltage * current) * .001) {
                power = null; malformed = true
            }
            val wireValidity = when (json.opt("validity")) {
                "valid" -> BeaconPowerCondition.VALID
                "partial" -> BeaconPowerCondition.PARTIAL
                "disconnected" -> BeaconPowerCondition.SENSOR_DISCONNECTED
                "unavailable" -> BeaconPowerCondition.WAITING
                "invalid" -> BeaconPowerCondition.INVALID
                else -> error("validity")
            }
            val usable = wireValidity in setOf(BeaconPowerCondition.VALID, BeaconPowerCondition.PARTIAL)
            val condition = when {
                malformed -> BeaconPowerCondition.INVALID
                wireValidity == BeaconPowerCondition.VALID && listOf(voltage, current, power, energy).any { it == null } -> BeaconPowerCondition.PARTIAL
                else -> wireValidity
            }
            val reading = BeaconElectricalReading(boot, captured, circuit, index.toInt(), condition, basis,
                voltage.takeIf { usable }, current.takeIf { usable }, power.takeIf { usable }, energy.takeIf { usable },
                percent.takeIf { usable }, remaining.takeIf { usable }, charging.takeIf { usable }, autonomy.takeIf { usable }, source, origin)
            BeaconPowerPayload(condition, reading, uptime - captured)
        }.getOrElse { BeaconPowerPayload(BeaconPowerCondition.INVALID) }
    }

    private fun integer(json: JSONObject, key: String): Long? {
        val n = (json.opt(key) as? Number)?.toDouble() ?: return null
        // Integer-valued, nonnegative and exactly representable by the JSON reader.
        return n.takeIf { it.isFinite() && it in 0.0..9_007_199_254_740_991.0 && it % 1.0 == 0.0 }?.toLong()
    }
}

internal data class BeaconPowerPoint(val uptimeMs: Long, val voltage: Double?, val power: Double?, val breakBefore: Boolean)

/** Bounded volatile observations only; never persists or acknowledges anything. */
internal data class BeaconPowerSession(
    val gapMs: Long = POWER_MAX_AGE_MS,
    val deviceKey: String? = null,
    val payload: BeaconPowerPayload = BeaconPowerPayload(BeaconPowerCondition.WAITING),
    val receivedAt: Long? = null,
    val sourceAgeMs: Long = 0,
    val online: Boolean = true,
    val breakNext: Boolean = false,
    val points: List<BeaconPowerPoint> = emptyList()
) {
    // Retain the provenance warning while stale/offline observations are still on the chart.
    val origin: BeaconPowerOrigin get() = payload.reading?.origin ?: BeaconPowerOrigin.UNAVAILABLE
    fun ageMs(now: Long): Long? = receivedAt?.takeIf { now >= it }?.let { sourceAgeMs + (now - it) }
    fun condition(now: Long): BeaconPowerCondition = when {
        !online -> BeaconPowerCondition.OFFLINE
        receivedAt != null && (now < receivedAt || now - receivedAt > POWER_MAX_AGE_MS - sourceAgeMs) -> BeaconPowerCondition.STALE
        else -> payload.condition
    }
    fun visible(now: Long): BeaconElectricalReading? = payload.reading.takeIf {
        condition(now) in setOf(BeaconPowerCondition.VALID, BeaconPowerCondition.PARTIAL)
    }
    fun disconnected() = copy(online = false, breakNext = true)
    fun failed(error: Throwable): BeaconPowerSession = if (error is org.json.JSONException || error is BeaconPowerContractException)
        copy(payload = payload.copy(condition = BeaconPowerCondition.INVALID), online = true, breakNext = true)
    else disconnected()
    fun accept(key: String, incoming: BeaconPowerPayload, now: Long): BeaconPowerSession {
        val next = incoming.reading
        if (next == null) return copy(deviceKey = key, payload = incoming, receivedAt = null,
            online = true, points = emptyList(), breakNext = true)
        val old = payload.reading
        val same = key == deviceKey && old?.bootId == next.bootId && old.circuit == next.circuit &&
            old.circuitIndex == next.circuitIndex && old.source == next.source && old.origin == next.origin
        if (same && next.captureUptimeMs < old!!.captureUptimeMs)
            return copy(payload = payload.copy(condition = BeaconPowerCondition.INVALID), breakNext = true)
        if (same && next.captureUptimeMs == old!!.captureUptimeMs) {
            // Re-reading a cached capture must not extend freshness or create chart samples.
            if (incoming.reading != old || incoming.condition != payload.condition)
                return copy(payload = incoming.copy(condition = BeaconPowerCondition.INVALID), online = true, breakNext = true)
            val elapsed = receivedAt?.let { (now - it).coerceAtLeast(0) } ?: 0
            return copy(online = true, sourceAgeMs = maxOf(sourceAgeMs, incoming.sourceAgeMs - elapsed))
        }
        val retained = if (same) points else emptyList()
        val usable = incoming.condition in setOf(BeaconPowerCondition.VALID, BeaconPowerCondition.PARTIAL) && incoming.sourceAgeMs <= POWER_MAX_AGE_MS
        val point = BeaconPowerPoint(next.captureUptimeMs, next.voltageV.takeIf { usable }, next.powerW.takeIf { usable },
            !same || breakNext || retained.lastOrNull()?.let { next.captureUptimeMs - it.uptimeMs > gapMs } == true)
        return copy(deviceKey = key, payload = incoming, receivedAt = now, sourceAgeMs = incoming.sourceAgeMs,
            online = true, breakNext = false, points = (retained + point).takeLast(120))
    }
}
