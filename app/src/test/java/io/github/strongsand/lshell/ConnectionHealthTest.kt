package io.github.strongsand.lshell

import io.github.strongsand.lshell.beacon.BeaconSampleSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ConnectionHealthTest {
    private val utc = ZoneId.of("UTC")
    private fun day(rows: List<HistoryEntry> = emptyList(), tests: List<RecordedTest> = emptyList(), end: Long = 60_000) =
        ConnectionHealthAnalyzer.day(rows, tests, 0, end, utc)
    private fun reading(t: Long, latency: Float = 20f, loss: Float = 0f, down: Float = 0f, state: String? = "CONNECTED", power: Float? = null) =
        HistoryEntry(t, "terminal_history", "", down, 0f, latency, loss, dishState = state, powerWatts = power)
    private fun distribution(vararg v: Double) = HealthDistribution.of(v.toList())
    private fun period() = HealthPeriod(HealthPeriodKind.WEEK, 0, 60_000, "UTC")
    private fun full() = day((0 until 60).map { reading(it * 1000L) })

    @Test fun measuredZeroIsNotMissing() {
        val d = day(listOf(reading(0, loss = 0f), reading(1000, loss = Float.NaN)))
        assertEquals(1, d.metric("loss").count.toInt()); assertEquals(0.0, d.metric("loss").mean!!, 0.0)
        assertNull(day(listOf(reading(0, loss = Float.NaN))).metric("loss").mean)
    }
    @Test fun latencyMustBePositiveAndFinite() {
        val d = day(listOf(reading(0, latency = Float.NaN), reading(1000, latency = 0f), reading(2000, latency = -1f)))
        assertNull(d.metric("latency").percentile(.5))
    }
    @Test fun emptyPeriodRemainsInsufficient() {
        val d = day(); assertEquals(0L, d.observedMs); assertNull(d.availability); assertNull(d.wattHours)
        assertEquals(HealthGrade.INSUFFICIENT, HealthComparison.grade(d))
    }
    @Test fun trueGapNotExtrapolated() {
        val d = day(listOf(reading(0), reading(59_000)))
        assertEquals(2000L, d.observedMs); assertEquals(58_000, d.longestGapMs)
        assertEquals(0L, d.offlineMs); assertEquals(0, d.outages)
    }
    @Test fun zeroThroughputIsNotAnOutage() {
        assertEquals(0, full().outages); assertEquals(100.0, full().availability!!, 0.0)
    }
    @Test fun unknownStateIsNotOnline() {
        val d = day(listOf(reading(0, state = "UNKNOWN")))
        assertNull(d.availability); assertEquals(0L, d.stateMs)
    }
    @Test fun stateDurationAndReconnectionAreBounded() {
        val d = day(listOf(reading(0, state = "NO_DOWNLINK"), reading(1000, state = "NO_DOWNLINK"), reading(2000)))
        assertEquals(1, d.outages); assertEquals(2000L, d.offlineMs); assertEquals(1, d.reconnects)
    }
    @Test fun overlappingOriginsAreNotDoubleCounted() {
        val beacon = reading(0, latency = 40f).copy(kind = "beacon_history", source = BeaconSampleSource.BEACON)
        val local = reading(0, latency = 20f)
        val d = day(listOf(beacon, local)); assertEquals(1, d.metric("latency").count.toInt())
        assertEquals(20.0, d.metric("latency").mean!!, 0.0)
    }
    @Test fun missingLocalMetricFallsBackToBeacon() {
        val local = reading(0, latency = Float.NaN)
        val beacon = reading(0, latency = 40f).copy(kind = "beacon_history", source = BeaconSampleSource.BEACON)
        assertEquals(40.0, day(listOf(local, beacon)).metric("latency").mean!!, 0.0)
    }
    @Test fun powerIntegratesOnlySupportedTime() {
        val d = day(listOf(reading(0, power = 60f), reading(1000, power = 120f), reading(2000)))
        assertEquals(2000L, d.powerCoveredMs); assertEquals(90.0, d.powerMean!!, 0.0)
        assertEquals(.05, d.wattHours!!, 1e-9)
    }
    @Test fun lossHighDurationDoesNotFillGaps() {
        assertEquals(1000L, day(listOf(reading(0, loss = 5f), reading(59_000))).lossHighMs)
    }
    @Test fun distributionMergeUsesCountsNotMedianOfMedians() {
        val a = HealthDistribution.of(List(99) { 10.0 }); val b = distribution(1000.0)
        val merged = a.merge(b)
        assertEquals(10.0, merged.percentile(.5)!!, .11)
        assertEquals(19.9, merged.mean!!, .0001); assertEquals(100, merged.count.toInt())
    }
    @Test fun logarithmicQuantilesAreExplicitlyBounded() {
        val d = HealthDistribution.of((1..100).map { it.toDouble() })
        assertEquals(50.0, d.percentile(.5)!!, .51)
        assertEquals(90.0, d.percentile(.9, 10)!!, .91)
        assertEquals(95.0, d.percentile(.95, 20)!!, .96)
    }
    @Test fun tinySampleDoesNotInventP95() {
        val d = distribution(1.0, 2.0); assertNull(d.percentile(.95, 20))
    }
    @Test fun outlierDoesNotDominateMedian() {
        assertEquals(20.0, HealthDistribution.of(List(20) { 20.0 } + 1_000_000.0).percentile(.5)!!, .21)
    }
    @Test fun lossAndLatencyLowerIsBetter() {
        assertEquals(HealthTrend.IMPROVING, HealthComparison.change("latency", 20.0, 30.0, true, absoluteThreshold = 3.0, relativeThreshold = .1).trend)
        val loss = HealthComparison.change("loss", 1.0, 2.0, true, absoluteThreshold = .5)
        assertEquals(-1.0, loss.delta!!, .0); assertEquals(HealthTrend.IMPROVING, loss.trend)
    }
    @Test fun insignificantChangesAreStable() {
        assertEquals(HealthTrend.STABLE, HealthComparison.change("download", 319.0, 318.0, false, true, 5.0, .1).trend)
    }
    @Test fun zeroBaselineCannotProduceRelativeImprovement() {
        assertNull(HealthComparison.change("download", 10.0, 0.0, false, true, 5.0).delta)
    }
    @Test fun manualAndSmartCannotProduceScheduledSpeedTrend() {
        fun tests(origin: TestOrigin) = HealthTests(origin, 0, 7, 7, 0, 0, setOf("a", "b"), distribution(10.0, 20.0, 30.0), distribution(1.0, 2.0, 3.0))
        val a = full().copy(tests = listOf(tests(TestOrigin.SMART)))
        val b = full().copy(tests = listOf(tests(TestOrigin.MANUAL)))
        assertEquals(HealthTrend.INSUFFICIENT, HealthComparison.speed(a, b, "download").first.trend)
    }
    @Test fun comparableScheduledWindowsSupportSpeedComparison() {
        fun tests(n: Double, window: Int = 0) = HealthTests(TestOrigin.SCHEDULED, window, 3, 3, 0, 0,
            setOf("a", "b"), distribution(n, n, n), distribution(10.0, 10.0, 10.0))
        val a = full().copy(tests = listOf(tests(200.0)))
        val b = full().copy(tests = listOf(tests(100.0)))
        assertEquals(HealthTrend.IMPROVING, HealthComparison.speed(a, b, "download").first.trend)
        assertEquals(3, HealthComparison.speed(a, b, "download").second)
        assertEquals(HealthTrend.INSUFFICIENT, HealthComparison.speed(a, b.copy(tests = listOf(tests(100.0, 3))), "download").first.trend)
    }
    @Test fun confidenceExposesMissingPreviousPeriod() {
        val d = full().copy(stats = full().stats + ("latency" to HealthDistribution.of(List(100) { 20.0 })))
        assertEquals(HealthConfidence.HIGH, HealthComparison.confidence(d, 7, 7, d))
        assertEquals(HealthConfidence.MEDIUM, HealthComparison.confidence(d, 7, 7, null))
        assertEquals(HealthConfidence.LOW, HealthComparison.confidence(day(), 0, 7, d))
    }
    @Test fun poorStabilityCannotBeClassifiedGood() {
        val d = full().copy(outages = 20)
        assertEquals(HealthGrade.POOR, HealthComparison.grade(d))
    }
    @Test fun provisionalReportNeverComparesHalfAWeekWithFullWeek() {
        val p = period()
        val previous = ConnectionHealthAnalyzer.report(p, listOf(full()), null, p.end, emptyList())
        val report = ConnectionHealthAnalyzer.report(p, listOf(day(end = 30_000)), previous, 30_000, emptyList())
        assertFalse(report.closed); assertTrue(report.changes.all { it.delta == null })
    }
    @Test fun snapshotRoundTripIsIndependentOfRawHistory() {
        val report = ConnectionHealthAnalyzer.report(period(), listOf(full()), null, 60_000, emptyList())
        val json = ConnectionHealthJson.report(report).toString()
        day(emptyList()) // Simulated raw cleanup cannot mutate a persisted report.
        assertEquals(report, ConnectionHealthJson.report(JSONObject(json)))
        assertEquals(report.total, ConnectionHealthJson.report(JSONObject(json)).copy(days = emptyList()).total)
        assertEquals(json, ConnectionHealthJson.report(ConnectionHealthJson.report(JSONObject(json))).toString())
    }
    @Test fun unavailablePreviousPeriodDoesNotGenerateChangeInsight() {
        val r = ConnectionHealthAnalyzer.report(period(), listOf(full()), null, 60_000, emptyList())
        assertTrue(r.changes.all { it.delta == null }); assertTrue(r.insights.isEmpty())
    }
    @Test fun singleHourlyMeasurementCannotBeBestTime() {
        assertNull(ConnectionHealthAnalyzer.bestLatencyWindow(day(listOf(reading(0)))))
    }
    @Test fun hourlyEvidenceRequiresMultipleDaysAndWindows() {
        fun h(i: Int, n: Double) = HealthHour(i, setOf("a", "b", "c"), 1_800_000, HealthDistribution.of(List(20) { n }), distribution(0.0), 1_800_000)
        assertEquals(0, ConnectionHealthAnalyzer.bestLatencyWindow(full().copy(hours = listOf(h(0, 20.0), h(3, 30.0))))!!.window)
    }
    @Test fun latencyStreakRequiresThreeAdjacentClosedPeriods() {
        val p = HealthPeriod.containing(HealthPeriodKind.WEEK, Instant.parse("2026-10-05T12:00:00Z").toEpochMilli(), utc)
        fun r(p: HealthPeriod) = HealthReport(p, p.end, p.end, true, emptyList(), listOf(HealthChange("latency", -5.0, HealthTrend.IMPROVING)),
            HealthConfidence.MEDIUM, HealthGrade.GOOD, 80.0, 0, emptyList())
        assertFalse(HealthComparison.latencyStreak(r(p), listOf(r(p.previous()))))
        assertTrue(HealthComparison.latencyStreak(r(p), listOf(r(p.previous()), r(p.previous().previous()))))
    }
    @Test fun originAndMissingMetricsSurvivePersistence() {
        for (origin in listOf(TestOrigin.MANUAL, TestOrigin.EXTREME, TestOrigin.SCHEDULED, TestOrigin.SMART)) {
            val state = ConnectionTestState(id = 1, startedAt = 1, endedAt = 10, origin = origin,
                phase = ConnectionTestPhase.COMPLETED, result = SpeedTestResult(0f, 100f, null, false, true))
            val recorded = RecordedTest.from(state)!!
            assertTrue(recorded.downloads.isEmpty()); assertEquals(listOf(100.0), recorded.uploads)
            assertEquals(recorded, RecordedTest.parse(recorded.json()))
        }
    }
    @Test fun activeExecutionIsNotArchivedAsCompleted() {
        assertNull(RecordedTest.from(ConnectionTestState(id = 1, phase = ConnectionTestPhase.RUNNING)))
    }
    @Test fun failedAndInterruptedArePreservedWithoutInventingMetrics() {
        for (phase in listOf(ConnectionTestPhase.FAILED, ConnectionTestPhase.INTERRUPTED)) {
            val t = RecordedTest.from(ConnectionTestState(id = 1, startedAt = 1, endedAt = 10, origin = TestOrigin.SCHEDULED, phase = phase))!!
            assertEquals(phase.name, t.outcome); assertTrue(t.downloads.isEmpty())
        }
    }
    @Test fun weekStartsMondayAndEndsExclusive() {
        val now = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
        val p = HealthPeriod.containing(HealthPeriodKind.WEEK, now, utc)
        assertEquals("2026-10-05T00:00:00Z", Instant.ofEpochMilli(p.start).toString())
        assertEquals("2026-10-12T00:00:00Z", Instant.ofEpochMilli(p.end).toString())
        assertEquals(p.start, p.previous().end); assertEquals(7, p.days().size)
    }
    @Test fun calendarMonthAndLeapDay() {
        val p = HealthPeriod.containing(HealthPeriodKind.MONTH, Instant.parse("2024-02-29T12:00:00Z").toEpochMilli(), utc)
        assertEquals(29, p.days().size)
    }
    @Test fun daylightSavingUsesRealLocalBounds() {
        val zone = ZoneId.of("America/New_York")
        val p = HealthPeriod.containing(HealthPeriodKind.WEEK, Instant.parse("2026-03-08T12:00:00Z").toEpochMilli(), zone)
        assertEquals(167, ((p.end - p.start) / 3_600_000).toInt()); assertEquals(7, p.days().size)
    }
    @Test fun midnightContinuationsAreMergedAcrossDays() {
        val a = day(listOf(reading(59_000, state = "NO_DOWNLINK")))
        val b = ConnectionHealthAnalyzer.day(listOf(reading(60_000, state = "NO_DOWNLINK")), emptyList(), 60_000, 120_000, utc)
        val merged = ConnectionHealthAnalyzer.combine(listOf(a, b), 0, 120_000)
        assertEquals(1, merged.outages); assertEquals(2000L, merged.longestOutageMs)
    }
    @Test fun extremeMissingMetricIsNotRecordedAsMeasuredZero() {
        val run = ExtremeRun(1, 1, 0f, 0f, null, 0f, 0f, "CONNECTED", emptyList(), 1000, 0,
            downloadMeasured = false, uploadMeasured = true)
        val state = ConnectionTestState(id = 1, kind = ConnectionTestKind.EXTREME, phase = ConnectionTestPhase.COMPLETED,
            origin = TestOrigin.EXTREME, runs = listOf(run))
        val test = RecordedTest.from(state)!!
        assertTrue(test.downloads.isEmpty()); assertEquals(listOf(0.0), test.uploads)
        val session = ExtremeSession(1, 1, 1, 1000, listOf(run))
        assertEquals(session, executionSessionFromJson(session.executionJson()))
    }
    @Test fun powerOnlyCoverageCannotValidateLatencyTrend() {
        val p = period()
        val thin = full().copy(metricCoveredMs = mapOf("latency" to 1L, "loss" to 1L))
        val previous = ConnectionHealthAnalyzer.report(p.previous(), listOf(full()), null, p.start, emptyList())
        val report = ConnectionHealthAnalyzer.report(p, listOf(thin), previous, p.end, emptyList())
        assertEquals(HealthTrend.INSUFFICIENT, report.changes.first { it.metric == "latency" }.trend)
        assertEquals(HealthGrade.INSUFFICIENT, report.grade)
    }
    @Test fun timezoneChangeDoesNotRewriteStoredBounds() {
        val p = HealthPeriod.containing(HealthPeriodKind.MONTH, Instant.parse("2026-09-20T12:00:00Z").toEpochMilli(), ZoneId.of("America/Sao_Paulo"))
        val r = ConnectionHealthAnalyzer.report(p, emptyList(), null, p.end, emptyList())
        val copy = ConnectionHealthJson.report(ConnectionHealthJson.report(r))
        assertEquals(p, copy.period); assertEquals("America/Sao_Paulo", copy.period.zone)
    }
    @Test fun delayedWorkerStillClosesTheIntendedPeriod() {
        val now = Instant.parse("2026-10-12T18:00:00Z").toEpochMilli()
        val current = HealthPeriod.containing(HealthPeriodKind.WEEK, now, utc)
        val previous = current.previous()
        assertEquals("2026-10-05T00:00:00Z", Instant.ofEpochMilli(previous.start).toString())
        assertEquals("2026-10-12T00:00:00Z", Instant.ofEpochMilli(previous.end).toString())
        assertEquals(previous.end, ConnectionHealthAnalyzer.report(previous, emptyList(), null, now, emptyList()).through)
    }
    @Test fun capturedPrefixSurvivesCompactionWithoutDuplicatingTail() {
        val cached = full()
        val lostPrefix = day(emptyList())
        val tailRows = (60 until 120).map { reading(it * 1000L) }
        val tail = ConnectionHealthAnalyzer.day(tailRows, emptyList(), 60_000, 120_000, utc)
        val available = ConnectionHealthAnalyzer.day(tailRows, emptyList(), 0, 120_000, utc)
        val retained = ConnectionHealthAnalyzer.retainPrefix(cached, lostPrefix, tail, available)
        assertEquals(120_000L, retained.observedMs); assertEquals(120L, retained.metric("latency").count)
        assertTrue(retained.resolutionPreserved)
        // An unchanged prefix must use the fresh complete aggregation, not append it twice.
        val fresh = ConnectionHealthAnalyzer.combine(listOf(cached, tail), 0, 120_000)
        assertEquals(fresh, ConnectionHealthAnalyzer.retainPrefix(cached, cached, tail, fresh))
    }
    @Test fun adjacentDailyBoundaryRecoveryIsCounted() {
        val a = day(listOf(reading(59_000, state = "NO_DOWNLINK")))
        val b = ConnectionHealthAnalyzer.day(listOf(reading(60_000)), emptyList(), 60_000, 120_000, utc)
        assertEquals(1, ConnectionHealthAnalyzer.combine(listOf(a, b), 0, 120_000).reconnects)
    }
}
