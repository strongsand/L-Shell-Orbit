package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class ConnectionHealthUiTest {
    private val now = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
    private fun report(): HealthReport {
        val p = HealthPeriod.containing(HealthPeriodKind.WEEK, now, ZoneId.of("UTC"))
        return ConnectionHealthAnalyzer.report(p, emptyList(), null, now, emptyList())
    }
    private fun ui(r: HealthReport) = HealthReportUi(PreparedHealthReport.from(r)!!)
    private fun tests(origin: TestOrigin, count: Int = 3, days: Set<String> = setOf("a", "b")) = HealthTests(
        origin, 0, count, count, 0, 0, days, HealthDistribution.of(List(count) { 100.0 }), HealthDistribution.of(List(count) { 10.0 }))

    @Test fun lowCoverageIsExplicitButDoesNotSuppressValidPoints() {
        assertTrue(HealthReportUiPolicy.lowCoverage(18.5))
        assertTrue(HealthReportUiPolicy.showChart(listOf(1.0, 2.0, 3.0)))
    }
    @Test fun goodCoverageDoesNotCarryLowCoverageWarning() { assertFalse(HealthReportUiPolicy.lowCoverage(90.0)) }
    @Test fun fewPointsUseFallback() {
        assertFalse(HealthReportUiPolicy.showChart(emptyList()))
        assertFalse(HealthReportUiPolicy.showChart(listOf(1.0, null, 2.0)))
    }
    @Test fun threePointsAllowChartWithoutFillingGaps() {
        val values = listOf(1.0, null, 2.0, null, 3.0)
        assertTrue(HealthReportUiPolicy.showChart(values)); assertEquals(3, HealthReportUiPolicy.chartPoints(values))
        assertNull(values[1])
    }
    @Test fun realZeroCountsButNonFiniteDoesNot() {
        assertEquals(3, HealthReportUiPolicy.chartPoints(listOf(0.0, 0.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY, null)))
    }
    @Test fun noPreviousPeriodProducesOneEmptyComparisonModel() {
        assertTrue(ui(report()).changes.isEmpty())
    }
    @Test fun validComparisonRetainsDirectionAndFiltersInsufficient() {
        val changes = listOf(HealthChange("latency", -4.0, HealthTrend.IMPROVING),
            HealthChange("upload", null, HealthTrend.INSUFFICIENT), HealthChange("download", 5.0, HealthTrend.INSUFFICIENT))
        assertEquals(listOf(changes[0]), ui(report().copy(changes = changes)).changes)
    }
    @Test fun neutralPowerIsNotReclassifiedAsImprovement() {
        val change = HealthChange("power", 5.0, HealthTrend.STABLE)
        assertEquals(change, ui(report().copy(changes = listOf(change))).changes.single())
    }
    @Test fun provisionalAndClosedRemainDistinctWithoutNewClassification() {
        val r = report()
        assertFalse(ui(r).report.closed)
        assertTrue(ui(r.copy(closed = true)).report.closed)
        assertEquals(r.grade, ui(r).report.grade)
    }
    @Test fun missingPowerNeverCreatesEnergySectionOrZero() {
        val model = ui(report())
        assertFalse(model.hasEnergy); assertNull(model.total.powerMean)
    }
    @Test fun noInsightsDoesNotCreateEmptySection() {
        val model = ui(report())
        assertTrue(model.insights.isEmpty()); assertNull(model.bestLatencyWindow)
    }
    @Test fun insightLimitAndUnknownCodesAreSafe() {
        val r = report().copy(insights = listOf("unsupported", "smart_bias", "smart_bias"))
        assertEquals(listOf("smart_bias"), ui(r).insights)
    }
    @Test fun noManualSmartOrExtremeSubstitutionForScheduled() {
        val groups = listOf(tests(TestOrigin.MANUAL), tests(TestOrigin.SMART), tests(TestOrigin.EXTREME))
        assertNull(HealthReportUiPolicy.scheduledValue(groups, true))
    }
    @Test fun scheduledSummaryRequiresThreeValuesOnTwoDays() {
        assertNull(HealthReportUiPolicy.scheduledValue(listOf(tests(TestOrigin.SCHEDULED, 2)), true))
        assertNull(HealthReportUiPolicy.scheduledValue(listOf(tests(TestOrigin.SCHEDULED, 3, setOf("a"))), true))
        assertNotNull(HealthReportUiPolicy.scheduledValue(listOf(tests(TestOrigin.SCHEDULED)), true))
    }
    @Test fun largeFontAndNarrowWidthUseSingleColumn() {
        assertTrue(HealthReportUiPolicy.twoColumns(393f, 1f))
        assertFalse(HealthReportUiPolicy.twoColumns(393f, 1.3f))
        assertFalse(HealthReportUiPolicy.twoColumns(320f, 1f))
        assertTrue(HealthReportUiPolicy.twoColumns(321f, 1f, 280f))
        assertFalse(HealthReportUiPolicy.twoColumns(321f, 1.3f, 280f))
    }
    @Test fun compactCalendarDatesRetainCrossYearInformation() {
        assertEquals("5–11 Oct 2026", HealthReportUiPolicy.periodLabel(report().period, Locale.US))
        val month = HealthPeriod.containing(HealthPeriodKind.MONTH, now, ZoneId.of("UTC"))
        assertEquals("October 2026", HealthReportUiPolicy.periodLabel(month, Locale.US))
        val year = HealthPeriod.containing(HealthPeriodKind.WEEK, Instant.parse("2026-12-31T12:00:00Z").toEpochMilli(), ZoneId.of("UTC"))
        assertTrue(HealthReportUiPolicy.periodLabel(year, Locale.US).contains("2027"))
    }
    @Test fun historySummaryReadsStoredCoverageAndTrendWithoutReclassification() {
        val r = report().copy(changes = listOf(HealthChange("stability", null, HealthTrend.WORSENING)))
        val json = ConnectionHealthJson.report(r)
        val (coverage, trend) = HealthReportUiPolicy.listingSummary(json)
        assertEquals(r.total.coverage, coverage!!, .001)
        assertEquals(HealthTrend.WORSENING, trend)
        json.remove("aggregate")
        assertEquals(coverage, HealthReportUiPolicy.listingSummary(json).first)
    }
    @Test fun qualityRequiresObservationsNotInventedZeroOutages() {
        assertFalse(ui(report()).hasQuality)
        val r = report()
        val observed = r.total.copy(stateMs = 1000, onlineMs = 1000, observedMs = 1000)
        val model = ui(r.copy(aggregate = observed))
        assertTrue(model.hasQuality); assertEquals(0, model.total.outages)
    }
    @Test fun onlyLegacyOriginStaysInDetailsWithoutEmptySpeedSection() {
        val r = report()
        val model = ui(r.copy(aggregate = r.total.copy(tests = listOf(tests(TestOrigin.UNKNOWN)))))
        assertFalse(model.hasSpeed)
        assertEquals(TestOrigin.UNKNOWN, model.tests.single().origin)
    }
    @Test fun negativeComparisonKeepsEngineDecision() {
        val change = HealthChange("latency", 12.0, HealthTrend.WORSENING)
        assertEquals(listOf(change), ui(report().copy(changes = listOf(change))).changes)
    }
    @Test fun executiveComparisonDoesNotDeleteOtherValidComparisons() {
        val changes = listOf(HealthChange("latency", -4.0, HealthTrend.IMPROVING),
            HealthChange("loss", -.8, HealthTrend.IMPROVING), HealthChange("power", 6.0, HealthTrend.STABLE))
        val model = ui(report().copy(changes = changes))
        assertEquals(listOf(changes.first()), model.primaryChanges)
        assertEquals(changes, model.changes)
    }
    @Test fun relevantEventsDoNotTreatLossOrObstructionGroupsAsDowntime() {
        val r = report()
        val counts = mapOf("DOWNLINK" to 3, "STATE" to 2, "LOSS" to 180, "OBSTRUCTION" to 11818, "RECONNECT" to 75)
        val model = ui(r.copy(aggregate = r.total.copy(eventCounts = counts)))
        assertEquals(mapOf("DOWNLINK" to 3, "STATE" to 2), model.relevantEvents)
        assertEquals(counts, model.total.eventCounts)
    }
}
