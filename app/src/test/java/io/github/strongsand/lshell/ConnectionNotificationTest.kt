package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionNotificationTest {
    private fun active() = ConnectionTestState(id = 42, kind = ConnectionTestKind.EXTREME,
        phase = ConnectionTestPhase.RUNNING, startedAt = 1000, total = 10, completed = 3)
    @Test fun activeExecutionCannotStartAgainAndStopRequiresMatchingIdentity() {
        assertFalse(ConnectionTestPresentation.canStart(active()))
        assertTrue(ConnectionTestPresentation.matchesStop(active(), 42))
        assertFalse(ConnectionTestPresentation.matchesStop(active(), 41))
        assertFalse(ConnectionTestPresentation.matchesStop(active().copy(phase = ConnectionTestPhase.COMPLETED), 42))
    }
    @Test fun roundChipAndProgressUseActualCompletedRounds() {
        assertEquals("4/10", ConnectionTestPresentation.chip(active()))
        assertEquals(3, active().completed)
        assertEquals(10, active().total)
        assertEquals("10/10", ConnectionTestPresentation.chip(active().copy(completed = 10)))
        assertNull(ConnectionTestPresentation.chip(active().copy(kind = ConnectionTestKind.NORMAL)))
    }
    @Test fun processRecoveryRetainsIdentityAndMarksInterruptionInsteadOfRestarting() {
        val restored = ConnectionTestPresentation.recovered(active(), 9000)
        assertEquals(42L, restored.id)
        assertEquals(3, restored.completed)
        assertEquals(ConnectionTestPhase.INTERRUPTED, restored.phase)
        assertEquals(9000L, restored.endedAt)
        assertFalse(restored.active)
    }
    @Test fun completedRecoveryDoesNotChangeResultsOrEndTime() {
        val done = active().copy(phase = ConnectionTestPhase.COMPLETED, endedAt = 8000)
        assertEquals(done, ConnectionTestPresentation.recovered(done, 9000))
    }
    @Test fun unavailableMeasurementsAreOmittedWhileMeasuredZeroIsValid() {
        val empty = active().copy(result = SpeedTestResult(0f, 0f, Float.NaN, false, false))
        assertEquals(TestMetrics(), ConnectionTestPresentation.metrics(empty))
        assertEquals(TestMetrics(0f, 0f, null), ConnectionTestPresentation.metrics(empty.copy(result = SpeedTestResult(0f, 0f))))
        assertEquals(TestMetrics(null, null, null), ConnectionTestPresentation.metrics(empty.copy(result = SpeedTestResult(Float.NaN, Float.POSITIVE_INFINITY))))
    }
    @Test fun normalTestHasNoFabricatedPercentOrRoundChip() {
        assertNull(ConnectionTestPresentation.chip(active().copy(kind = ConnectionTestKind.NORMAL)))
        assertFalse(ConnectionTestPresentation.supportsLiveUpdate(35))
        assertTrue(ConnectionTestPresentation.supportsLiveUpdate(36))
    }
    @Test fun staleOrCachedTelemetryDoesNotAppearCurrent() {
        val live = DishySnapshot(timestamp = 1000, state = DishState.CONNECTED, isOnline = true,
            throughput = ThroughputStats(downlinkBps = 1000000f, uplinkBps = 0f))
        assertEquals(1f, MonitorNotificationModel.reading(live, 2000, 1, true).download)
        assertEquals(0f, MonitorNotificationModel.reading(live, 2000, 1, true).upload)
        assertEquals(MonitorConnection.NO_DATA, MonitorNotificationModel.reading(live, 18000, 1, true).connection)
        assertNull(MonitorNotificationModel.reading(live.copy(isFromCache = true), 2000, 1, true).download)
    }
    @Test fun monitoringHonorsExistingCadenceAndDistinguishesOfflineStates() {
        val live = DishySnapshot(timestamp = 1000, state = DishState.CONNECTED, isOnline = true)
        assertEquals(MonitorConnection.CONNECTED, MonitorNotificationModel.reading(live, 60000, 60, true).connection)
        val offline = live.copy(isOnline = false)
        assertEquals(MonitorConnection.RECONNECTING, MonitorNotificationModel.reading(offline, 2000, 1, true).connection)
        assertEquals(MonitorConnection.UNAVAILABLE, MonitorNotificationModel.reading(offline, 2000, 1, false).connection)
        assertEquals(MonitorConnection.NO_DATA, MonitorNotificationModel.reading(null, 2000, 1, false).connection)
    }
    @Test fun executionRecoveryMetadataPreservesMissingMetricsAndRealZero() {
        val run = ExtremeRun(1, 1000, 0f, 2f, null, Float.NaN, Float.POSITIVE_INFINITY,
            "UNKNOWN", emptyList(), 12000, 0)
        val session = ExtremeSession(42, 10, 1000, 0, listOf(run))
        val encoded = session.executionJson()
        val row = encoded.getJSONArray("runs").getJSONObject(0)
        assertTrue(row.isNull("loss"))
        assertTrue(row.isNull("obstruction"))
        assertFalse(row.isNull("down"))
        val restored = executionSessionFromJson(encoded)
        assertEquals(session.id, restored.id)
        assertEquals(0f, restored.runs.single().downloadMbps, 0f)
        assertTrue(restored.runs.single().packetLossPercent.isNaN())
        assertTrue(restored.runs.single().obstructionPercent.isNaN())
    }
    @Test fun invalidLiveMetricsAreNotShownAsZero() {
        val live = DishySnapshot(timestamp = 1000, state = DishState.CONNECTED, isOnline = true,
            throughput = ThroughputStats(downlinkBps = Float.NaN, uplinkBps = Float.POSITIVE_INFINITY),
            latency = LatencyStats(popPingLatencyMs = Float.NaN))
        val reading = MonitorNotificationModel.reading(live, 2000, 2, true)
        assertEquals(MonitorConnection.NO_DATA, reading.connection)
        assertNull(reading.download)
        assertNull(reading.upload)
        assertNull(reading.latency)
    }
}
