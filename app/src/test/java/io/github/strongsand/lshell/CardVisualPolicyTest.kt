package io.github.strongsand.lshell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class CardVisualPolicyTest {
    @Test fun denseSpikesUseThinnerStrokeAndLighterFillThanSparseStableReadings() {
        val stable = CardVisualPolicy.style(List(10) { 20f }, 145f, 100f, CardMetricProfile.LATENCY)
        val noisy = CardVisualPolicy.style(List(600) { if (it % 2 == 0) 0f else 100f }, 145f, 100f, CardMetricProfile.THROUGHPUT)
        assertTrue(noisy.strokeDp < stable.strokeDp)
        assertTrue(noisy.fillAlpha < stable.fillAlpha)
        assertTrue(noisy.markerDp < 5f)
    }

    @Test fun actualAreaAndDensityAffectStyleRatherThanOnlyMetricId() {
        val values = List(600) { if (it % 2 == 0) 0f else 100f }
        val half = CardVisualPolicy.style(values, 140f, 100f, CardMetricProfile.THROUGHPUT)
        val full = CardVisualPolicy.style(values, 700f, 160f, CardMetricProfile.THROUGHPUT)
        assertTrue(full.strokeDp > half.strokeDp)
        assertTrue(full.buckets > half.buckets)
        assertTrue(full.markerDp > half.markerDp)
    }

    @Test fun emptyFlatZeroAndMissingSeriesProduceFiniteBoundedDrawingParameters() {
        for (values in listOf(emptyList(), List(100) { 0f }, listOf(Float.NaN, Float.POSITIVE_INFINITY))) {
            val style = CardVisualPolicy.style(values, 0f, 0f, CardMetricProfile.LOSS)
            assertTrue(style.strokeDp.isFinite() && style.strokeDp in 1.1f..2.9f)
            assertTrue(style.buckets >= 24)
        }
    }

    @Test fun emphasisIsMonotonicBoundedAndKeepsMeasuredZeroDistinctFromMissing() {
        val baseline = listOf(10f, 10f, 10f)
        val low = CardVisualPolicy.emphasis(12f, baseline, CardMetricProfile.THROUGHPUT)
        val high = CardVisualPolicy.emphasis(40f, baseline, CardMetricProfile.THROUGHPUT)
        assertTrue(low > 0 && high > low && high < 1)
        assertEquals(0f, CardVisualPolicy.emphasis(null, baseline, CardMetricProfile.THROUGHPUT), 0f)
        assertEquals(0f, CardVisualPolicy.emphasis(0f, baseline, CardMetricProfile.THROUGHPUT), 0f)
        assertTrue(CardVisualPolicy.emphasis(10f, List(3) { 0f }, CardMetricProfile.THROUGHPUT) > 0)
        assertEquals(0f, CardVisualPolicy.emphasis(10f, List(3) { Float.NaN }, CardMetricProfile.THROUGHPUT), 0f)
    }

    @Test fun invalidCurrentOrInsufficientBaselineNeverCreatesActivity() {
        for (value in listOf(null, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1f))
            assertEquals(0f, CardVisualPolicy.emphasis(value, List(4) { 1f }, CardMetricProfile.ENERGY), 0f)
        assertEquals(0f, CardVisualPolicy.emphasis(50f, listOf(1f, 2f), CardMetricProfile.ENERGY), 0f)
    }

    @Test fun adverseMetricsStayAdverseAndNoiseFloorsPreventZeroBaselineFlashing() {
        assertTrue(CardMetricProfile.LATENCY.adverse && CardMetricProfile.LOSS.adverse)
        assertFalse(CardMetricProfile.THROUGHPUT.adverse || CardMetricProfile.ENERGY.adverse)
        assertTrue(CardVisualPolicy.emphasis(.01f, List(20) { 0f }, CardMetricProfile.LOSS) < .02f)
        assertTrue(CardVisualPolicy.emphasis(1f, List(20) { 0f }, CardMetricProfile.LOSS) > .4f)
    }

    @Test fun widthAwareReductionKeepsRealExtremaEndpointsAndGaps() {
        val points = (0..599).map { i -> MetricHistoryPoint(i * 1000L,
            if (i == 250) null else TrafficValue(if (i == 499) 100.0 else if (i == 120) 0.0 else 10.0,
                TrafficOrigin.LOCAL_PHONE, 1000L)) }
        val series = MetricHistorySeries(0, 600_000, points, 0, emptyList())
        val buckets = CardVisualPolicy.style(List(600) { 10f }, 145f, 112f, CardMetricProfile.THROUGHPUT).buckets
        val plot = MetricChartPresentation.reduce(series, MetricTimeViewport(0, 600_000), buckets)
        assertTrue(plot.any { it.time == 499_000L && it.value == 100.0 })
        assertTrue(plot.any { it.time == 120_000L && it.value == 0.0 })
        assertEquals(0L, plot.first().time); assertEquals(599_000L, plot.last().time)
        assertFalse(plot.zipWithNext().any { (a, b) -> a.time < 250_000 && b.time > 250_000 && b.connect })
        assertNull(MetricChartPresentation.inspect(series, 250_500))
    }

    @Test fun dynamicTintPreservesNormalTextContrastForLightDarkAndBlackSurfaces() {
        for ((base, text, tint) in listOf(
            Triple(Color(0xffe4f7e9), Color(0xff12391c), Color(0xff006e24)),
            Triple(Color(0xff24262b), Color(0xffe4e2e8), Color(0xffb9cdb2)),
            Triple(Color.Black, Color(0xffeeeeee), Color(0xffbbffbb)))) {
            val alpha = safeIntensityAlpha(base, tint, listOf(text))
            assertTrue(alpha in 0f..0.101f)
            val background = tint.copy(alpha = alpha).compositeOver(base)
            val contrast = (maxOf(background.luminance(), text.luminance()) + .05f) /
                (minOf(background.luminance(), text.luminance()) + .05f)
            assertTrue(contrast >= 4.5f)
        }
    }
}
