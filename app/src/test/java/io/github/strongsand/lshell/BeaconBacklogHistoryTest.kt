package io.github.strongsand.lshell

import io.github.strongsand.lshell.beacon.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BeaconBacklogHistoryTest {
    private val start = 1_800_000_000_000L
    private val end = start + 86_400_000
    private fun sample(time: Long, beacon: Boolean = false, power: Float? = 60f) =
        HistoryEntry(time, if (beacon) "beacon_history" else "terminal_history", "",
            8f, 1f, 20f, 0f, powerWatts = power,
            source = if (beacon) BeaconSampleSource.BEACON else BeaconSampleSource.LOCAL_PHONE)
    private fun local() = (0 until 86_400).filter { it < 7_200 || it >= 64_800 }.map { sample(start + it * 1_000L) }
    private fun backlog() = (7_200 until 64_800 step 5).map { sample(start + it * 1_000L, true) }

    @Test fun phoneAbsentForSixteenHoursBeaconBacklogRestoresAllAvailableMetrics() {
        val local = local()
        assertEquals(100.0 / 3, TrafficSessionAnalyzer.analyze(local, start, end).coveragePercent, 1e-8)
        val merged = local + backlog()
        val traffic = TrafficSessionAnalyzer.analyze(merged, start, end)
        assertEquals(100.0, traffic.coveragePercent, 0.0)
        assertTrue(traffic.gaps.isEmpty())
        assertEquals(TrafficOrigin.MIXED, traffic.origin)
        for (metric in TrafficMetric.entries) {
            assertEquals(86_400_000L, traffic.summary(metric).coveredMs)
            assertTrue(traffic.points.any { it.metric(metric)?.origin == TrafficOrigin.BEACON })
            assertTrue(traffic.points.any { it.metric(metric)?.origin == TrafficOrigin.LOCAL_PHONE })
        }
        assertEquals(0.0, traffic.loss.mean!!, 0.0) // Measured zero remains valid.
        for (metric in HistoricalMetric.entries) {
            val series = MetricHistorySeriesBuilder.build(merged, metric, start, end)
            assertEquals(100.0, series.coveragePercent, 0.0)
            assertTrue(series.plot.any { it.origin == TrafficOrigin.BEACON })
        }
    }
    @Test fun powerCannotBridgeTheSameRealGapThatTrafficShows() {
        val rows = local()
        val power = MetricHistorySeriesBuilder.build(rows, HistoricalMetric.POWER, start, end)
        assertEquals(TrafficSessionAnalyzer.analyze(rows, start, end).coveragePercent, power.coveragePercent, 0.0)
        assertTrue(power.plot.any { !it.connect && it.time >= start + 64_800_000 })
        assertTrue(power.plot.size <= 600)
    }
    @Test fun optionalPowerDoesNotFabricateTrafficAndAbsentPowerDoesNotDiscardTraffic() {
        val powerOnly = sample(start, true).copy(downloadMbps = Float.NaN, uploadMbps = Float.NaN,
            latencyMs = Float.NaN, dropPercent = Float.NaN)
        assertEquals(0L, TrafficSessionAnalyzer.analyze(listOf(powerOnly), start, start + 5_000).coveredMs)
        assertEquals(5_000L, MetricHistorySeriesBuilder.build(listOf(powerOnly), HistoricalMetric.POWER,
            start, start + 5_000).coveredMs)
        val trafficOnly = sample(start, true, power = null)
        assertEquals(5_000L, TrafficSessionAnalyzer.analyze(listOf(trafficOnly), start, start + 5_000).coveredMs)
        assertEquals(0L, MetricHistorySeriesBuilder.build(listOf(trafficOnly), HistoricalMetric.POWER,
            start, start + 5_000).coveredMs)
    }
    @Test fun missingLocalPowerFallsBackIndependentlyToBeaconInSameSecond() {
        val series = MetricHistorySeriesBuilder.build(listOf(sample(start, power = null), sample(start, true)),
            HistoricalMetric.POWER, start, start + 5_000)
        assertEquals(TrafficOrigin.BEACON, series.points.single().measurement!!.origin)
        assertEquals(5_000L, series.coveredMs)
    }
    @Test fun measuredZeroPowerIsDifferentFromMissingPower() {
        val zero = MetricHistorySeriesBuilder.build(listOf(sample(start, true, 0f)),
            HistoricalMetric.POWER, start, start + 5_000)
        assertEquals(listOf(0.0), zero.values)
        assertEquals(5_000L, zero.coveredMs)
        val missing = MetricHistorySeriesBuilder.build(listOf(sample(start, true, null)),
            HistoricalMetric.POWER, start, start + 5_000)
        assertTrue(missing.values.isEmpty())
        assertEquals(0L, missing.coveredMs)
    }
    @Test fun nullBoundaryInsideReductionDoesNotBridgeAndMixedBucketUsesRealSources() {
        val series = MetricHistorySeriesBuilder.build(listOf(sample(start), sample(start + 1_000, true),
            sample(start + 6_000, true, null), sample(start + 7_000, true)), HistoricalMetric.POWER,
            start, start + 12_000, limit = 1)
        assertEquals(TrafficOrigin.MIXED, series.plot.single().origin)
        assertFalse(series.plot.single().connect)
        assertEquals(11_000L, series.coveredMs)
    }
    @Test fun synchronizedHistoricalTimestampsPersistBeforeAckAndFeedTheOriginalPeriod() = runBlocking {
        val device = BeaconDevice("synthetic", "Beacon", "example.local", 80)
        val imported = mutableListOf<HistoryEntry>()
        var cursorValue = 0L
        val actions = mutableListOf<String>()
        val samples = backlog().mapIndexed { index, row ->
            BeaconSample(device.id, index + 1L, BeaconHistoryClock.timestamp(
                JSONObject().put("timestamp_ms", row.timestamp), JSONObject(), end + 3_600_000),
                row.downloadMbps, row.uploadMbps, row.latencyMs, row.dropPercent, powerWatts = row.powerWatts)
        }
        val cursor = object : BeaconSyncCursor {
            override fun configuredBeacon() = device
            override fun pairedBeacon(): PairedBeacon? = null
            override fun lastSyncedSequence() = cursorValue
            override fun setLastSyncedSequence(sequence: Long) { actions += "cursor"; cursorValue = sequence }
        }
        val sink = object : BeaconHistorySink {
            override fun latestBeaconSequence(beaconId: String) = 0L
            override fun importBeaconSamples(samples: List<BeaconSample>): Int {
                actions += "persist"
                imported += samples.map { sample(it.timestampMillis, true) }
                return samples.size
            }
            override fun close() {}
        }
        val transport = object : BeaconSyncTransport {
            override suspend fun historyInfo(device: BeaconDevice) = Result.success(
                BeaconHistoryInfo(1, 1, 1, samples.size.toLong(), samples.size.toLong(), samples.size.toLong()))
            override suspend fun recordsAfter(device: BeaconDevice, sequence: Long, limit: Int): Result<BeaconSyncPage> {
                val page = samples.filter { it.sequence > sequence }.take(limit)
                return Result.success(BeaconSyncPage(page, page.last().sequence, page.last().sequence < samples.size))
            }
            override suspend fun acknowledge(device: BeaconDevice, sequence: Long): Result<Unit> {
                assertEquals("persist", actions.last()); actions += "ack"; return Result.success(Unit)
            }
        }
        assertTrue(BeaconSyncManager(sink, cursor, transport, log = {}).synchronize(device).isSuccess)
        assertEquals(samples.size.toLong(), cursorValue)
        assertTrue(actions.chunked(3).all { it == listOf("persist", "ack", "cursor") })
        assertEquals(100.0, TrafficSessionAnalyzer.analyze(local() + imported, start, end).coveragePercent, 0.0)
        assertEquals(100.0, MetricHistorySeriesBuilder.build(local() + imported, HistoricalMetric.POWER,
            start, end).coveragePercent, 0.0)
    }
}
