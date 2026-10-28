package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.data.TelemetrySample
import org.junit.Assert.*
import org.junit.Test

class HomeSparklineTest {
    private fun sample(t: Long, value: Float, history: Boolean = false) =
        TelemetrySample(t, value, value / 2, value, value, value, history)

    @Test fun usesRealTimestampedRateAndPowerObservationsIncludingZeroAndPeaks() {
        val samples = listOf(sample(10_000, 0f), sample(11_000, 1000f), sample(12_000, 2f))
        for (kind in listOf(MetricDetailKind.DOWNLOAD, MetricDetailKind.POWER, MetricDetailKind.LATENCY)) {
            val series = HomeSparklinePolicy.series(samples, kind)!!
            assertEquals(listOf(10_000L, 11_000L, 12_000L), series.points.map { it.time })
            assertEquals(listOf(0.0, 1000.0, 2.0), series.points.map { it.measurement?.value })
        }
        assertEquals(listOf(0.0, 500.0, 1.0), HomeSparklinePolicy.series(samples, MetricDetailKind.UPLOAD)!!.values)
    }

    @Test fun insufficientInvalidAndBooleanSourcesDoNotInventCurves() {
        assertNull(HomeSparklinePolicy.series(emptyList(), MetricDetailKind.DOWNLOAD))
        assertNull(HomeSparklinePolicy.series(listOf(sample(10_000, 2f)), MetricDetailKind.DOWNLOAD))
        assertNull(HomeSparklinePolicy.series(List(3) { sample(it * 1000L, Float.NaN) }, MetricDetailKind.POWER))
        assertNull(HomeSparklinePolicy.series(List(3) { sample(it * 1000L, 2f) }, MetricDetailKind.SIGNAL))
        assertNull(HomeSparklinePolicy.series(listOf(sample(0, 2f), sample(10_000, 4f)), MetricDetailKind.DOWNLOAD))
    }

    @Test fun nullNegativeAndNonfinitePowerStayGapsWhileMeasuredZeroStaysValid() {
        val input = listOf(sample(0, 0f), sample(1000, 1f), sample(2000, 2f).copy(powerWatts = null),
            sample(3000, -1f), sample(4000, Float.POSITIVE_INFINITY), sample(5000, 4f), sample(6000, 8f))
        val points = HomeSparklinePolicy.series(input, MetricDetailKind.POWER)!!.points
        assertEquals(0.0, points.first().measurement!!.value, 0.0)
        assertTrue(points.subList(2, 5).all { it.measurement == null })
    }

    @Test fun realSupportGapsRemainDisconnectedAfterWidthReduction() {
        val input = listOf(sample(0, 1f), sample(1000, 2f), sample(2000, Float.NaN), sample(5000, 8f), sample(6000, 3f))
        val series = HomeSparklinePolicy.series(input, MetricDetailKind.DOWNLOAD)!!
        val plot = MetricChartPresentation.reduce(series, MetricTimeViewport(series.start, series.end), 16)
        assertFalse(plot.zipWithNext().any { (a, b) -> a.time <= 1000 && b.time >= 5000 && b.connect })
        assertNull(MetricChartPresentation.inspect(series, 2500))
    }

    @Test fun nativeHistorySupportIsNotPromotedToLiveSupport() {
        val values = listOf(sample(1000, 3f, true), sample(2500, 4f, true))
        assertNull(HomeSparklinePolicy.series(values, MetricDetailKind.DOWNLOAD))
        assertNotNull(HomeSparklinePolicy.series(values.map { it.copy(isHistory = false) }, MetricDetailKind.DOWNLOAD))
    }

    @Test fun realPeaksSurviveDecorativeRenderingAndInputIsBounded() {
        val input = (0..500).map { sample(it * 1000L, if (it == 480) 999f else 1f) }
        val series = HomeSparklinePolicy.series(input, MetricDetailKind.DOWNLOAD)!!
        assertTrue(series.points.size <= 121)
        val plot = MetricChartPresentation.reduce(series, MetricTimeViewport(series.start, series.end), 16)
        assertTrue(plot.any { it.time == 480_000L && it.value == 999.0 })
        assertEquals(501, input.size)
    }

    @Test fun fadeCorridorStaysOutsideAllProtectedTextEvenWithValuesOnBothSides() {
        val occupied = listOf(SparklineBand(16f, 140f), SparklineBand(250f, 320f))
        val band = HomeSparklinePolicy.band(340f, 70f, occupied)!!
        assertTrue(band.left >= 148f && band.right <= 242f && band.width >= 48f)
    }

    @Test fun narrowTallOrTextFilledCardsHaveNoBackgroundChart() {
        assertNull(HomeSparklinePolicy.band(160f, 80f, listOf(SparklineBand(12f, 100f))))
        assertNull(HomeSparklinePolicy.band(330f, 180f, listOf(SparklineBand(12f, 100f))))
        assertNull(HomeSparklinePolicy.band(330f, 80f, listOf(SparklineBand(12f, 290f))))
        assertNull(HomeSparklinePolicy.band(330f, 80f, emptyList()))
        assertNotNull(HomeSparklinePolicy.band(330f, 90f, listOf(SparklineBand(12f, 140f))))
    }

    @Test fun wideCurveHasStrongColorAndOnlyShortFadesNextToProtectedText() {
        val text = listOf(SparklineBand(16f, 100f), SparklineBand(260f, 320f))
        val band = HomeSparklinePolicy.band(340f, 70f, text)!!
        val stops = HomeSparklinePolicy.opacityStops(band, text)
        assertEquals(0f, stops.first().second, 0f)
        assertEquals(0f, stops.last().second, 0f)
        assertTrue(stops[1].second >= .9f && stops[2].second >= .9f)
        assertTrue(stops[1].first <= .22f && stops[2].first >= .78f)
    }

    @Test fun aCardEdgeWithoutTextDoesNotDimTheCurve() {
        val textOnLeft = listOf(SparklineBand(16f, 140f))
        val left = HomeSparklinePolicy.opacityStops(HomeSparklinePolicy.band(340f, 70f, textOnLeft)!!, textOnLeft)
        assertEquals(0f, left.first().second, 0f)
        assertTrue(left.last().second >= .9f)
        val textOnRight = listOf(SparklineBand(200f, 320f))
        val right = HomeSparklinePolicy.opacityStops(HomeSparklinePolicy.band(340f, 70f, textOnRight)!!, textOnRight)
        assertTrue(right.first().second >= .9f)
        assertEquals(0f, right.last().second, 0f)
    }
}
