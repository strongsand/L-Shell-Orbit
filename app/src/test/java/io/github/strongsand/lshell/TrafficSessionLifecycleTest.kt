package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.data.LivePowerReading
import io.github.strongsand.lshell.core.model.DishySnapshot
import io.github.strongsand.lshell.core.model.ThroughputStats
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrafficSessionLifecycleTest {
    private fun session() = TrafficSessionMetadata.start("synthetic-session", 10000, "UTC", id = "session-test",
        mode = TrafficSessionMode.DOWNLOAD, bootCount = 8)
    @Test fun startCreatesMetadataBeforeRequestingService() = runBlocking {
        val calls = mutableListOf<String>()
        val result = TrafficSessionLifecycle.start(emptyList(), { calls += "save"; session() },
            { calls += "service:${it.id}" })
        assertEquals(listOf("save", "service:session-test"), calls)
        assertEquals(session(), result)
    }
    @Test fun secondStartReusesTheOneActiveSession() = runBlocking {
        var creations = 0
        var requestedId: String? = null
        val active = session()
        val result = TrafficSessionLifecycle.start(listOf(active), { creations++; error("duplicate") }, { requestedId = it.id })
        assertEquals(0, creations)
        assertEquals(active, result)
        assertEquals(active.id, requestedId)
    }
    @Test fun finishSavesEndBeforeStoppingAndIsIdempotent() = runBlocking {
        val calls = mutableListOf<String>()
        val result = TrafficSessionLifecycle.finish(session(), 20000, false,
            { calls += "save:${it.endedAt}"; it }, { calls += "stop" })
        assertEquals(listOf("save:20000", "stop"), calls)
        assertFalse(result.active)
        assertEquals(result, TrafficSessionLifecycle.finish(result, 30000, false, { error("second save") }, { error("stale stop") }))
    }
    @Test fun failedPersistenceDoesNotStopTheCollector() = runBlocking {
        var stopped = false
        try { TrafficSessionLifecycle.finish(session(), 20000, false, { error("disk unavailable") }, { stopped = true }); fail() }
        catch (_: IllegalStateException) { assertFalse(stopped) }
    }
    @Test fun metadataRetainsIdModeBootAndInterruptionWithLegacyDefaults() {
        val s = session().copy(endedAt = 20000, interrupted = true)
        assertEquals(s, TrafficSessionMetadata.decode(TrafficSessionMetadata.encode(listOf(s))).single())
        val json = JSONObject(TrafficSessionMetadata.encode(listOf(session())))
        json.getJSONArray("sessions").getJSONObject(0).apply { remove("mode"); remove("boot_count"); remove("interrupted") }
        val legacy = TrafficSessionMetadata.decode(json.toString()).single()
        assertEquals(session().id, legacy.id)
        assertEquals(TrafficSessionMode.BOTH, legacy.mode)
        assertEquals(-1, legacy.bootCount)
        assertFalse(legacy.interrupted)
    }
    @Test fun restoreOnlyTheMatchingActiveSessionAndDetectReboot() {
        val active = session()
        assertEquals(active, TrafficSessionNotificationModel.restoreCandidate(listOf(active), null))
        assertEquals(active, TrafficSessionNotificationModel.restoreCandidate(listOf(active), active.id))
        assertNull(TrafficSessionNotificationModel.restoreCandidate(listOf(active), "stale-session"))
        assertNull(TrafficSessionNotificationModel.restoreCandidate(listOf(active.copy(endedAt = 20000)), null))
        assertFalse(TrafficSessionNotificationModel.requiresInterruption(active, 8))
        assertTrue(TrafficSessionNotificationModel.requiresInterruption(active, 9))
        assertFalse(TrafficSessionNotificationModel.requiresInterruption(active.copy(bootCount = -1), 9))
    }
    private fun snapshot(down: Float = 184000000f, up: Float = 7200000f) =
        DishySnapshot(timestamp = 10000, isOnline = true, throughput = ThroughputStats(down, up))
    @Test fun notificationUsesActualRecentValuesAndSessionMode() {
        val s = snapshot()
        val power = LivePowerReading(10000, 46.5f)
        assertEquals(TrafficNotificationReading(184f, 7.2f), TrafficSessionNotificationModel.reading(s, 10000, TrafficSessionMode.BOTH))
        assertEquals(TrafficNotificationReading(184f, 7.2f, 46.5f), TrafficSessionNotificationModel.reading(s, 10000, TrafficSessionMode.BOTH, power))
        assertEquals(TrafficNotificationReading(null, null, 46.5f), TrafficSessionNotificationModel.reading(s, 10000, TrafficSessionMode.ENERGY, power))
        assertNull(TrafficSessionNotificationModel.reading(s, 10000, TrafficSessionMode.DOWNLOAD, power).power)
        assertNull(TrafficSessionNotificationModel.reading(s, 10000, TrafficSessionMode.DOWNLOAD).upload)
        assertNull(TrafficSessionNotificationModel.reading(s, 10000, TrafficSessionMode.UPLOAD).download)
        assertTrue(TrafficSessionNotificationModel.reading(s, 25000, TrafficSessionMode.BOTH).hasData)
        assertFalse(TrafficSessionNotificationModel.reading(s, 25001, TrafficSessionMode.BOTH).hasData)
        assertFalse(TrafficSessionNotificationModel.reading(s, 9999, TrafficSessionMode.BOTH).hasData)
        assertFalse(TrafficSessionNotificationModel.reading(s.copy(isFromCache = true), 10000, TrafficSessionMode.BOTH).hasData)
        assertFalse(TrafficSessionNotificationModel.reading(s.copy(isOnline = false), 10000, TrafficSessionMode.BOTH).hasData)
    }
    @Test fun nonfiniteAndInvalidDoNotBecomeZeroButRealZeroIsValid() {
        assertFalse(TrafficSessionNotificationModel.reading(snapshot(Float.NaN, Float.POSITIVE_INFINITY), 10000, TrafficSessionMode.BOTH).hasData)
        val reading = TrafficSessionNotificationModel.reading(snapshot(-1f, 0f), 10000, TrafficSessionMode.BOTH)
        assertNull(reading.download)
        assertEquals(0f, reading.upload)
    }
    @Test fun chipIsShortUsesRelevantDirectionAndFallsBackToSystemClock() {
        assertEquals("↓184M", TrafficSessionNotificationModel.chip(TrafficNotificationReading(184f, 7.2f)))
        assertEquals("↑20M", TrafficSessionNotificationModel.chip(TrafficNotificationReading(null, 20f)))
        assertEquals("↓250k", TrafficSessionNotificationModel.chip(TrafficNotificationReading(.25f, null)))
        assertEquals("↓1.2G", TrafficSessionNotificationModel.chip(TrafficNotificationReading(1200f, null)))
        assertNull(TrafficSessionNotificationModel.chip(TrafficNotificationReading(0f, .01f)))
        assertNull(TrafficSessionNotificationModel.chip(TrafficNotificationReading(null, null)))
        assertEquals(0, TrafficSessionNotificationModel.criticalMetricIndex(TrafficNotificationReading(184f, 7.2f)))
        assertEquals(1, TrafficSessionNotificationModel.criticalMetricIndex(TrafficNotificationReading(1f, 20f)))
        assertEquals(0, TrafficSessionNotificationModel.criticalMetricIndex(TrafficNotificationReading(null, 20f)))
        assertEquals(2, TrafficSessionNotificationModel.criticalMetricIndex(TrafficNotificationReading(0f, .01f)))
        assertEquals(1, TrafficSessionNotificationModel.criticalMetricIndex(TrafficNotificationReading(null, 0f)))
        assertEquals(0, TrafficSessionNotificationModel.criticalMetricIndex(TrafficNotificationReading(null, null)))
        assertEquals("big_text", TrafficSessionNotificationModel.notificationVariant(35))
        assertEquals("promoted_big_text", TrafficSessionNotificationModel.notificationVariant(36))
        assertEquals("metric", TrafficSessionNotificationModel.notificationVariant(37))
    }
    @Test fun onePollingLeaseMapSurvivesUiDestructionAndStopsOnlyWhenAllRelease() {
        val leases = TelemetryLeases()
        leases.acquire("ui:first", 2000); leases.acquire("ui:second", 2000)
        assertFalse(leases.persistStatus)
        assertEquals(5000L, leases.historyInterval)
        leases.acquire(TelemetryLeases.MONITOR, 60000); leases.acquire(TelemetryLeases.SESSION, 1000)
        assertEquals(1000L, leases.interval)
        assertTrue(leases.persistStatus)
        assertEquals(1000L, leases.statusPersistenceInterval)
        assertFalse(leases.persistEvents)
        leases.release("ui:first"); leases.release("ui:second")
        assertEquals(1000L, leases.interval)
        leases.release(TelemetryLeases.SESSION)
        assertEquals(60000L, leases.interval)
        assertEquals(60000L, leases.statusPersistenceInterval)
        assertEquals(60000L, leases.historyInterval)
        leases.release(TelemetryLeases.MONITOR)
        assertNull(leases.interval)
        assertFalse(leases.persistStatus)
    }
}
