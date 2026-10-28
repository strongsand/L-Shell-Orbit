package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class WidgetPresentationTest {
    @Test fun responsiveContractsAreDistinctAndSmallestStillUseful() {
        assertEquals(OrbitWidgetLayout.entries.size, OrbitWidgetLayout.entries.map { it.width to it.height }.toSet().size)
        OrbitWidgetLayout.entries.forEach {
            assertEquals(it, orbitWidgetLayout(it.width.toFloat(), it.height.toFloat()))
            assertTrue(it.width >= 144 && it.height >= 96)
        }
    }

    @Test fun lowWidthOrHeightDoesNotSelectAnOversizedHero() {
        assertEquals(OrbitWidgetLayout.GLANCE, orbitWidgetLayout(159f, 400f))
        assertEquals(OrbitWidgetLayout.GLANCE, orbitWidgetLayout(400f, 95f))
        assertEquals(OrbitWidgetLayout.PRIMARY, orbitWidgetLayout(320f, 208f))
        assertEquals(OrbitWidgetLayout.COMPACT, orbitWidgetLayout(239f, 287f))
        assertEquals(OrbitWidgetLayout.PRIMARY, orbitWidgetLayout(319f, 352f))
    }

    @Test fun threeByTwoUsesThePrimaryCompositionAndShortHostsKeepTheirFallback() {
        assertEquals(OrbitWidgetLayout.PRIMARY, orbitWidgetLayout(260f, 208f))
        assertEquals(OrbitWidgetLayout.PRIMARY, orbitWidgetLayout(300f, 224f))
        assertEquals(OrbitWidgetLayout.HORIZONTAL, orbitWidgetLayout(300f, 207f))
        assertEquals(OrbitWidgetLayout.COMPACT, orbitWidgetLayout(259f, 208f))
        assertEquals(OrbitWidgetLayout.HORIZONTAL, orbitWidgetLayout(300f, 208f, 1.3f))
    }

    @Test fun primaryMapReservesHeaderAndReadingSpaceWithoutStretching() {
        for ((w, h) in listOf(260f to 208f, 300f to 224f, 320f to 352f)) {
            val side = widgetPrimaryMapSide(w, h)
            assertTrue(side >= 125)
            assertTrue(side <= h - 72f)
            assertTrue(side <= 176)
            assertTrue(w - 32f - side >= 100f)
        }
        assertEquals(0, widgetPrimaryMapSide(0f, 0f))
    }

    @Test fun largeFontsRevealLessContextWithoutInventingAnotherWidget() {
        assertEquals(OrbitWidgetLayout.COMPACT, orbitWidgetLayout(240f, 288f, 1.3f))
        assertEquals(OrbitWidgetLayout.GLANCE, orbitWidgetLayout(160f, 208f, 1.5f))
    }

    @Test fun zeroIsARealFreeReading() {
        val p = obstructionPresentation(0f, 100f, false)
        assertEquals(0f, p.percent!!, 0f)
        assertEquals(ObstructionWidgetState.FREE, p.state)
    }

    @Test fun missingAndNonFiniteMetricsNeverBecomeZero() {
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f, 101f).forEach {
            val p = obstructionPresentation(it, 100f, false)
            assertNull(p.percent)
            assertEquals(ObstructionWidgetState.NO_DATA, p.state)
        }
    }

    @Test fun partialSurveyDoesNotClaimAFreeSky() {
        assertEquals(ObstructionWidgetState.PARTIAL, obstructionPresentation(0f, 45.5f, false).state)
        assertEquals(ObstructionWidgetState.FREE, obstructionPresentation(0f, 99.5f, false).state)
    }

    @Test fun offlineDominatesCachedZeroAndKeepsItsProvenance() {
        val p = obstructionPresentation(0f, null, true, cached = true)
        assertEquals(ObstructionWidgetState.OFFLINE, p.state)
        assertTrue(p.cachedReading)
        assertEquals(0f, p.percent!!, 0f)
    }

    @Test fun livePercentCanExistWithoutAMap() {
        val p = obstructionPresentation(0.1f, null, false)
        assertEquals(ObstructionWidgetState.LIGHT, p.state)
        assertNull(p.coverage)
    }

    @Test fun relevantObstructionAndLoadingHaveDistinctPresentations() {
        assertEquals(ObstructionWidgetState.RELEVANT, obstructionPresentation(5f, 100f, false).state)
        assertEquals(ObstructionWidgetState.LOADING, obstructionPresentation(null, null, false, loading = true).state)
    }

    @Test fun invalidGridsDoNotDivideByZeroOrFabricateCoverage() {
        assertNull(skyCoverage(0, 0, emptyList()))
        assertNull(skyCoverage(1, 5, List(5) { 0f }))
        assertNull(skyCoverage(3, 3, List(8) { 1f }))
        assertFalse(validSkyGrid(Int.MAX_VALUE, Int.MAX_VALUE, emptyList()))
    }

    @Test fun circularCoverageExcludesOutsideCellsAndMissingValues() {
        // Only the central cross is inside the 3x3 circular sky footprint.
        val cells = listOf(Float.NaN, 1f, -1f, 1f, 1f, -1f, Float.NaN, 1f, -1f)
        assertEquals(80f, skyCoverage(3, 3, cells)!!, 0f)
        assertEquals(0f, skyCoverage(3, 3, List(9) { Float.NaN })!!, 0f)
        assertEquals(100f, skyCoverage(3, 3, List(9) { 0f })!!, 0f)
    }

    @Test fun mapCellsPreserveUnknownUsablePartialAndBlocked() {
        assertEquals(SkyCell.UNKNOWN, skyCell(Float.NaN))
        assertEquals(SkyCell.UNKNOWN, skyCell(-1f))
        assertEquals(SkyCell.UNKNOWN, skyCell(1.1f))
        assertEquals(SkyCell.FREE, skyCell(1f))
        assertEquals(SkyCell.PARTIAL, skyCell(0.9f))
        assertEquals(SkyCell.BLOCKED, skyCell(0f))
    }

    @Test fun bitmapIsProportionalAndBounded() {
        assertEquals(176, widgetBitmapSide(176f, 1f))
        assertEquals(352, widgetBitmapSide(176f, 2f))
        assertEquals(384, widgetBitmapSide(176f, 4f))
        assertEquals(96, widgetBitmapSide(32f, 1f))
    }

    @Test fun compactHeadlinesFitNarrowSlotsAndBacklogsAreExplicitlyApproximate() {
        assertTrue(widgetHeadlineSize("100%", 72f, 1.5f, 22) < 22)
        assertEquals(22, widgetHeadlineSize("0%", 120f, 1f, 22))
        assertEquals("213", widgetPendingCount(213L, java.util.Locale.US))
        assertEquals("≈14k", widgetPendingCount(13_568L, java.util.Locale.US))
        assertEquals("≈2M", widgetPendingCount(2_000_000L, java.util.Locale.US))
    }

    @Test fun periodObservationIsIndependentFromAvailability() {
        assertEquals(45.5f, widgetReportCoverage(0L, 100_000L, 45_500L)!!, 0.01f)
        assertEquals(0f, widgetReportCoverage(0L, 100L, 0L)!!, 0f)
        assertEquals(100f, widgetReportCoverage(0L, 100L, 200L)!!, 0f)
        assertNull(widgetReportCoverage(100L, 100L, 50L))
    }

    @Test fun beaconHealthDoesNotConfuseOfflineWithEmptyBacklog() {
        assertEquals(BeaconWidgetState.SETUP, beaconWidgetState(false, false, false))
        assertEquals(BeaconWidgetState.OFFLINE, beaconWidgetState(true, false, false))
        assertEquals(BeaconWidgetState.RECORDING, beaconWidgetState(true, true, true))
        assertEquals(BeaconWidgetState.ONLINE, beaconWidgetState(true, true, false))
        assertEquals(BeaconWidgetState.DISH_UNAVAILABLE, beaconWidgetState(true, true, true, dishAvailable = false))
        assertEquals(BeaconWidgetState.STORAGE_WARNING, beaconWidgetState(true, true, true, storageHealthy = false))
    }

    @Test fun destinationsPreserveExistingRoutes() {
        assertEquals("DISH", OrbitWidgetDestination.OBSTRUCTION.tab)
        assertEquals("REPORTS", OrbitWidgetDestination.REPORT.tab)
        assertEquals("BEACON", OrbitWidgetDestination.BEACON.tab)
    }

    @Test fun syntheticObstructionSamplesCoverAllRequiredStates() {
        val samples = OrbitWidgetSamples.Obstruction.entries.associateWith { OrbitWidgetSamples.obstruction(it) }
        assertEquals(0f, samples.getValue(OrbitWidgetSamples.Obstruction.ZERO).presentation.percent!!, 0f)
        assertEquals(0.1f, samples.getValue(OrbitWidgetSamples.Obstruction.TRACE).presentation.percent!!, 0f)
        assertEquals(5f, samples.getValue(OrbitWidgetSamples.Obstruction.LIGHT).presentation.percent!!, 0f)
        assertEquals(ObstructionWidgetState.PARTIAL, samples.getValue(OrbitWidgetSamples.Obstruction.PARTIAL).presentation.state)
        assertNull(samples.getValue(OrbitWidgetSamples.Obstruction.MISSING).map)
        assertEquals(ObstructionWidgetState.OFFLINE, samples.getValue(OrbitWidgetSamples.Obstruction.OFFLINE).presentation.state)
        assertEquals(ObstructionWidgetState.LOADING, samples.getValue(OrbitWidgetSamples.Obstruction.LOADING).presentation.state)
    }

    @Test fun syntheticReportAndBeaconSamplesRemainClearlyDifferent() {
        assertNull(OrbitWidgetSamples.report(OrbitWidgetSamples.Report.EMPTY))
        assertEquals(45.5f, OrbitWidgetSamples.report(OrbitWidgetSamples.Report.PARTIAL)!!.coverage!!, 0f)
        assertTrue(OrbitWidgetSamples.report(OrbitWidgetSamples.Report.BAD)!!.interruptions > 0)
        assertEquals(0L, OrbitWidgetSamples.beacon(OrbitWidgetSamples.Beacon.HEALTHY).status!!.pendingRecords)
        assertEquals(213L, OrbitWidgetSamples.beacon(OrbitWidgetSamples.Beacon.BACKLOG).status!!.pendingRecords)
        assertNull(OrbitWidgetSamples.beacon(OrbitWidgetSamples.Beacon.OFFLINE).status)
        assertNull(OrbitWidgetSamples.beacon(OrbitWidgetSamples.Beacon.NEVER_SYNCED).lastSyncAt)
    }
}
