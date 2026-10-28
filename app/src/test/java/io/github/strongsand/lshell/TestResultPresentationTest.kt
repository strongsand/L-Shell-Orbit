package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

/** The refreshed screen consumes the existing result model, never a live snapshot. */
class TestResultPresentationTest {
    @Test fun normalDisplayRetainsMeasuredZeroAndRejectsInvalidOrUnmeasuredRates() {
        val state = ConnectionTestState(id = 9, phase = ConnectionTestPhase.COMPLETED,
            result = SpeedTestResult(0f, 0f, null, true, false))
        assertEquals(TestMetrics(0f, null, null), ConnectionTestResultModel.from(state).metrics)
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertEquals(TestMetrics(null, 0f, null), ConnectionTestResultModel.from(state.copy(
                result = SpeedTestResult(invalid, 0f, invalid, true, true))).metrics)
        }
    }

    @Test fun extremeDisplayMasksMissingDirectionWithoutLosingARealZeroInTheOtherDirection() {
        val run = ExtremeRun(1, 1_000, 0f, 0f, null, 0f, 0f, "CONNECTED", emptyList(),
            10_000, 0, downloadMeasured = false, uploadMeasured = true)
        val session = ExtremeSession(9, 5, 1_000, 11_000, listOf(run))
        val state = ConnectionTestState(id = 9, kind = ConnectionTestKind.EXTREME,
            phase = ConnectionTestPhase.COMPLETED, startedAt = session.startedAt, endedAt = session.endedAt,
            total = 5, completed = 1, runs = session.runs, extreme = session)
        val model = ConnectionTestResultModel.from(state)
        assertEquals(TestMetrics(null, 0f, null), model.metrics)
        assertEquals(10L, model.durationSeconds)
        assertTrue(model.averages)
        assertEquals(TestOutcome.SUCCESS, ConnectionTestExperience.outcome(state))
        assertEquals(1, state.completed)
        assertEquals(5, state.total)
        assertEquals(session, state.extreme)
    }
}
