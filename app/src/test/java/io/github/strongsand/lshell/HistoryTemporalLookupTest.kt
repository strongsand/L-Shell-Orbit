package io.github.strongsand.lshell

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class HistoryTemporalLookupTest {
    private fun outage(start: Long, end: Long, resolved: Boolean = true) =
        ReportInterruption(start, end, "Conexão perdida", resolved)

    private fun reference(rows: List<HistoryEntry>, outage: ReportInterruption) = OutageContext(outage,
        rows.filter { (it.kind == "reading" || it.kind == "beacon_history") && it.timestamp < outage.start }.maxByOrNull { it.timestamp },
        rows.filter { (it.kind == "reading" || it.kind == "beacon_history") && it.timestamp in outage.start..outage.end }.minByOrNull { it.timestamp },
        rows.filter { (it.kind == "reading" || it.kind == "beacon_history") && it.timestamp >= outage.end }.minByOrNull { it.timestamp })

    private fun compare(rows: List<HistoryEntry>, outages: List<ReportInterruption>) {
        val index = OutageHistoryLookup(rows)
        assertEquals(outages.map { reference(rows, it) }, outages.map(index::context))
    }

    @Test fun emptyHistoryAndNoOutagesStayEmpty() {
        compare(emptyList(), emptyList())
        compare(emptyList(), listOf(outage(0, 10)))
    }

    @Test fun beforeIsExclusiveAndDuringAndAfterAreInclusive() {
        val rows = (0L..4L).map { HistoryEntry(it, "reading", it.toString()) }
        val context = OutageHistoryLookup(rows).context(outage(1, 3))
        assertEquals(rows[0], context.before)
        assertEquals(rows[1], context.during)
        assertEquals(rows[3], context.after)
        compare(rows, listOf(outage(1, 3), outage(3, 3), outage(3, 1)))
    }

    @Test fun equalTimestampsKeepTheFirstRowInAllThreePositions() {
        val rows = listOf(HistoryEntry(0, "reading", "first-before"), HistoryEntry(0, "reading", "second-before"),
            HistoryEntry(1, "beacon_history", "first-during"), HistoryEntry(1, "reading", "second-during"),
            HistoryEntry(2, "reading", "first-after"), HistoryEntry(2, "reading", "second-after"))
        val result = OutageHistoryLookup(rows).context(outage(1, 2))
        assertEquals("first-before", result.before?.label)
        assertEquals("first-during", result.during?.label)
        assertEquals("first-after", result.after?.label)
        compare(rows, listOf(outage(1, 2), outage(1, 1)))
    }

    @Test fun terminalRowsAndEventsDoNotBecomeStateContext() {
        val rows = listOf(HistoryEntry(0, "terminal_history", "terminal"), HistoryEntry(1, "event", "event"),
            HistoryEntry(2, "reading", "state"), HistoryEntry(3, "beacon_history", "beacon"))
        compare(rows, listOf(outage(1, 2), outage(3, 9, false)))
        assertNull(OutageHistoryLookup(rows).context(outage(1, 2)).before)
    }

    @Test fun openOutagesWindowEdgesGapsMissingAndZeroValuesArePreserved() {
        val rows = listOf(HistoryEntry(0, "reading", "missing"),
            HistoryEntry(10, "reading", "zero", downloadMbps = 0f, dropPercent = 0f, powerWatts = null),
            HistoryEntry(100_000, "beacon_history", "gap", latencyMs = 5f, obstructionPercent = 0f))
        compare(rows, listOf(outage(-10, 0), outage(1, 9), outage(10, 100_001, false), outage(100_001, 100_002)))
        val context = OutageHistoryLookup(rows).context(outage(10, 100_001, false))
        assertTrue(context.before!!.downloadMbps.isNaN())
        assertEquals(0f, context.during!!.downloadMbps, 0f)
        assertNull(context.during!!.powerWatts)
        assertFalse(context.outage.resolved)
    }

    @Test fun randomizedSortedFixturesMatchTheOriginalScans() {
        val random = Random(42)
        repeat(30) {
            val rows = (0 until 120).map { id -> HistoryEntry(random.nextLong(0, 200),
                listOf("reading", "terminal_history", "beacon_history", "event")[random.nextInt(4)], id.toString()) }
                .sortedBy { it.timestamp }
            val outages = (0 until 12).map { outage(random.nextLong(-10, 220), random.nextLong(-10, 220), it % 2 == 0) }
            compare(rows, outages)
        }
    }

    @Test fun buildingTheIndexCooperativelyCancels() {
        var calls = 0
        try {
            OutageHistoryLookup((0L..4096).map { HistoryEntry(it, "reading", "") }) {
                if (++calls == 2) throw CancellationException("cancelled")
            }
            fail("Index construction ignored cancellation")
        } catch (_: CancellationException) { assertEquals(2, calls) }
    }

    @Test fun repeatedContextLookupsNeverRescanTheInput() {
        var reads = 0
        val rows = object : AbstractList<HistoryEntry>() {
            override val size = 1000
            override fun get(index: Int): HistoryEntry { reads++; return HistoryEntry(index.toLong(), "reading", "") }
        }
        val events = (0 until 20).map { outage(it * 40L, it * 40L + 5) }
        val old = events.map { reference(rows, it) }
        assertEquals(3 * rows.size * events.size, reads)
        reads = 0
        val lookup = OutageHistoryLookup(rows)
        assertEquals(old, events.map(lookup::context))
        assertEquals(rows.size, reads)
    }

    @Test fun theSameReadProducesTheSameReportAndContexts() {
        val rows = listOf(HistoryEntry(0, "reading", "", downloadMbps = 0f, dropPercent = 0f),
            HistoryEntry(1000, "event", "Conexão com a antena perdida"),
            HistoryEntry(2000, "beacon_history", "", latencyMs = 20f),
            HistoryEntry(3000, "event", "Conexão com a antena restabelecida"))
        val previous = DailyReportGenerator.generate(rows.toList(), 0, 4000)
        val reused = DailyReportGenerator.generate(rows, 0, 4000)
        assertEquals(previous.copy(createdAt = 0), reused.copy(createdAt = 0))
        compare(rows, reused.interruptions)
    }

    @Test fun overlapsIncludesBothFiveHundredMillisecondBoundaries() {
        for (delta in listOf(-501L, -500L, -1L, 0L, 1L, 500L, 501L)) {
            assertEquals(delta in -500L..500L, HistoryTimestampIndex(longArrayOf(1000 + delta)).overlaps(1000))
        }
        assertFalse(HistoryTimestampIndex(longArrayOf()).overlaps(1000))
    }

    @Test fun batchOverlapMatchesSqlBetweenSemanticsForDuplicatesAndGaps() {
        val times = longArrayOf(-1000, 0, 0, 1000, 3000, 9000)
        val index = HistoryTimestampIndex(times)
        for (time in -1600L..9600L step 37L) assertEquals(times.any { it in time-500..time+500 }, index.overlaps(time))
    }

    @Test fun timestampIndexDoesNotExposeMutableInput() {
        val times = longArrayOf(1000)
        val index = HistoryTimestampIndex(times)
        times[0] = 999_999
        assertTrue(index.overlaps(1000))
        assertFalse(index.overlaps(999_999))
    }

    @Test fun overflowBoundsNeverChangeOriginalPerSampleOverlapDecisions() {
        val index = HistoryTimestampIndex(longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE))
        assertFalse(index.overlaps(Long.MIN_VALUE))
        assertFalse(index.overlaps(Long.MAX_VALUE))
        assertEquals(Long.MIN_VALUE, historyOverlapBounds(Long.MIN_VALUE, 0).first)
        assertEquals(Long.MAX_VALUE, historyOverlapBounds(0, Long.MAX_VALUE).last)
        assertEquals(Long.MIN_VALUE..Long.MAX_VALUE, historyOverlapBounds(1000, 0))
        assertEquals(500L..2500L, historyOverlapBounds(1000, 2000))
    }
}
