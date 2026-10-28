package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Locale

class TrafficChartPresentationTest {
    @Test fun axisHasFiveDistinctTicksAndRoomAboveRealPeaks() {
        listOf(.00001,.001,.02,.2,1.0,4.0,73.6,300.0,1_000.0,Float.MAX_VALUE.toDouble()).forEach { peak ->
            val axis = trafficAxis(peak)
            assertTrue(axis.maximum.isFinite())
            assertTrue("Clipped peak $peak",axis.maximum >= peak * 1.1)
            assertEquals(5,axis.ticks.size)
            assertEquals(axis.maximum,axis.ticks.first(),0.0)
            assertEquals(0.0,axis.ticks.last(),0.0)
            assertEquals(5,axis.ticks.map { axis.label(it,Locale.US) }.distinct().size)
        }
    }
    @Test fun missingAndConstantZeroHaveUsableAxis() {
        listOf(null,0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY).forEach {
            val axis = trafficAxis(it)
            assertTrue(axis.maximum.isFinite() && axis.maximum > 0)
            assertTrue(axis.step > 0)
        }
    }
    @Test fun niceTicksPreserveLowValuesAndLocale() {
        val axis = trafficAxis(.02)
        assertTrue(axis.maximum < .1)
        assertTrue(axis.label(axis.step,Locale.forLanguageTag("pt-BR")).contains(","))
        assertTrue(axis.label(axis.step,Locale.US).contains("."))
    }
    @Test fun highResolutionIncludesThirtyMinutesOnly() {
        assertEquals(1800,trafficPlotLimit(300_000))
        assertEquals(1800,trafficPlotLimit(900_000))
        assertEquals(1800,trafficPlotLimit(1_800_000))
        assertEquals(600,trafficPlotLimit(1_800_001))
        assertEquals(600,trafficPlotLimit(86_400_000))
    }
    private fun points() = listOf(
        TrafficPoint(1000,TrafficValue(1.0,TrafficOrigin.LOCAL_PHONE,1000),null,null,null),
        TrafficPoint(2000,null,null,null,null),
        TrafficPoint(8000,TrafficValue(300.0,TrafficOrigin.BEACON,5000),null,null,null))
    @Test fun inspectionSnapsToRealTimestampIncludingMissingMetric() {
        assertNull(trafficNearestPoint(emptyList(),5000))
        assertEquals(0,trafficNearestPoint(points(),0))
        assertEquals(0,trafficNearestPoint(points(),1500))
        assertEquals(1,trafficNearestPoint(points(),5000))
        assertEquals(2,trafficNearestPoint(points(),5001))
        assertEquals(2,trafficNearestPoint(points(),20_000))
        assertNull(points()[trafficNearestPoint(points(),2000)!!].download)
    }
    @Test fun shortTimeAndSeparateDateAdaptAcrossMidnight() {
        val start = Instant.parse("2026-10-01T23:50:00Z").toEpochMilli()
        val end = Instant.parse("2026-10-02T00:10:00Z").toEpochMilli()
        assertEquals("23:50",trafficTime(start,"UTC"))
        assertEquals("00:10:00",trafficTime(end,"UTC",true))
        assertFalse(trafficPeriodDate(start,start + 60_000,"UTC").contains(" – "))
        assertTrue(trafficPeriodDate(start,end,"UTC").contains(" – "))
    }
}
