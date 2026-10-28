package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.model.DishySnapshot

internal enum class ConnectionTestKind { NORMAL, EXTREME }
internal enum class ConnectionTestPhase { IDLE, STARTING, RUNNING, BETWEEN_ROUNDS, COMPLETED, INTERRUPTED, FAILED }
internal data class ConnectionTestState(
    val id: Long = 0L,
    val kind: ConnectionTestKind = ConnectionTestKind.NORMAL,
    val phase: ConnectionTestPhase = ConnectionTestPhase.IDLE,
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val total: Int = 1,
    val completed: Int = 0,
    val result: SpeedTestResult? = null,
    val runs: List<ExtremeRun> = emptyList(),
    val extreme: ExtremeSession? = null,
    val error: String? = null,
    val origin: TestOrigin = TestOrigin.UNKNOWN,
    // Ephemeral presentation only; not persisted, replayed or used in result calculations.
    val liveActivity: SpeedTestActivity = SpeedTestActivity.UNKNOWN
) {
    val active get() = phase in setOf(ConnectionTestPhase.STARTING, ConnectionTestPhase.RUNNING, ConnectionTestPhase.BETWEEN_ROUNDS)
    val currentRound get() = (completed + 1).coerceAtMost(total)
}

internal data class TestMetrics(val download: Float? = null, val upload: Float? = null, val latency: Float? = null)
internal object ConnectionTestPresentation {
    const val NOTIFICATION_INTERVAL_MS = 3000L
    fun valid(value: Float?) = value?.takeIf { it.isFinite() && it >= 0f }
    fun metrics(state: ConnectionTestState): TestMetrics {
        val r = state.result ?: return TestMetrics()
        return TestMetrics(valid(r.downloadMbps).takeIf { r.downloadMeasured },
            valid(r.uploadMbps).takeIf { r.uploadMeasured }, valid(r.latencyMs))
    }
    fun chip(state: ConnectionTestState): String? = if (state.active && state.kind == ConnectionTestKind.EXTREME)
        "${state.currentRound}/${state.total}" else null
    fun canStart(state: ConnectionTestState) = !state.active
    fun matchesStop(state: ConnectionTestState, id: Long) = state.active && state.id == id
    fun recovered(state: ConnectionTestState, now: Long) = if (state.active)
        state.copy(phase = ConnectionTestPhase.INTERRUPTED, endedAt = now) else state
    fun supportsLiveUpdate(api: Int) = api >= 36
}

internal enum class MonitorConnection { CONNECTED, NO_DATA, RECONNECTING, UNAVAILABLE }
internal data class MonitorReading(val connection: MonitorConnection, val download: Float? = null,
    val upload: Float? = null, val latency: Float? = null)
internal object MonitorNotificationModel {
    const val UPDATE_MS = 5000L
    fun reading(snapshot: DishySnapshot?, now: Long, intervalSeconds: Int, previouslyOnline: Boolean): MonitorReading {
        if (snapshot == null || snapshot.timestamp <= 0L || snapshot.state == io.github.strongsand.lshell.core.model.DishState.UNKNOWN) return MonitorReading(MonitorConnection.NO_DATA)
        if (!snapshot.isOnline || snapshot.isFromCache) return MonitorReading(
            if (previouslyOnline) MonitorConnection.RECONNECTING else MonitorConnection.UNAVAILABLE)
        val fresh = maxOf(15000L, intervalSeconds * 1000L + 5000L)
        if (now - snapshot.timestamp !in 0..fresh) return MonitorReading(MonitorConnection.NO_DATA)
        val down = ConnectionTestPresentation.valid(snapshot.throughput.downlinkBps)?.div(1000000f)
        val up = ConnectionTestPresentation.valid(snapshot.throughput.uplinkBps)?.div(1000000f)
        val ping = ConnectionTestPresentation.valid(snapshot.latency.popPingLatencyMs)
        return MonitorReading(if (down == null && up == null && ping == null) MonitorConnection.NO_DATA
            else MonitorConnection.CONNECTED, down, up, ping)
    }
}
