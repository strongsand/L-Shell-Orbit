package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class HomeShortcutTest {
    @Test fun freshHomeOpensHubWithoutStartingIt() {
        assertEquals(HomeTestDestination.HUB, HomeShortcutPolicy.destination(ConnectionTestState()))
        assertNull(HomeShortcutPolicy.result(ConnectionTestState()))
    }
    @Test fun activeExtremeEntersHubWithResumeAvailable() {
        for (phase in listOf(ConnectionTestPhase.STARTING, ConnectionTestPhase.RUNNING, ConnectionTestPhase.BETWEEN_ROUNDS)) {
            val state = ConnectionTestState(id = 10, kind = ConnectionTestKind.EXTREME, phase = phase)
            assertEquals(HomeTestDestination.HUB, HomeShortcutPolicy.destination(state))
            assertNull(HomeShortcutPolicy.result(state))
        }
    }
    @Test fun completedExtremeCannotSeizeHubEntry() {
        val state = ConnectionTestState(id = 10, kind = ConnectionTestKind.EXTREME, phase = ConnectionTestPhase.COMPLETED)
        assertEquals(HomeTestDestination.HUB, HomeShortcutPolicy.destination(state))
        assertNull(HomeShortcutPolicy.result(state)) // never pretend the last round is the session average
    }
    @Test fun interruptedAndFailedExtremeEnterHub() {
        for (phase in listOf(ConnectionTestPhase.INTERRUPTED, ConnectionTestPhase.FAILED)) {
            val state = ConnectionTestState(kind = ConnectionTestKind.EXTREME, phase = phase, result = SpeedTestResult(12f, 2f))
            assertEquals(HomeTestDestination.HUB, HomeShortcutPolicy.destination(state))
            assertNull(HomeShortcutPolicy.result(state))
        }
    }
    @Test fun activeNormalDoesNotPresentPartialValuesAsCompletedResult() {
        val state = ConnectionTestState(phase = ConnectionTestPhase.RUNNING, result = SpeedTestResult(12f, 2f))
        assertEquals(HomeTestDestination.HUB, HomeShortcutPolicy.destination(state))
        assertNull(HomeShortcutPolicy.result(state))
    }
    @Test fun completedNormalShowsRealMeasuredValuesAndPreservesOrigin() {
        for (origin in listOf(TestOrigin.MANUAL, TestOrigin.SCHEDULED, TestOrigin.SMART)) {
            val state = ConnectionTestState(phase = ConnectionTestPhase.COMPLETED, origin = origin, result = SpeedTestResult(12f, 2f))
            assertEquals(TestMetrics(12f, 2f, null), HomeShortcutPolicy.result(state))
            assertEquals(origin, state.origin)
        }
    }
    @Test fun validZeroIsNotMissing() {
        assertEquals(TestMetrics(0f, 0f), HomeShortcutPolicy.result(ConnectionTestState(
            phase = ConnectionTestPhase.COMPLETED, result = SpeedTestResult(0f, 0f))))
    }
    @Test fun unmeasuredDirectionIsNotInventedAsZero() {
        val state = ConnectionTestState(phase = ConnectionTestPhase.COMPLETED, result = SpeedTestResult(25f, 0f, uploadMeasured = false))
        assertEquals(TestMetrics(25f, null), HomeShortcutPolicy.result(state))
    }
    @Test fun invalidOrAbsentResultOffersCta() {
        assertNull(HomeShortcutPolicy.result(ConnectionTestState(phase = ConnectionTestPhase.COMPLETED)))
        assertNull(HomeShortcutPolicy.result(ConnectionTestState(phase = ConnectionTestPhase.COMPLETED,
            result = SpeedTestResult(Float.NaN, Float.POSITIVE_INFINITY))))
    }
    @Test fun processRecoveryPreservesCompletedDestinationAndValues() {
        val state = ConnectionTestState(id = 10, phase = ConnectionTestPhase.COMPLETED, result = SpeedTestResult(12f, 2f))
        val restored = ConnectionTestPresentation.recovered(state, 50L)
        assertEquals(HomeShortcutPolicy.destination(state), HomeShortcutPolicy.destination(restored))
        assertEquals(HomeShortcutPolicy.result(state), HomeShortcutPolicy.result(restored))
    }
    @Test fun homeGridFallsBackAtLargeFontAndNarrowWidths() {
        assertTrue(HealthReportUiPolicy.twoColumns(361f, 1f, 340f))
        assertFalse(HealthReportUiPolicy.twoColumns(361f, 1.5f, 340f))
        assertFalse(HealthReportUiPolicy.twoColumns(288f, 1f, 340f))
        assertTrue(HealthReportUiPolicy.twoColumns(720f, 2f, 340f))
    }
    @Test fun compactInnerGridsAlsoRespectAvailableSpaceAndFont() {
        assertTrue(HealthReportUiPolicy.twoColumns(329f, 1f, 280f))
        assertFalse(HealthReportUiPolicy.twoColumns(329f, 1.5f, 280f))
        assertFalse(HealthReportUiPolicy.twoColumns(265f, 1f, 280f))
    }
}
