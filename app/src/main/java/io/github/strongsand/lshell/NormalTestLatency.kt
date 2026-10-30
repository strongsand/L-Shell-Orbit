package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.model.DishySnapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal data class NormalLatencyReading(val milliseconds: Float, val sampledAt: Long)

/** An observation of existing gRPC telemetry, never a second poll or speedtest RTT. */
internal fun normalPopLatency(snapshot: DishySnapshot, startedAt: Long, now: Long): NormalLatencyReading? {
    if (!snapshot.isOnline || snapshot.isFromCache || snapshot.timestamp !in startedAt..now ||
        now - snapshot.timestamp !in 0L..15_000L) return null
    // This legacy status model uses 0 for absent scalar fields. Do not promote that
    // ambiguous default to a measured zero. A genuine speed-test zero stays valid below.
    val value = snapshot.latency.popPingLatencyMs.takeIf { it.isFinite() && it > 0f } ?: return null
    return NormalLatencyReading(value, snapshot.timestamp)
}

internal fun normalTestLatencyLabel(result: SpeedTestResult?): Int =
    if (result?.latencySource == TestLatencySource.POP_TELEMETRY) R.string.test_pop_latency else R.string.test_latency

internal fun normalLiveLatency(state: ConnectionTestState, now: Long): Float? =
    ConnectionTestPresentation.valid(state.liveLatencyMs)?.takeIf {
        state.kind == ConnectionTestKind.NORMAL && state.phase == ConnectionTestPhase.RUNNING &&
            state.liveLatencySampledAt?.let { time -> now - time in 0L..15_000L } == true
    }

internal suspend fun normalTestWithLatency(
    readings: StateFlow<DishySnapshot>,
    startedAt: Long,
    now: () -> Long = System::currentTimeMillis,
    onReading: (NormalLatencyReading?) -> Unit = {},
    runTest: suspend () -> SpeedTestResult
): SpeedTestResult = coroutineScope {
    val observer = launch(start = CoroutineStart.UNDISPATCHED) {
        readings.collect { snapshot ->
            onReading(normalPopLatency(snapshot, startedAt, now()))
        }
    }
    try {
        val result = runTest()
        // Preserve a real test result first, including a measured zero.
        if (ConnectionTestPresentation.valid(result.latencyMs) != null) result
        else {
            val reading = normalPopLatency(readings.value, startedAt, now())
            result.copy(latencyMs = reading?.milliseconds,
                latencySource = reading?.let { TestLatencySource.POP_TELEMETRY },
                latencySampledAt = reading?.sampledAt)
        }
    } finally {
        observer.cancelAndJoin()
    }
}
