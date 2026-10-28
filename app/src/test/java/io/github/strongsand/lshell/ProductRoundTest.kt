package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test
import io.github.strongsand.lshell.beacon.BeaconSampleSource

class ProductRoundTest {
    @Test fun newAutomationSettingsRoundTripThroughBackupWithoutAdmittingRuntimeOrBypassingConsent() {
        val prefs = listOf(BackupPreference("automatic_speed_tests", "interval", 4),
            BackupPreference("automatic_speed_tests", "cooldown", 3), BackupPreference("automatic_speed_tests", "smart_limit", 8),
            BackupPreference("automatic_speed_tests", "smart", true))
        val json = BackupSettingsCodec.encode(prefs)
        assertEquals(4, BackupSettingsCodec.plan(json, true).compatible.size)
        assertEquals(3, BackupSettingsCodec.plan(json, false).compatible.size)
        assertTrue(BackupSettingsCodec.plan(json, false).automationRequested)
    }
    @Test fun routineTelemetryNeverCreatesAnOccurrence() {
        val rows = (0..120).map { HistoryEntry(it * 1000L, "beacon_history", "", 10f, 1f) }
        assertTrue(OccurrenceHistory.compose(listOf(ReportMoment(0, "Maior tráfego de download observado", "10 Mbps")), rows, emptyList()).isEmpty())
    }
    @Test fun consolidatedEventsKeepActualDurationAndRecoveryWithoutDuplicateRawEvent() {
        val moments = listOf(ReportMoment(1000, "Conexão perdida", "3 s"), ReportMoment(4000, "Conexão restaurada", "Falha durou 3 s"))
        val events = listOf(HistoryEntry(1000, "event", "Conexão com a antena perdida"), HistoryEntry(4000, "event", "Conexão com a antena restabelecida"))
        val list = OccurrenceHistory.compose(moments, events, emptyList())
        assertEquals(2, list.size); assertEquals("Falha durou 3 s", list.first().detail)
        assertTrue(list.all { it.category == OccurrenceCategory.CONNECTION })
    }
    @Test fun orphanRecoveryAndResolutionAreNotDiscardedOrGivenInventedDurations() {
        val events = listOf(HistoryEntry(1000, "event", "Conexão com a antena restabelecida"), HistoryEntry(2000, "event", "Alerta resolvido: Obstrução"))
        val list = OccurrenceHistory.compose(emptyList(), events, emptyList())
        assertEquals(2, list.size); assertTrue(list.all { it.detail.isEmpty() })
    }
    @Test fun recordedTestReplacesLegacySummaryWithoutDeletingRecord() {
        val test = RecordedTest("test", TestOrigin.EXTREME, 1000, 4000, "COMPLETED", listOf(10.0), listOf(1.0), listOf(20.0))
        val list = OccurrenceHistory.compose(listOf(ReportMoment(4000, "Teste Extremo")),
            listOf(HistoryEntry(4000, "event", "Teste Extremo concluído")), listOf(test))
        assertEquals(1, list.size); assertSame(test, list.single().test)
    }
    @Test fun rawKeysAreStableWhenAnotherEventIsImported() {
        val event = HistoryEntry(1000, "event", "Beacon conectado", source = BeaconSampleSource.BEACON)
        val first = OccurrenceHistory.compose(emptyList(), listOf(event), emptyList()).single()
        val expanded = OccurrenceHistory.compose(emptyList(), listOf(HistoryEntry(500, "event", "Outro evento"), event), emptyList())
        assertEquals(first.key, expanded.first { it.time == 1000L }.key)
        assertEquals(BeaconSampleSource.BEACON, first.source)
    }
    @Test fun oldNormalResultIsNotFollowedByMenuEntry() {
        val completed = ConnectionTestState(id = 10, phase = ConnectionTestPhase.COMPLETED)
        assertNull(SpeedTestNavigation.followedId(completed, null, null))
        assertEquals(10L, SpeedTestNavigation.followedId(completed, 10, null))
    }
    @Test fun activeNormalIsRestoredAndOwnCompletionRemainsSelectedAcrossRecreation() {
        val active = ConnectionTestState(id = 20, phase = ConnectionTestPhase.RUNNING)
        val followed = SpeedTestNavigation.followedId(active, null, null)
        assertEquals(20L, followed)
        assertEquals(20L, SpeedTestNavigation.followedId(active.copy(phase = ConnectionTestPhase.COMPLETED), null, followed))
        assertNull(SpeedTestNavigation.followedId(active.copy(kind = ConnectionTestKind.EXTREME), null, null))
    }
    @Test fun trafficInspectionUsesOriginalIndependentMetricsAndHonestGaps() {
        val rows = listOf(HistoryEntry(1000, "beacon_history", "", downloadMbps = 0f, uploadMbps = 2f,
            latencyMs = 20f, dropPercent = Float.NaN, source = BeaconSampleSource.BEACON),
            HistoryEntry(2000, "terminal_history", "", downloadMbps = Float.NaN, uploadMbps = 3f))
        val a = TrafficSessionAnalyzer.analyze(rows, 1000, 20000)
        val series = TrafficChartPresentation.series(a, TrafficMetric.DOWNLOAD)
        assertEquals(a.points.size, series.points.size)
        assertEquals(0.0, series.points.first().measurement!!.value, 0.0)
        assertEquals(TrafficOrigin.BEACON, series.points.first().measurement!!.origin)
        assertNull(series.points.last().measurement)
        assertNull(MetricChartPresentation.inspect(series, 7000))
        assertNull(TrafficChartPresentation.exactPoint(a.points, 1500))
        val p = TrafficChartPresentation.exactPoint(a.points, 1000)!!
        assertEquals(2.0, p.upload!!.value, 0.0); assertNull(p.loss)
    }
    @Test fun denseMonitorReductionRetainsTruePeakAndDoesNotChangeSamples() {
        val rows = (1..10000).map { HistoryEntry(it * 1000L, "terminal_history", "", downloadMbps = if (it == 7777) 900f else 1f) }
        val a = TrafficSessionAnalyzer.analyze(rows, 0, 10001000)
        val s = TrafficChartPresentation.series(a, TrafficMetric.DOWNLOAD)
        val plot = MetricChartPresentation.reduce(s, MetricTimeViewport(s.start, s.end))
        assertEquals(10000, s.points.size); assertTrue(plot.size <= 1440)
        assertEquals(900.0, plot.mapNotNull { it.value }.max(), 0.0)
    }
}
