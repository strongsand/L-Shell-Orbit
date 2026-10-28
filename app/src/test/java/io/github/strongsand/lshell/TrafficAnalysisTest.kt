package io.github.strongsand.lshell

import io.github.strongsand.lshell.beacon.BeaconSampleSource
import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import io.github.strongsand.lshell.core.network.grpc.terminalHistorySize
import io.github.strongsand.lshell.core.network.grpc.terminalHistoryValue
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrafficAnalysisTest {
    private fun row(time: Long, down: Float = Float.NaN, up: Float = Float.NaN,
        kind: String = "terminal_history", source: BeaconSampleSource = BeaconSampleSource.LOCAL_PHONE) =
        HistoryEntry(time,kind,"",down,up,source = source)
    private fun analyze(rows: List<HistoryEntry>, start: Long = 10_000, end: Long = 13_000) =
        TrafficSessionAnalyzer.analyze(rows,start,end)
    @Test fun arithmeticMeanEvenAndOddMedianAndPeak() {
        val a = analyze(listOf(row(10_000,1f),row(11_000,2f),row(12_000,100f)))
        assertEquals(103.0 / 3,a.download.mean!!,1e-9)
        assertEquals(2.0,a.download.median!!,0.0)
        assertEquals(100.0,a.download.peak!!,0.0)
        assertEquals(3.0,TrafficSessionAnalyzer.median(listOf(2.0,4.0))!!,0.0)
        assertNull(TrafficSessionAnalyzer.median(emptyList()))
    }
    @Test fun integratesMbpsToDecimalBytesWithoutInventingWholePeriod() {
        val a = analyze(listOf(row(10_000,8f),row(11_000,8f)),end = 70_000)
        assertEquals(2_000_000.0,a.download.estimatedBytes!!,0.0)
        assertEquals(2000,a.download.coveredMs)
        assertEquals(58_000,a.gaps.sumOf { it.end - it.start })
    }
    @Test fun sustainedWindowsUseOnlyCompleteContiguousCoverage() {
        val rows = List(60) { row(10_000 + it * 1000L,if (it < 30) 100f else 300f) }
        val a = analyze(rows,end = 70_000)
        assertEquals(300.0,a.download.sustained30s!!,0.0)
        assertEquals(200.0,a.download.sustained1m!!,0.0)
        assertNull(a.download.sustained5m)
    }
    @Test fun sustainedFiveMinutesIsNotAnInstantSpike() {
        val a = analyze(List(300) { row(10_000 + it * 1000L,if (it == 130) 310f else 10f) },end = 310_000)
        assertEquals(310.0,a.download.peak!!,0.0)
        assertEquals(11.0,a.download.sustained5m!!,1e-9)
    }
    @Test fun gapsCannotProduceSustainedWindow() {
        val rows = List(20) { row(10_000 + it * 1000L,200f) } +
            List(20) { row(60_000 + it * 1000L,200f) }
        assertNull(analyze(rows,end = 80_000).download.sustained30s)
    }
    @Test fun irregularIntervalsUseBoundedSupport() {
        val a = analyze(listOf(row(10_000,8f,kind = "reading"),row(11_500,4f,kind = "reading"),
            row(20_000,2f,kind = "reading")),end = 25_000)
        // Canonical second timestamps: 1 s at 8, 2 s at 4, 2 s at 2 Mbps.
        assertEquals(2_500_000.0,a.download.estimatedBytes!!,0.0)
        assertEquals(5000,a.coveredMs)
    }
    @Test fun realZeroIsNotMissingAndActiveThresholdIsExplicit() {
        val a = analyze(listOf(row(10_000,0f),row(11_000,.1f),row(12_000,1f)))
        assertEquals(3,a.download.count)
        assertEquals(3000,a.coveredMs)
        // Float 0.1 has representational error; do not classify threshold equality as active.
        assertEquals(1000,a.download.activeMs)
        assertEquals(.1,a.download.usefulMinimum!!,1e-6)
    }
    @Test fun nullNanInfinityAndNegativeDoNotTurnIntoZero() {
        val a = analyze(listOf(row(10_000,Float.NaN),row(11_000,Float.POSITIVE_INFINITY),row(12_000,-1f)))
        assertEquals(0,a.download.count)
        assertNull(a.download.mean); assertNull(a.download.estimatedBytes)
        assertEquals(0,a.coveredMs); assertEquals(3000,a.gaps.single().end - a.gaps.single().start)
    }
    @Test fun independentMissingMetricsRetainOtherValues() {
        val a = analyze(listOf(row(10_000,8f),row(11_000,up = 1f)))
        assertEquals(1,a.download.count); assertEquals(1,a.upload.count)
        assertEquals(2000,a.coveredMs); assertEquals(TrafficOrigin.LOCAL_PHONE,a.origin)
    }
    @Test fun duplicateTimestampPrefersHistoryAndDoesNotDoubleCount() {
        val a = analyze(listOf(row(10_000,10f),row(10_050,999f,kind = "reading"),
            row(10_000,20f,kind = "beacon_history",source = BeaconSampleSource.BEACON)))
        assertEquals(1,a.download.count); assertEquals(10.0,a.download.mean!!,0.0)
        assertEquals(1_250_000.0,a.download.estimatedBytes!!,0.0)
    }
    @Test fun sourceFallbackIsPerMetricAndMixedIsVisible() {
        val a = analyze(listOf(row(10_000,8f),row(10_000,up = 2f,kind = "beacon_history",source = BeaconSampleSource.BEACON)))
        assertEquals(TrafficOrigin.LOCAL_PHONE,a.points.single().download!!.origin)
        assertEquals(TrafficOrigin.BEACON,a.points.single().upload!!.origin)
        assertEquals(TrafficOrigin.MIXED,a.origin)
    }
    @Test fun beaconFiveSecondSupportDoesNotFillLongOutage() {
        val a = analyze(listOf(row(10_000,8f,kind = "beacon_history",source = BeaconSampleSource.BEACON),
            row(20_000,8f,kind = "beacon_history",source = BeaconSampleSource.BEACON)),end = 30_000)
        assertEquals(10_000,a.coveredMs); assertEquals(50.0,a.coveragePercent,0.0)
        assertEquals(10_000_000.0,a.download.estimatedBytes!!,0.0)
    }
    @Test fun chartReductionKeepsSpikesAndNullSeparatorsInsideBucket() {
        val a = analyze(listOf(row(10_000,1f),row(11_000,500f),row(20_000,2f)),end = 30_000)
        val plot = TrafficSessionAnalyzer.plot(a,TrafficMetric.DOWNLOAD,1)
        assertTrue(plot.any { it.maximum == 500.0 })
        assertTrue(plot.any { it.value == null })
        assertEquals(3,TrafficSessionAnalyzer.plot(a,TrafficMetric.DOWNLOAD,1200).count { it.value != null })
    }
    @Test fun explicitStateCountsOnlyObservedDurationNotGaps() {
        val rows = listOf(row(10_000,0f,kind = "reading").copy(dishState = "NO_DOWNLINK"),
            row(20_000,0f,kind = "reading").copy(dishState = "CONNECTED"))
        val a = analyze(rows,end = 30_000)
        assertEquals(2000,a.downtimeMs); assertEquals(1,a.interruptionCount)
        assertTrue(a.events.any { it.kind == TrafficEventKind.DOWNLINK })
        assertTrue(a.events.any { it.kind == TrafficEventKind.RECONNECT })
    }
    @Test fun zeroTrafficAndMissingTelemetryAreNotOutages() {
        val a = analyze(listOf(row(10_000,0f)),end = 60_000)
        assertEquals(0,a.interruptionCount); assertEquals(0,a.downtimeMs)
        assertTrue(a.gaps.isNotEmpty())
    }
    @Test fun eventMarkersAreGroupedButMembersRemainAvailable() {
        val events = List(500) { TrafficEvent(10_000 + it * 1000L,10_000 + it * 1000L,TrafficEventKind.ALERT) }
        val grouped = TrafficSessionAnalyzer.groupedEvents(events,10_000,510_000)
        assertTrue(grouped.size <= 12); assertEquals(500,grouped.sumOf { it.members.size })
    }
    @Test fun defaultSessionNameTimezoneMidnightAndFinish() {
        val start = 1_700_000_000_000L
        val session = TrafficSessionMetadata.start("",start,"UTC",id = "test-session")
        assertTrue(session.name.isNotBlank()); assertTrue(session.active)
        val done = TrafficSessionMetadata.finish(session,start + 86_400_000)
        assertEquals(start + 86_400_000,done.endedAt)
        assertEquals(done,TrafficSessionMetadata.decode(TrafficSessionMetadata.encode(listOf(done))).single())
    }
    @Test fun sessionRetainsOnlyMetadataAndRejectsInvalidLifecycle() {
        val s = TrafficSessionMetadata.start("  game  ",10_000,"UTC",id = "session")
        assertEquals("game",s.name)
        try { TrafficSessionMetadata.finish(s,9_000); fail("backwards clock") } catch (_: IllegalArgumentException) {}
        try { TrafficSessionMetadata.decode(TrafficSessionMetadata.encode(listOf(s,s.copy(id = "other")))); fail("two active") }
        catch (_: IllegalArgumentException) {}
        assertFalse(TrafficSessionMetadata.encode(listOf(s)).contains("samples"))
    }
    @Test fun exportHasMethodSourceCoverageAndEmptyCsvCells() {
        val s = TrafficSessionMetadata.start("quote, test",10_000,"UTC",id = "session")
        val a = analyze(listOf(row(10_000,8f)))
        val exported = JSONObject(TrafficSessionExport.json(s,a,"test"))
        assertEquals("traffic_session",exported.getString("type"))
        assertEquals("LOCAL_PHONE",exported.getString("source"))
        assertTrue(exported.has("gaps")); assertTrue(exported.getString("bytes_method").contains("estimate"))
        assertTrue(TrafficSessionExport.csv(a).contains("10000,8.0,LOCAL_PHONE,,,,"))
        assertFalse(exported.toString().contains("device_id"))
    }
    @Test fun sustainedScanMatchesSmallExhaustiveStepReference() {
        val random = java.util.Random(7)
        repeat(30) {
            val values = List(90) { random.nextInt(200).toFloat() }
            val a = analyze(values.mapIndexed { i,v -> row(10_000 + i * 1000L,v) },end = 100_000)
            val expected = (0..60).maxOf { from -> values.subList(from,from + 30).average() }
            assertEquals(expected,a.download.sustained30s!!,1e-9)
        }
    }
    @Test fun independentRingsUseTheirOwnSizeAndRejectOlderCounters() {
        val down = listOf(10f,20f,30f,40f)
        val up = listOf(1f,2f)
        assertEquals(40f,terminalHistoryValue(down,7,3)!!,0f)
        assertEquals(30f,terminalHistoryValue(down,7,6)!!,0f)
        assertNull(terminalHistoryValue(up,7,4))
        assertEquals(2f,terminalHistoryValue(up,7,5)!!,0f)
        assertEquals(1f,terminalHistoryValue(up,7,6)!!,0f)
        assertNull(terminalHistoryValue(down,7,7))
    }
    @Test fun missingQualityArrayDoesNotCancelTrafficAndPaddingIsNotData() {
        val data = DishyGrpcClient.HistoryReading(2,listOf(8f,16f,0f,0f),emptyList(),emptyList(),emptyList(),emptyList())
        assertEquals(4,terminalHistorySize(data))
        assertEquals(16f,terminalHistoryValue(data.downloadMbps,data.current,1)!!,0f)
        assertNull(terminalHistoryValue(data.uploadMbps,data.current,1))
        assertNull(terminalHistoryValue(data.downloadMbps,data.current,2))
        assertNull(terminalHistoryValue(listOf(Float.NaN),2,1))
        assertNull(terminalHistoryValue(emptyList(),2,1))
        assertEquals(0f,terminalHistoryValue(listOf(0f),2,1)!!,0f)
    }
    @Test fun shortWindowsRetainEachSecondAndLongWindowsStayBounded() {
        for (seconds in listOf(300,900,86400)) {
            val a = analyze(List(seconds) { row(10_000 + it * 1000L,8f) },end = 10_000 + seconds * 1000L)
            val limit = if (seconds <= 900) 1200 else 600
            val plot = TrafficSessionAnalyzer.plot(a,TrafficMetric.DOWNLOAD,limit)
            if (seconds <= 900) assertEquals(seconds,plot.count { it.value != null })
            else assertTrue(plot.size <= limit * 2)
            assertEquals(100.0,a.coveragePercent,0.0)
        }
    }
    @Test fun expiredPrePeriodSourceCannotMarkSessionMixed() {
        val a = analyze(listOf(row(5_000,8f,kind = "beacon_history",source = BeaconSampleSource.BEACON),row(10_000,1f)))
        assertEquals(TrafficOrigin.LOCAL_PHONE,a.origin)
    }
    @Test fun duplicateSavedAndLiveEventsAreNotCountedTwice() {
        val event = HistoryEntry(10_000,"event","Conexão com a antena perdida")
        val a = analyze(listOf(event,event))
        assertEquals(1,a.events.size); assertEquals(1,a.events.single().observations)
    }
    @Test fun laterBeaconDataReanalysisFillsSessionWithoutChangingMetadata() {
        val s = TrafficSessionMetadata.finish(TrafficSessionMetadata.start("",10_000,"UTC",id = "late"),20_000)
        val before = TrafficSessionAnalyzer.analyze(listOf(row(10_000,8f)),s.startedAt,s.endedAt!!)
        val after = TrafficSessionAnalyzer.analyze(listOf(row(10_000,8f),
            row(15_000,8f,kind = "beacon_history",source = BeaconSampleSource.BEACON)),s.startedAt,s.endedAt)
        assertEquals(1000,before.coveredMs); assertEquals(6000,after.coveredMs)
        assertEquals(TrafficOrigin.MIXED,after.origin)
        assertEquals(s,TrafficSessionMetadata.decode(TrafficSessionMetadata.encode(listOf(s))).single())
    }
    @Test fun streamingExportsPreserveMissingValuesAndAllObservations() {
        val s = TrafficSessionMetadata.start("",10_000,"UTC",id = "stream")
        val a = analyze(listOf(row(10_000,8f),row(12_000,up = 2f)))
        val out = java.io.StringWriter()
        TrafficSessionExport.writeJson(out,s,a,"test")
        val parsed = JSONObject(out.toString())
        assertEquals(2,parsed.getJSONArray("samples").length())
        assertTrue(parsed.getJSONArray("samples").getJSONObject(0).isNull("upload"))
        assertEquals(TrafficSessionExport.json(s,a,"test"),out.toString())
        val csv = java.io.StringWriter(); TrafficSessionExport.writeCsv(csv,a)
        assertEquals(TrafficSessionExport.csv(a),csv.toString())
    }
    @Test fun beaconAlertsRemainVisibleWithoutRepeatedMarkers() {
        val rows = List(3) { row(10_000 + it * 1000L,8f,kind = "beacon_history",source = BeaconSampleSource.BEACON)
            .copy(activeAlerts = "Synthetic thermal warning") }
        val a = analyze(rows)
        assertEquals(1,a.events.count { it.kind == TrafficEventKind.ALERT })
        assertEquals("Synthetic thermal warning",a.events.single().detail)
    }
    @Test fun sessionIdentifierCannotEscapeExportFilename() {
        val s = TrafficSessionMetadata.start("",10_000,"UTC",id = "safe")
        val json = JSONObject(TrafficSessionMetadata.encode(listOf(s)))
        json.getJSONArray("sessions").getJSONObject(0).put("id","../../escape")
        try { TrafficSessionMetadata.decode(json.toString()); fail("unsafe identifier") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun powerPointRetainsZeroAndPositiveWattsWhileMissingStaysNull() {
        val rows = listOf(
            row(10_000,8f).copy(powerWatts = 0f),
            row(11_000,8f,kind = "beacon_history",source = BeaconSampleSource.BEACON).copy(powerWatts = 48.5f),
            row(12_000,8f).copy(powerWatts = -5f),
            row(13_000,8f).copy(powerWatts = Float.NaN)
        )
        val a = analyze(rows,start = 10_000,end = 14_000)
        assertEquals(0.0,a.points[0].power!!.value,0.0)
        assertEquals(TrafficOrigin.LOCAL_PHONE,a.points[0].power!!.origin)
        assertEquals(48.5,a.points[1].power!!.value,1e-6)
        assertEquals(TrafficOrigin.BEACON,a.points[1].power!!.origin)
        assertNull(a.points[2].power)
        assertNull(a.points[3].power)
        assertEquals(2,a.power.count)
        assertEquals(48.5,a.power.peak!!,1e-6)
        assertEquals(48.5,a.power.usefulMinimum!!,1e-6)
        val s = TrafficSessionMetadata.start("all",10_000,"UTC",id = "session-all",mode = TrafficSessionMode.BOTH)
        val exported = JSONObject(TrafficSessionExport.json(s,a,"test"))
        assertEquals(2,exported.getJSONObject("statistics").getJSONObject("power").getInt("sample_count"))
        assertEquals(48.5,exported.getJSONArray("samples").getJSONObject(1).getJSONObject("power").getDouble("value"),1e-6)
        assertTrue(TrafficSessionExport.csv(a).contains("11000,8.0,BEACON,,,,,48.5,BEACON"))
    }
}
