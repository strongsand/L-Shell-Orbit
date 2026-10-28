// =========================================================================
// PROJETO: L-Shell Orbit (L-Shell Orbit Android)
// ARQUIVO: DishyRepository.kt
// PACOTE: io.github.strongsand.lshell.core.data
// VERSÃO: v1.2 (2026-09-20)
//
// CHANGELOG:
// - [v1.2 | 2026-09-20]: Padronização de imports para 'io.github.strongsand.lshell.*'.
// - [v1.0 | 2026-09-20]: Repositório com StateFlow, polling contínuo e cache offline.
// =========================================================================

package io.github.strongsand.lshell.core.data
import io.github.strongsand.lshell.core.network.grpc.terminalHistorySize
import io.github.strongsand.lshell.core.network.grpc.terminalHistoryValue

import io.github.strongsand.lshell.core.model.DishState
import io.github.strongsand.lshell.core.model.DishySnapshot
import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TelemetrySample(
    val timestamp: Long,
    val downloadMbps: Float,
    val uploadMbps: Float,
    val latencyMs: Float,
    val dropPercent: Float,
    val powerWatts: Float? = null,
    /** Distinguishes actual 1 Hz history from live status observations. */
    val isHistory: Boolean = false
)

data class ConnectionEvent(val timestamp: Long, val description: String)

