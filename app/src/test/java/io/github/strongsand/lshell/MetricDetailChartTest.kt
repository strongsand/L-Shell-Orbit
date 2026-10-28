package io.github.strongsand.lshell

import io.github.strongsand.lshell.beacon.BeaconSampleSource
import org.junit.Assert.*
import org.junit.Test

class MetricDetailChartTest {
    private val start = 1_800_000_000_000L
    private fun row(second: Int, value: Float? = 60f) = HistoryEntry(start + second * 1_000, "terminal_history", "", powerWatts = value)
    private fun series(rows: List<HistoryEntry>, seconds: Int = 300) =
        MetricHistorySeriesBuilder.build(rows, HistoricalMetric.POWER, start, start + seconds * 1_000)
    private fun reduce(series: MetricHistorySeries, buckets: Int = 10) =
        MetricChartPresentation.reduce(series, MetricTimeViewport(series.start, series.end), buckets)

    @Test fun noDataProducesEmptyPlotAndFiniteAxis() {
        assertTrue(reduce(series(emptyList())).isEmpty())
        val scale = MetricChartScale.from(emptyList())
        assertEquals(0.0, scale.fraction(0.0), 0.0)
        assertTrue(scale.ticks.all { it.isFinite() })
    }
    @Test fun zeroRemainsARealReading() {
        val s = series(listOf(row(0, 0f), row(1, null)))
        assertEquals(0.0, MetricChartPresentation.inspect(s, start)!!.measurement!!.value, 0.0)
        assertNull(MetricChartPresentation.inspect(s, start + 1_000))
        assertEquals(1_000L, s.coveredMs)
    }
    @Test fun constantValuesHaveVerticalHeadroom() {
        val scale = MetricChartScale.from(List(50) { 60.0 })
        assertFalse(scale.compressed)
        assertTrue(scale.fraction(60.0) in .01.. .99)
    }
    @Test fun isolatedOutlierIsPreservedAndUsesExplicitNonlinearScale() {
        val s = series((0 until 100).map { row(it, if (it == 50) 10_000f else 10f) })
        val scale = MetricChartScale.from(s.values)
        assertTrue(scale.compressed)
        assertTrue(scale.fraction(10.0) > .05)
        assertEquals(10_000.0, reduce(s).maxOf { it.value!! }, 0.0)
        assertEquals(10_000.0, MetricChartPresentation.inspect(s, start + 50_000)!!.measurement!!.value, 0.0)
        assertTrue(scale.ticks.zipWithNext().all { (a, b) -> a < b })
    }
    @Test fun aNullInsideOneBucketNeverGetsBridged() {
        val plot = reduce(series(listOf(row(0), row(1, null), row(2), row(3))), 1)
        assertFalse(plot.last().connect)
    }
    @Test fun longMissingIntervalsRemainDisconnected() {
        val s = series(listOf(row(0), row(1), row(250), row(251)))
        assertNull(MetricChartPresentation.inspect(s, start + 100_000))
        val plot = reduce(s)
        assertFalse(plot.first { it.time >= start + 250_000 }.connect)
    }
    @Test fun nearGapSelectionDoesNotSnapAcrossIt() {
        val s = series(listOf(row(0), row(5)))
        assertNull(MetricChartPresentation.inspect(s, start + 4_900))
        assertNotNull(MetricChartPresentation.inspect(s, start + 5_000))
    }
    @Test fun nearestRealSampleWithinContinuousSupport() {
        val s = series(listOf(row(0), row(1), row(2)))
        assertEquals(start + 1_000, MetricChartPresentation.inspect(s, start + 750)!!.time)
    }
    @Test fun denseDayKeepsMinMaxAndBoundedDrawing() {
        val s = series((0 until 86_400).map { row(it, (it % 101).toFloat()) }, 86_400)
        val plot = reduce(s, 360)
        assertTrue(plot.size <= 1440)
        assertEquals(0.0, plot.minOf { it.value!! }, 0.0)
        assertEquals(100.0, plot.maxOf { it.value!! }, 0.0)
        assertTrue(plot.zipWithNext().all { (a, b) -> a.time < b.time })
        assertEquals(86_400, s.points.size)
    }
    @Test fun duplicateTimestampsUseIndependentFallbackAndKeepOrigins() {
        val local = row(0, null).copy(downloadMbps = 20f)
        val beacon = row(0).copy(kind = "beacon_history", downloadMbps = 10f, source = BeaconSampleSource.BEACON)
        assertEquals(TrafficOrigin.BEACON, series(listOf(beacon, local)).points.single().measurement!!.origin)
        val traffic = MetricHistorySeriesBuilder.build(listOf(beacon, local), HistoricalMetric.DOWNLOAD, start, start + 5_000)
        assertEquals(20.0, traffic.points.single().measurement!!.value, 0.0)
        assertEquals(TrafficOrigin.LOCAL_PHONE, traffic.points.single().measurement!!.origin)
    }
    @Test fun downloadAndUploadMatchValidatedMonitorValuesAndCoverage() {
        val rows = (0 until 20).map { row(it).copy(downloadMbps = it.toFloat(), uploadMbps = 0f) }
        val analysis = TrafficSessionAnalyzer.analyze(rows, start, start + 30_000)
        listOf(HistoricalMetric.DOWNLOAD to TrafficMetric.DOWNLOAD, HistoricalMetric.UPLOAD to TrafficMetric.UPLOAD).forEach { (history, metric) ->
            val s = MetricHistorySeriesBuilder.build(rows, history, start, start + 30_000)
            assertEquals(analysis.points.map { it.metric(metric)!!.value }, s.values)
            assertEquals(analysis.summary(metric).coveredMs, s.coveredMs)
        }
    }
    @Test fun sortingAndInvalidValuesDoNotManufactureReadings() {
        val s = series(listOf(row(2), row(0), row(1, Float.NaN), row(3, Float.POSITIVE_INFINITY)))
        assertEquals(listOf(start, start + 1_000, start + 2_000, start + 3_000), s.points.map { it.time })
        assertEquals(2, s.values.size)
        assertFalse(reduce(s).last().connect)
    }
    @Test fun lensKeepsPeriodAndAnchorPositionAndClampsEdges() {
        val end = start + 3_600_000
        val anchor = start + 1_800_000
        val focus = MetricTimeViewport.focus(start, end, anchor, .25)
        assertEquals(anchor, focus.time(.25))
        assertTrue(focus.duration < end - start)
        assertEquals(start, MetricTimeViewport.focus(start, end, start, .5).start)
        assertEquals(end, MetricTimeViewport.focus(start, end, end, .5).end)
    }
    @Test fun onePointAndDegeneratePeriodAreSafe() {
        assertEquals(1, reduce(series(listOf(row(0)))).size)
        val empty = MetricHistorySeriesBuilder.build(emptyList(), HistoricalMetric.POWER, start, start)
        assertTrue(reduce(empty).isEmpty())
    }
}
