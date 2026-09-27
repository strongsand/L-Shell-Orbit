package io.github.strongsand.lshell

import io.github.strongsand.lshell.beacon.BeaconSampleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyReportChartTest {
    @Test fun constantSeriesGetsFiniteNonZeroScale() {
        val scale = requireNotNull(reportChartScale(listOf(4f, 4f, 4f)))
        assertTrue(scale.constant)
        assertTrue(scale.minimum.isFinite())
        assertTrue(scale.maximum.isFinite())
        assertTrue(scale.maximum > scale.minimum)
    }

    @Test fun scaleAddsRoomAroundVariableDataWithoutInventingNegativeFloor() {
        val scale = requireNotNull(reportChartScale(listOf(0f, 5f, 10f)))
        assertEquals(0f, scale.minimum, 0f)
        assertTrue(scale.maximum > 10f)
    }

    @Test fun missingAndInvalidValuesSplitLineSegments() {
        val segments = reportChartSamples(listOf(1f, Float.NaN, 2f, null, 3f, 4f).mapIndexed { index, value ->
            ReportChartBucket(index.toLong(), index + 1L, value, if (value?.isFinite() == true) 1 else 0,
                if (value?.isFinite() == true) 1 else 0, 0)
        })
        assertEquals(listOf(1, 1, 2), segments.map { it.size })
    }

    @Test fun reportChartAlwaysBuildsFortyEightBuckets() {
        val buckets = aggregateReportChart(emptyList(), 0L, 4_800L) { it.downloadMbps }
        assertEquals(48, buckets.size)
        assertTrue(buckets.all { it.value == null && it.sampleCount == 0 })
    }

    @Test fun chartCoverageCountsIntervalsRatherThanRawSamples() {
        val entries = (0 until 100).map { offset ->
            HistoryEntry(timestamp = 10L + offset, kind = "beacon_history", label = "synthetic",
                downloadMbps = 10f, source = BeaconSampleSource.BEACON)
        }
        val buckets = aggregateReportChart(entries, 0L, 4_800L) { it.downloadMbps }
        val coverage = reportChartCoverage(buckets)
        assertEquals(2, coverage.filled)
        assertEquals(48, coverage.total)
        assertEquals(100f / 24f, coverage.percent, .001f)
    }

    @Test fun bucketTracksLocalBeaconAndMixedOrigins() {
        val entries = listOf(
            HistoryEntry(10L, "reading", "local", downloadMbps = 1f),
            HistoryEntry(110L, "beacon_history", "beacon", downloadMbps = 2f,
                source = BeaconSampleSource.BEACON),
            HistoryEntry(210L, "reading", "local", downloadMbps = 3f),
            HistoryEntry(220L, "beacon_history", "beacon", downloadMbps = 5f,
                source = BeaconSampleSource.BEACON)
        )
        val buckets = aggregateReportChart(entries, 0L, 4_800L) { it.downloadMbps }
        assertEquals(ReportBucketOrigin.LOCAL, buckets[0].origin)
        assertEquals(ReportBucketOrigin.BEACON, buckets[1].origin)
        assertEquals(ReportBucketOrigin.MIXED, buckets[2].origin)
        assertEquals(2, buckets[2].sampleCount)
        assertEquals(1, buckets[2].localCount)
        assertEquals(1, buckets[2].beaconCount)
        assertEquals(4f, buckets[2].value!!, 0f)
    }

    @Test fun missingMetricCreatesRealGapWhileZeroRemainsValid() {
        val entries = listOf(
            HistoryEntry(10L, "reading", "missing"),
            HistoryEntry(110L, "reading", "zero", dropPercent = 0f)
        )
        val buckets = aggregateReportChart(entries, 0L, 4_800L) { it.dropPercent }
        assertEquals(null, buckets[0].value)
        assertEquals(0f, buckets[1].value!!, 0f)
    }

    @Test fun sqlNullIsDifferentFromRealZero() {
        assertTrue(storedMetricValue(isNull = true, value = 0f).isNaN())
        assertEquals(0f, storedMetricValue(isNull = false, value = 0f), 0f)
    }

    @Test fun beaconRangeMarksOnlyOverlappingReportAsAffected() {
        val first = report(start = 0L, end = 1_000L)
        val second = report(start = 1_000L, end = 2_000L)
        assertEquals(listOf(1_000L), affectedReportEnds(listOf(first, second), 500L, 900L))
        assertFalse(reportRangesOverlap(first.start, first.end, 1_000L, 1_100L))
    }

    @Test fun staleReportIsRegeneratedWithItsOriginalPeriod() {
        val existing = report(100L, 200L)
        var generated = false
        val refreshed = refreshReportIfStale(existing, stale = true) { start, end ->
            generated = true
            report(start, end, sampleCount = 99)
        }
        assertTrue(generated)
        assertEquals(100L, refreshed.start)
        assertEquals(200L, refreshed.end)
        assertEquals(99, refreshed.sampleCount)
        assertSame(existing, refreshReportIfStale(existing, stale = false) { _, _ -> error("unexpected") })
    }

    @Test fun denseTimelineEventsAreAggregatedIntoVisualBuckets() {
        val events = (0 until 100).map { index ->
            ReportMoment(timestamp = 1_000L + index, title = "Alerta")
        }
        val buckets = aggregateReportTimeline(events, start = 1_000L, end = 2_000L, slots = 8)
        assertTrue(buckets.size <= 8)
        assertEquals(100, buckets.sumOf { it.count })
    }

    @Test fun timelineKeepsTheMostImportantToneInEachBucket() {
        val events = listOf(
            ReportMoment(1_100L, "Alerta comum"),
            ReportMoment(1_101L, "Perda elevada de pacotes")
        )
        val bucket = aggregateReportTimeline(events, start = 1_000L, end = 2_000L, slots = 1).single()
        assertEquals(3, bucket.tone)
    }

    private fun report(start: Long, end: Long, sampleCount: Int = 0) = DailyReport(
        start = start, end = end, createdAt = end, summary = "synthetic",
        monitoredMs = 0L, onlineMs = 0L, offlineMs = 0L, availabilityPercent = null,
        sampleCount = sampleCount, trafficDownload = null, trafficUpload = null, latency = null,
        latencyAbove100Ms = 0L, packetLoss = null, packetLossPeriods = 0,
        packetLossDurationMs = 0L, obstruction = null, interruptions = emptyList(), alerts = emptyList(),
        hardwareEvents = emptyList(), timeline = emptyList(), latencyChart = emptyList(),
        lossChart = emptyList(), obstructionChart = emptyList(), trafficChart = emptyList()
    )
}
