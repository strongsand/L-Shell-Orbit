package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class ChartInspectionTest {
    private fun point(time: Long, value: Double? = 1.0, origin: TrafficOrigin = TrafficOrigin.LOCAL_PHONE) =
        MetricHistoryPoint(time, value?.let { TrafficValue(it, origin, 1_000) })
    private fun index(vararg points: MetricHistoryPoint) = ChartSampleIndex(MetricHistorySeries(0, 10_000_000, points.toList(), 0, emptyList()))

    @Test fun `selection snaps to original nearest supported reading and endpoints`() {
        val first = point(0); val last = point(1_000, 2.0)
        val i = index(first, last)
        assertEquals(first, i.inspect(0)); assertEquals(first, i.inspect(499)); assertEquals(last, i.inspect(501))
        assertEquals(last, i.inspect(1_000)); assertNull(i.inspect(-1)); assertNull(i.inspect(2_000))
    }
    @Test fun `missing and hours without support never snap to nearby values`() {
        val i = index(point(0, 0.0), point(1_000, null), point(3_600_000))
        assertEquals(0.0, i.inspect(0)!!.measurement!!.value, 0.0)
        assertNull(i.inspect(1_000)); assertNull(i.inspect(1_500)); assertNull(i.inspect(2_000_000))
        assertFalse(chartConnected(point(0), point(3_600_000)))
        assertTrue(chartConnected(point(0), point(1_000)))
    }
    @Test fun `navigation skips missing without changing original timestamps`() {
        val a = point(0); val b = point(2_000)
        val i = index(a, point(1_000, null), b)
        assertEquals(a, i.adjacent(null, true)); assertEquals(b, i.adjacent(null, false))
        assertEquals(b, i.adjacent(0, true)); assertEquals(a, i.adjacent(2_000, false))
        assertNull(i.adjacent(0, false)); assertNull(i.adjacent(2_000, true))
    }
    @Test fun `report inspection preserves aggregate interval zero missing and sources`() {
        val buckets = listOf(ReportChartBucket(0, 1_000, 0f, 1, 1, 0),
            ReportChartBucket(1_000, 2_000, null, 0, 0, 0),
            ReportChartBucket(2_000, 3_000, 2f, 1, 0, 1),
            ReportChartBucket(3_000, 4_000, 3f, 2, 1, 1))
        val i = ChartSampleIndex(reportInspectionSeries(buckets))
        assertEquals(TrafficOrigin.LOCAL_PHONE, i.containing(500)!!.measurement!!.origin)
        assertNull(i.containing(1_500))
        assertEquals(TrafficOrigin.BEACON, i.containing(2_500)!!.measurement!!.origin)
        assertEquals(TrafficOrigin.MIXED, i.containing(3_500)!!.measurement!!.origin)
        assertEquals(1_000L, i.containing(500)!!.measurement!!.supportMs)
        assertEquals(0L, i.containing(999)!!.time)
        assertEquals(2_000L, i.containing(2_999)!!.time)
        assertNull(i.containing(4_000))
    }
    @Test fun `real temporal positions expose hours gaps`() {
        assertEquals(.01f, chartTimeFraction(1_000, 0, 100_000), .00001f)
        assertEquals(1f, chartTimeFraction(100_000, 0, 100_000), 0f)
    }
    @Test fun `report skips discontinuous intervals rather than joining neighboring indexes`() {
        val buckets = listOf(ReportChartBucket(0, 1_000, 1f, 1, 1, 0), ReportChartBucket(3_600_000, 3_601_000, 2f, 1, 1, 0))
        assertEquals(2, reportChartSamples(buckets).size)
        assertNull(ChartSampleIndex(reportInspectionSeries(buckets)).inspect(2_000_000))
    }
    @Test fun `ordinal round snap uses actual dot positions including first last and close rounds`() {
        assertEquals(0, chartOrdinalIndex(0f, 5))
        assertEquals(1, chartOrdinalIndex(.25f, 5))
        assertEquals(2, chartOrdinalIndex(.5f, 5))
        assertEquals(4, chartOrdinalIndex(1f, 5))
        assertEquals(0, chartOrdinalIndex(.9f, 1))
    }
    @Test fun `independent metric series never borrow missing values or origin from another series`() {
        val download = index(point(0, 10.0, TrafficOrigin.BEACON), point(1_000, 20.0, TrafficOrigin.MIXED))
        val upload = index(point(0, null), point(1_000, 0.0, TrafficOrigin.LOCAL_PHONE))
        assertEquals(TrafficOrigin.BEACON, download.inspect(0)!!.measurement!!.origin)
        assertNull(upload.inspect(0))
        assertEquals(0.0, upload.inspect(1_000)!!.measurement!!.value, 0.0)
        assertEquals(TrafficOrigin.MIXED, download.inspect(1_000)!!.measurement!!.origin)
    }
    @Test fun `spoken selection includes exact seconds value unit origin and an honest gap`() {
        val time = trafficTime(32_018_000, "UTC", true)
        assertEquals("08:53:38 · 27.4 W · L-Shell Beacon", chartSelectionDescription(time, "27.4 W", "L-Shell Beacon"))
        assertEquals("08:53:38 · No valid data", chartSelectionDescription(time, "No valid data", ""))
    }
    @Test fun `dense inspection chooses the original reading independently of drawing reduction`() {
        val raw = (0 until 10_000).map { point(it * 1_000L, it.toDouble()) }
        val i = index(*raw.toTypedArray())
        assertEquals(raw[9_876], i.inspect(9_876_100))
        assertEquals(raw[9_877], i.adjacent(raw[9_876].time, true))
    }
}


