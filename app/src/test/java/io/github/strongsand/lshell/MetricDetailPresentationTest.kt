package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.model.DishState
import io.github.strongsand.lshell.core.model.DishySnapshot
import io.github.strongsand.lshell.core.model.LatencyStats
import io.github.strongsand.lshell.core.model.ThroughputStats
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class MetricDetailPresentationTest {
    private val live = DishySnapshot(timestamp = 20_000, state = DishState.CONNECTED,
        isOnline = true, throughput = ThroughputStats(downlinkBps = 1_400_000f, uplinkBps = 80_000f))

    @Test fun currentDownloadAndUploadComeFromLiveStatusAndCanUpdate() {
        assertEquals(CurrentMetricReading(CurrentMetricState.AVAILABLE, 1.4f),
            currentMetricReading(MetricDetailKind.DOWNLOAD, live, 20_000))
        assertEquals(CurrentMetricReading(CurrentMetricState.AVAILABLE, .08f),
            currentMetricReading(MetricDetailKind.UPLOAD, live, 20_000))
        assertEquals(CurrentMetricReading(CurrentMetricState.AVAILABLE, 2f),
            currentMetricReading(MetricDetailKind.DOWNLOAD,
                live.copy(timestamp = 22_000, throughput = live.throughput.copy(downlinkBps = 2_000_000f)), 22_000))
        assertEquals("1,4", formatCurrentMbps(1.4f, Locale.forLanguageTag("pt-BR")))
        assertEquals("0,08", formatCurrentMbps(.08f, Locale.forLanguageTag("pt-BR")))
        assertEquals("0.08", formatCurrentMbps(.08f, Locale.ENGLISH))
    }

    @Test fun missingIsDistinctFromARealZero() {
        val zero = live.copy(throughput = ThroughputStats())
        assertEquals(CurrentMetricReading(CurrentMetricState.AVAILABLE, 0f),
            currentMetricReading(MetricDetailKind.DOWNLOAD, zero, 20_000))
        assertEquals(CurrentMetricReading(CurrentMetricState.AVAILABLE, 0f),
            currentMetricReading(MetricDetailKind.UPLOAD, zero, 20_000))
        assertEquals(CurrentMetricReading(CurrentMetricState.WAITING),
            currentMetricReading(MetricDetailKind.DOWNLOAD, DishySnapshot(state = DishState.UNKNOWN), 20_000))
        assertEquals(CurrentMetricReading(CurrentMetricState.DISCONNECTED),
            currentMetricReading(MetricDetailKind.UPLOAD, live.copy(isOnline = false, isFromCache = true), 20_000))
        assertEquals(CurrentMetricReading(CurrentMetricState.UNAVAILABLE),
            currentMetricReading(MetricDetailKind.DOWNLOAD, live.copy(throughput = ThroughputStats(Float.NaN, 0f)), 20_000))
        assertEquals(CurrentMetricReading(CurrentMetricState.UNAVAILABLE),
            currentMetricReading(MetricDetailKind.DOWNLOAD, live, 31_001))
    }

    @Test fun currentLatencyUsesLiveStatusAndRejectsMissingZero() {
        val withLatency = live.copy(latency = LatencyStats(popPingLatencyMs = 20.4f))
        assertEquals(CurrentMetricReading(CurrentMetricState.AVAILABLE, 20.4f),
            currentMetricReading(MetricDetailKind.LATENCY, withLatency, 20_000))
        assertEquals("20", formatCurrentLatency(20.4f, Locale.forLanguageTag("pt-BR")))
        assertEquals(CurrentMetricReading(CurrentMetricState.AVAILABLE, 31f),
            currentMetricReading(MetricDetailKind.LATENCY,
                withLatency.copy(timestamp = 22_000, latency = LatencyStats(popPingLatencyMs = 31f)), 22_000))
        assertEquals(CurrentMetricReading(CurrentMetricState.UNAVAILABLE),
            currentMetricReading(MetricDetailKind.LATENCY, live, 20_000))
        assertEquals(CurrentMetricReading(CurrentMetricState.DISCONNECTED),
            currentMetricReading(MetricDetailKind.LATENCY, withLatency.copy(isOnline = false), 20_000))
    }

    @Test fun currentReadingDoesNotDependOnHistoryOrSelectedPeriod() {
        // Neither the HistoryStore series nor a period is an input to the current-reading projection.
        val historical = MetricHistorySeriesBuilder.build(emptyList(), HistoricalMetric.DOWNLOAD, 0, 20_000)
        assertTrue(historical.values.isEmpty())
        assertEquals(CurrentMetricState.AVAILABLE,
            currentMetricReading(MetricDetailKind.DOWNLOAD, live, 20_000)?.state)
        val stored = MetricHistorySeriesBuilder.build(
            listOf(HistoryEntry(5_000, "reading", "", downloadMbps = 9f)),
            HistoricalMetric.DOWNLOAD, 0, 20_000)
        assertEquals(listOf(9.0), stored.values)
        assertEquals(CurrentMetricState.DISCONNECTED,
            currentMetricReading(MetricDetailKind.UPLOAD, live.copy(isOnline = false), 20_000)?.state)
        assertEquals(CurrentMetricReading(CurrentMetricState.AVAILABLE, 0f),
            currentMetricReading(MetricDetailKind.SIGNAL, live, 20_000))
        assertEquals(CurrentMetricReading(CurrentMetricState.UNAVAILABLE),
            currentMetricReading(MetricDetailKind.POWER, live, 20_000))
    }
    private val points = listOf(
        MetricHistoryPoint(1_000, TrafficValue(0.0, TrafficOrigin.LOCAL_PHONE, 1_000)),
        MetricHistoryPoint(2_000, null),
        MetricHistoryPoint(3_000, TrafficValue(10.0, TrafficOrigin.BEACON, 1_000)),
    )
    private fun navigate(readings: List<MetricHistoryPoint>, time: Long?, forward: Boolean) =
        ChartSampleIndex(MetricHistorySeries(0, Long.MAX_VALUE, readings, 0, emptyList())).adjacent(time, forward)
    @Test fun accessibleNavigationUsesOriginalReadingsAndSkipsMissingMetrics() {
        assertEquals(points.first(), navigate(points, null, true))
        assertEquals(points.last(), navigate(points, 1_000, true))
        assertEquals(points.first(), navigate(points, 3_000, false))
        assertEquals(points.last(), navigate(points, null, false))
        assertNull(navigate(points, 3_000, true))
        assertNull(navigate(points, 1_000, false))
    }
    @Test fun emptyAndMissingOnlyChartsHaveNoFabricatedAccessibleReading() {
        listOf(emptyList(), listOf(points[1])).forEach { absent ->
            assertNull(navigate(absent, null, true))
            assertNull(navigate(absent, null, false))
        }
    }
    @Test fun stationarySelectionAndGapsNeverRepeatFeedback() {
        val feedback = MetricInspectionFeedback()
        assertFalse(feedback.sample(null, 0))
        assertTrue(feedback.sample(1_000, 0))
        assertFalse(feedback.sample(1_000, 900))
        assertFalse(feedback.sample(null, 1_000))
        assertTrue(feedback.sample(1_000, 1_100))
    }
    @Test fun sampleAndFocusShareCooldownRatherThanDoublingVibration() {
        val feedback = MetricInspectionFeedback()
        assertTrue(feedback.sample(1_000, 0))
        assertFalse(feedback.focus(120))
        assertFalse(feedback.sample(3_000, 200))
        assertTrue(feedback.focus(300))
        assertFalse(feedback.sample(1_000, 400))
        assertTrue(feedback.sample(3_000, 600))
    }
}
