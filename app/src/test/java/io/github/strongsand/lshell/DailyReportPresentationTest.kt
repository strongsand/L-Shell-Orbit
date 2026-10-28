package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class DailyReportPresentationTest {
    private fun report(rows: List<ReportInterruption>, offline: Long = 0) = DailyReport(
        start = 0, end = 86_400_000, createdAt = 86_400_000, summary = "Synthetic",
        monitoredMs = 10_000, onlineMs = 10_000 - offline, offlineMs = offline,
        availabilityPercent = null, sampleCount = 0, trafficDownload = null, trafficUpload = null,
        latency = null, latencyAbove100Ms = 0, packetLoss = null, packetLossPeriods = 0,
        packetLossDurationMs = 0, obstruction = null, interruptions = rows, alerts = emptyList(),
        hardwareEvents = emptyList(), timeline = emptyList(), latencyChart = emptyList(),
        lossChart = emptyList(), obstructionChart = emptyList(), trafficChart = emptyList())

    @Test fun zeroInterruptionsHasNoLongestOrFakeEvent() {
        val ui = DailyInterruptionUi.from(report(emptyList()))
        assertEquals(0, ui.count); assertNull(ui.longest)
    }
    @Test fun oneInterruptionPreservesExactIdentityAndDuration() {
        val row = ReportInterruption(1_000, 4_000, "Synthetic context", true)
        val ui = DailyInterruptionUi.from(report(listOf(row), 3_000))
        assertEquals(1, ui.count); assertSame(row, ui.longest)
        assertEquals(3_000L, ui.longest!!.durationMs)
    }
    @Test fun manyInterruptionsRemainAvailableInChronologicalOrder() {
        val rows = (1..500).map { ReportInterruption(it * 10_000L, it * 10_000L + 3_000, "Synthetic", true) }.reversed()
        val ui = DailyInterruptionUi.from(report(rows))
        assertEquals(500, ui.count)
        assertEquals(rows.asReversed(), ui.rows)
        assertEquals(5_000_000L, rows.first().start) // input was not rewritten/sorted in place
    }
    @Test fun longestInterruptionIsHighlightedEvenIfNotFirst() {
        val short = ReportInterruption(1_000, 4_000, "Synthetic", true)
        val long = ReportInterruption(10_000, 411_000, "Synthetic", true)
        assertSame(long, DailyInterruptionUi.from(report(listOf(short, long))).longest)
    }
    @Test fun mergedOfflineDurationIsNotRecomputedAsSumOfOverlappingEvents() {
        val rows = listOf(ReportInterruption(0, 4_000, "A", true), ReportInterruption(2_000, 6_000, "B", true))
        assertEquals(6_000L, DailyInterruptionUi.from(report(rows, 6_000)).offlineMs)
    }
    @Test fun UnresolvedContextIsNotInventedAsRestored() {
        val row = ReportInterruption(0, 10_000, "Observed context", false)
        val restored = DailyInterruptionUi.from(report(listOf(row), 10_000)).rows.single()
        assertFalse(restored.resolved); assertEquals("Observed context", restored.reason)
    }
    @Test fun zeroDurationEventIsPreserved() {
        val ui = DailyInterruptionUi.from(report(listOf(ReportInterruption(0, 0, "Synthetic", true))))
        assertEquals(1, ui.count); assertEquals(0L, ui.longest!!.durationMs)
    }
}