class DishyRepository(
    private val grpcClient: DishyGrpcClient = DishyGrpcClient(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()),
    private val onStatus: suspend (DishySnapshot) -> Unit = {},
    private val onHistory: suspend (DishyGrpcClient.HistoryReading, DishySnapshot) -> Unit = { _, _ -> },
    private val onEvent: (ConnectionEvent) -> Unit = {}
) {

    private val _telemetryFlow = MutableStateFlow(
        DishySnapshot(
            state = DishState.UNKNOWN,
            isOnline = false,
            isFromCache = false,
            errorMessage = "Aguardando primeira leitura..."
        )
    )
    val telemetryFlow: StateFlow<DishySnapshot> = _telemetryFlow.asStateFlow()

    private val _samples = MutableStateFlow<List<TelemetrySample>>(emptyList())
    val samples: StateFlow<List<TelemetrySample>> = _samples.asStateFlow()

    private val _livePower = MutableStateFlow<LivePowerReading?>(null)
    val livePower: StateFlow<LivePowerReading?> = _livePower.asStateFlow()

    private val _events = MutableStateFlow<List<ConnectionEvent>>(emptyList())
    val events: StateFlow<List<ConnectionEvent>> = _events.asStateFlow()

    private val _history = MutableStateFlow<DishyGrpcClient.HistoryReading?>(null)
    val history: StateFlow<DishyGrpcClient.HistoryReading?> = _history.asStateFlow()

    private var lastKnownValidSnapshot: DishySnapshot? = null
    private var pollingJob: Job? = null
    @Volatile private var intervalMs = 2000L
    @Volatile private var historyIntervalMs = 5000L

    fun startMonitoring(pollIntervalMs: Long = 2000L, historyPollIntervalMs: Long = 5000L) {
        intervalMs = pollIntervalMs.coerceAtLeast(1000L)
        historyIntervalMs = historyPollIntervalMs.coerceAtLeast(5000L)
        if (pollingJob?.isActive == true) return

        pollingJob = scope.launch {
            var nextHistoryAttemptAt = 0L
            while (isActive) {
                val result = grpcClient.fetchStatus()
                result.fold(
                    onSuccess = { snapshot ->
                        val previous = _telemetryFlow.value
                        if (previous.isOnline && previous.state != snapshot.state) {
                            recordEvent(snapshot.timestamp, "${previous.state.displayName} → ${snapshot.state.displayName}")
                        } else if (!previous.isOnline && previous.timestamp != 0L && lastKnownValidSnapshot != null) {
                            recordEvent(snapshot.timestamp, "Conexão restabelecida")
                        }
                        _samples.value = (_samples.value + TelemetrySample(
                            snapshot.timestamp,
                            snapshot.throughput.downlinkMbps,
                            snapshot.throughput.uplinkMbps,
                            snapshot.latency.popPingLatencyMs,
                            snapshot.latency.popPingDropRate * 100f
                        )).filter { it.timestamp >= snapshot.timestamp - 600_000L }.takeLast(650)
                        lastKnownValidSnapshot = snapshot
                        _telemetryFlow.value = snapshot
                        onStatus(snapshot)
                    },
                    onFailure = { error ->
                        if (_telemetryFlow.value.isOnline) {
                            recordEvent(System.currentTimeMillis(), "Conexão com a antena perdida")
                        }
                        val offlineSnapshot = DishySnapshot.offlineFallback(
                            cached = lastKnownValidSnapshot,
                            reason = error.message ?: "Conexão perdida com a antena."
                        )
                        _telemetryFlow.value = offlineSnapshot
                    }
                )
                if (result.isSuccess && System.currentTimeMillis() >= nextHistoryAttemptAt) {
                    // power_in is a 1 Hz ring buffer. Refresh the existing history request every
                    // five seconds so the displayed "current" wattage does not lag by a minute.
                    nextHistoryAttemptAt = System.currentTimeMillis() + historyIntervalMs
                    grpcClient.fetchHistory().onSuccess { history ->
                        onHistory(history, _telemetryFlow.value)
                        _history.value = history
                        val projectedAt = System.currentTimeMillis()
                        val historical = history.asSamples(projectedAt)
                        _livePower.value = history.livePower(projectedAt)
                        if (historical.isNotEmpty()) {
                            val cutoff = System.currentTimeMillis() - 600_000L
                            // When both sources land in the same second, keep the history sample:
                            // status samples do not contain power_in.
                            _samples.value = (_samples.value + historical)
                                .filter { it.timestamp >= cutoff }
                                .associateBy { it.timestamp / 1_000L }
                                .values.sortedBy { it.timestamp }.takeLast(650)
                        }
                    }
                }
                delay(intervalMs)
            }
        }
    }

    private fun recordEvent(timestamp: Long, description: String) {
        _events.value = (listOf(ConnectionEvent(timestamp, description)) + _events.value).take(40)
        onEvent(ConnectionEvent(timestamp, description))
    }

    fun stopMonitoring() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun close() {
        stopMonitoring()
        grpcClient.close()
    }

    suspend fun queryImmediateSnapshot(): DishySnapshot {
        val result = grpcClient.fetchStatus()
        return result.getOrElse { error ->
            DishySnapshot.offlineFallback(
                cached = lastKnownValidSnapshot,
                reason = error.message ?: "Falha ao consultar a antena."
            )
        }.also { snapshot ->
            if (snapshot.isOnline) {
                lastKnownValidSnapshot = snapshot
                _telemetryFlow.value = snapshot
            }
        }
    }
}

private fun DishyGrpcClient.HistoryReading.asSamples(now: Long): List<TelemetrySample> {
    val count = terminalHistorySize(this)
    if (count == 0 || current <= 0L) return emptyList()
    val firstCounter = maxOf(0L, current - count, current - 600L)
    return (firstCounter until current).mapNotNull { counter ->
        val down = terminalHistoryValue(downloadMbps, current, counter)?.takeIf { it >= 0f }
        val up = terminalHistoryValue(uploadMbps, current, counter)?.takeIf { it >= 0f }
        val ping = terminalHistoryValue(latencyMs, current, counter)?.takeIf { it > 0f }
        val drop = terminalHistoryValue(dropPercent, current, counter)?.takeIf { it in 0f..100f }
        val power = terminalHistoryValue(powerWatts, current, counter)?.takeIf { it > 0f }
        // Other existing consumers expect complete samples. The DB import retains
        // independent optional metrics even when this legacy live projection cannot.
        if (down == null || up == null || ping == null || drop == null) null
        else TelemetrySample(now - (current - 1L - counter) * 1_000L,
            down, up, ping, drop, power, isHistory = true)
    }
}

