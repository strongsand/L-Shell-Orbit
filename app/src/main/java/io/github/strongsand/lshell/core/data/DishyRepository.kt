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
    val powerWatts: Float? = null
)

data class ConnectionEvent(val timestamp: Long, val description: String)

class DishyRepository(
    private val grpcClient: DishyGrpcClient = DishyGrpcClient(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())
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

    private val _events = MutableStateFlow<List<ConnectionEvent>>(emptyList())
    val events: StateFlow<List<ConnectionEvent>> = _events.asStateFlow()

    private val _history = MutableStateFlow<DishyGrpcClient.HistoryReading?>(null)
    val history: StateFlow<DishyGrpcClient.HistoryReading?> = _history.asStateFlow()

    private var lastKnownValidSnapshot: DishySnapshot? = null
    private var pollingJob: Job? = null

    fun startMonitoring(pollIntervalMs: Long = 2000L) {
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
                    nextHistoryAttemptAt = System.currentTimeMillis() + 5_000L
                    grpcClient.fetchHistory().onSuccess { history ->
                        _history.value = history
                        val historical = history.asSamples(System.currentTimeMillis())
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
                delay(pollIntervalMs)
            }
        }
    }

    private fun recordEvent(timestamp: Long, description: String) {
        _events.value = (listOf(ConnectionEvent(timestamp, description)) + _events.value).take(40)
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
    val count = minOf(downloadMbps.size, uploadMbps.size, latencyMs.size, dropPercent.size)
    if (count == 0 || current <= 0L) return emptyList()
    val firstCounter = maxOf(0L, current - count, current - 600L)
    return (firstCounter until current).mapNotNull { counter ->
        val index = (counter % count).toInt()
        val down = downloadMbps[index]
        val up = uploadMbps[index]
        val ping = latencyMs[index]
        val drop = dropPercent[index]
        val power = powerWatts.takeIf { it.isNotEmpty() }
            ?.let { it[(counter % it.size).toInt()] }
            ?.takeIf { it.isFinite() && it > 0f }
        if (!listOf(down, up, ping, drop).all { it.isFinite() }) null
        else TelemetrySample(now - (current - 1L - counter) * 1_000L,
            down.coerceAtLeast(0f), up.coerceAtLeast(0f), ping.coerceAtLeast(0f),
            drop.coerceIn(0f, 100f), power)
    }
}

