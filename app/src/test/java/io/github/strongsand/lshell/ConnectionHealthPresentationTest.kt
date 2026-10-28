package io.github.strongsand.lshell

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ConnectionHealthPresentationTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
    private fun report(kind: HealthPeriodKind = HealthPeriodKind.WEEK, rows: List<HistoryEntry> = emptyList()): HealthReport {
        val period = HealthPeriod.containing(kind, now, zone)
        val day = ConnectionHealthAnalyzer.day(rows, emptyList(), period.start, period.start + 86_400_000, zone)
        return ConnectionHealthAnalyzer.report(period, listOf(day), null, now, emptyList())
    }

    @Test fun originalMixedCaptureReproducesNpeBeforeRecomposition() {
        val state = mutableStateOf<HealthReport?>(null)
        val oldTotal = state.value?.total
        // LazyListIntervalContent is derived state, reevaluated on snapshot invalidation.
        val oldContent = derivedStateOf { if (state.value != null) oldTotal!! else null }
        assertNull(oldContent.value)
        state.value = report()
        Snapshot.sendApplyNotifications()
        assertThrows(NullPointerException::class.java) { oldContent.value }
    }

    @Test fun loadingContentDoesNotReadNewReportWithOldAggregate() {
        val state = mutableStateOf<HealthReport?>(null)
        val prepared = PreparedHealthReport.from(state.value)
        val deferredContent = derivedStateOf { prepared?.total }
        state.value = report()
        Snapshot.sendApplyNotifications()
        assertNull(deferredContent.value)
        val recomposed = PreparedHealthReport.from(state.value)!!
        assertSame(state.value, recomposed.report)
        assertEquals(state.value!!.total, recomposed.total)
    }

    @Test fun periodChangeKeepsDeferredContentInternallyConsistent() {
        val week = report()
        val state = mutableStateOf<HealthReport?>(week)
        val prepared = PreparedHealthReport.from(state.value)!!
        val deferredContent = derivedStateOf { prepared }
        state.value = report(HealthPeriodKind.MONTH)
        Snapshot.sendApplyNotifications()
        assertSame(week, deferredContent.value.report)
        assertEquals(week.total, deferredContent.value.total)
        val next = PreparedHealthReport.from(state.value)!!
        assertEquals(HealthPeriodKind.MONTH, next.report.period.kind)
        assertEquals(next.report.total, next.total)
    }

    @Test fun clearingReportForHistoryOrErrorCannotInvalidateOldContent() {
        val state = mutableStateOf<HealthReport?>(report())
        val prepared = PreparedHealthReport.from(state.value)!!
        state.value = null
        Snapshot.sendApplyNotifications()
        assertNotNull(prepared.total)
        assertNull(PreparedHealthReport.from(state.value))
    }

    @Test fun emptyDatabaseHasRealEmptyAggregateInBothViews() {
        HealthPeriodKind.entries.forEach { kind ->
            val prepared = PreparedHealthReport.from(report(kind))!!
            assertEquals(0L, prepared.total.observedMs)
            assertNull(prepared.total.availability)
            assertNull(prepared.total.metric("latency").mean)
        }
    }

    @Test fun legacyMissingMetricsAndNewSnapshotsBothPrepareWithoutInventedZero() {
        HealthPeriodKind.entries.forEach { kind ->
            val start = HealthPeriod.containing(kind, now, zone).start
            val rows = listOf(
                HistoryEntry(start, "reading", "", latencyMs = Float.NaN, dropPercent = 0f),
                HistoryEntry(start + 1000, "terminal_history", "", latencyMs = 20f, dishState = "CONNECTED"),
                HistoryEntry(start + 2000, "beacon_history", "", latencyMs = 30f,
                    source = io.github.strongsand.lshell.beacon.BeaconSampleSource.BEACON,
                    beaconId = "synthetic", beaconSequence = 1)
            )
            val loaded = report(kind, rows)
            val legacy = loaded.copy(aggregate = null)
            val prepared = PreparedHealthReport.from(legacy)!!
            assertEquals(2L, prepared.total.metric("latency").count)
            assertNull(prepared.total.powerMean)
            val persisted = loaded.copy(aggregate = prepared.total, closed = true)
            val reopened = ConnectionHealthJson.report(ConnectionHealthJson.report(persisted))
            assertEquals(prepared.total, PreparedHealthReport.from(reopened)!!.total)
        }
    }
}
