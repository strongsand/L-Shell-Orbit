package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class ConnectionTestExperienceTest {
    private fun normal(id: Long = 42) = ConnectionTestState(id = id, startedAt = 1000,
        phase = ConnectionTestPhase.RUNNING)
    private fun extreme() = normal().copy(kind = ConnectionTestKind.EXTREME, total = 10, completed = 3)
    private fun run(failure: String? = null) = ExtremeRun(1, 1000, 0f, 10f, null,
        0f, 0f, "CONNECTED", emptyList(), 10000, 0, failure = failure)

    @Test fun runningNormalHasRealStateAndNoFabricatedRoundPercentage() {
        assertEquals(TestOutcome.ACTIVE, ConnectionTestExperience.outcome(normal()))
        assertEquals(R.string.test_running, ConnectionTestExperience.phaseLabel(normal()))
        assertEquals(R.string.test_preparing, ConnectionTestExperience.phaseLabel(
            normal().copy(phase = ConnectionTestPhase.STARTING)))
        assertNull(ConnectionTestExperience.progress(normal()))
        assertNull(ConnectionTestPresentation.chip(normal())) // Native elapsed chronometer wins.
    }

    @Test fun everyRoundUsesCompletedWorkRatherThanCurrentRoundAsProgress() {
        for (total in 5..15) for (completed in 0..total) {
            val state = extreme().copy(total = total, completed = completed)
            assertEquals(completed.toFloat() / total, ConnectionTestExperience.progress(state)!!, .00001f)
            assertEquals("${(completed + 1).coerceAtMost(total)}/$total", ConnectionTestPresentation.chip(state))
        }
        assertNull(ConnectionTestExperience.progress(extreme().copy(total = 0)))
        assertNull(ConnectionTestExperience.progress(extreme().copy(completed = 11)))
        assertEquals(R.string.test_between_rounds, ConnectionTestExperience.phaseLabel(
            extreme().copy(phase = ConnectionTestPhase.BETWEEN_ROUNDS)))
    }

    @Test fun measuredZeroCompletesPositivelyWithoutInventingMissingDirections() {
        val state = normal().copy(phase = ConnectionTestPhase.COMPLETED,
            result = SpeedTestResult(0f, 0f, null, true, false))
        assertEquals(TestOutcome.SUCCESS, ConnectionTestExperience.outcome(state))
        assertEquals(R.drawable.ic_test_result_check, ConnectionTestResultModel.from(state).statusIcon)
        assertEquals(TestMetrics(0f, null, null), ConnectionTestResultModel.from(state).metrics)
        assertEquals(R.string.test_done, ConnectionTestExperience.phaseLabel(state))
    }

    @Test fun noMeasurementsAndFailedExtremeRoundsNeverGetPositiveCompletion() {
        val missing = normal().copy(phase = ConnectionTestPhase.COMPLETED,
            result = SpeedTestResult(0f, 0f, null, false, false))
        assertEquals(TestOutcome.NO_MEASUREMENTS, ConnectionTestExperience.outcome(missing))
        assertEquals(R.string.test_no_result, ConnectionTestResultModel.from(missing).title)
        assertNotEquals(R.drawable.ic_test_result_check, ConnectionTestResultModel.from(missing).statusIcon)
        val failedRuns = extreme().copy(phase = ConnectionTestPhase.COMPLETED, runs = listOf(run("RPC failed")))
        assertEquals(TestOutcome.NO_MEASUREMENTS, ConnectionTestExperience.outcome(failedRuns))
        assertEquals(R.string.test_no_result, ConnectionTestResultModel.from(failedRuns).title)
        val valid = failedRuns.copy(runs = listOf(run(), run("RPC failed")))
        assertEquals(TestOutcome.SUCCESS, ConnectionTestExperience.outcome(valid))
    }

    @Test fun allFailedArchiveDoesNotTurnMissingAveragesIntoZero() {
        val state = extreme().copy(phase = ConnectionTestPhase.COMPLETED, runs = listOf(run("RPC failed")),
            extreme = ExtremeSession(42, 10, 1000, 9000, listOf(run("RPC failed"))))
        val model = ConnectionTestResultModel.from(state)
        assertEquals(TestMetrics(), model.metrics)
        assertEquals(R.string.test_no_result, model.title)
        assertNotEquals(R.drawable.ic_test_result_check, model.statusIcon)
    }

    @Test fun failureOrCancellationCannotInheritASuccessCheckFromAnEarlierReading() {
        for (phase in listOf(ConnectionTestPhase.FAILED, ConnectionTestPhase.INTERRUPTED)) {
            val state = normal().copy(phase = phase, result = SpeedTestResult(100f, 10f))
            assertNotEquals(TestOutcome.SUCCESS, ConnectionTestExperience.outcome(state))
            assertNotEquals(R.drawable.ic_test_result_check, ConnectionTestResultModel.from(state).statusIcon)
            assertFalse(ConnectionTestNotificationUi.requestsPromotion(state, 36, true, false))
        }
    }

    @Test fun supportedPromotionFallbackAndTerminalTransitionPreserveExecutionIdentity() {
        for (state in listOf(normal(), extreme())) {
            assertTrue(ConnectionTestNotificationUi.requestsPromotion(state, 36, true, false))
            assertTrue(ConnectionTestNotificationUi.requestsPromotion(state, 37, true, false))
            assertFalse(ConnectionTestNotificationUi.requestsPromotion(state, 35, true, false))
            assertFalse(ConnectionTestNotificationUi.requestsPromotion(state, 36, false, false))
            assertFalse(ConnectionTestNotificationUi.requestsPromotion(state, 36, true, true))
            val done = state.copy(phase = ConnectionTestPhase.COMPLETED)
            assertEquals(ConnectionTestNotificationTarget.from(state), ConnectionTestNotificationTarget.from(done))
            assertFalse(ConnectionTestNotificationUi.requestsPromotion(done, 37, true, false))
        }
    }

    @Test fun notificationTargetsCannotCollideAcrossSessionOrKind() {
        val n = ConnectionTestNotificationTarget.from(normal())
        val e = ConnectionTestNotificationTarget.from(extreme())
        assertEquals("SPEED", n.tab); assertEquals("EXTREME", e.tab)
        assertEquals(42L, n.id); assertEquals(42L, e.id)
        assertNotEquals(n.uri, e.uri)
        assertNotEquals(n.uri, ConnectionTestNotificationTarget.from(normal(43)).uri)
    }

    @Test fun savedNormalDestinationFollowsCompletionButNewMenuEntryDoesNot() {
        val active = normal()
        val saved = SpeedTestNavigation.followedId(active, null, null)
        val done = active.copy(phase = ConnectionTestPhase.COMPLETED)
        assertEquals(active.id, SpeedTestNavigation.followedId(done, null, saved))
        assertNull(SpeedTestNavigation.followedId(done, null, null))
        val explicit = ConnectionTestNotificationTarget.from(done)
        assertEquals(done.id, SpeedTestNavigation.followedId(done, explicit.id, null))
        assertEquals(done.id, SpeedTestNavigation.followedId(normal(43), explicit.id, saved))
    }

    @Test fun extremeBackgroundResumeAndRecreatedDestinationKeepRoundAndMatchingResult() {
        val active = extreme()
        val saved = ExtremeTestNavigation.followedId(active, null, null)
        assertEquals(active.id, ExtremeTestNavigation.followedId(active, null, saved))
        assertEquals("4/10", ConnectionTestPresentation.chip(active))
        val done = active.copy(phase = ConnectionTestPhase.COMPLETED)
        assertTrue(ExtremeTestNavigation.showsResult(done, ExtremeTestNavigation.followedId(done, null, saved)))
        assertFalse(ExtremeTestNavigation.showsResult(done.copy(id = 43), saved))
        assertNull(ExtremeTestNavigation.followedId(done, null, null))
    }

    @Test fun processDeathIsAnInterruptionNotAReplayOrPositiveCompletion() {
        val state = ConnectionTestPresentation.recovered(extreme(), 9000)
        assertEquals(42L, state.id); assertEquals(3, state.completed)
        assertEquals(TestOutcome.INTERRUPTED, ConnectionTestExperience.outcome(state))
        assertFalse(state.active)
    }

    @Test fun hapticsAreForTransitionsNotEachMetricAndFailedCompletionIsNotPositive() {
        val previous = normal()
        val done = previous.copy(phase = ConnectionTestPhase.COMPLETED, result = SpeedTestResult(1f, 0f))
        assertEquals(DashFeedback.CONFIRM, ConnectionTestExperience.feedback(previous, done))
        assertNull(ConnectionTestExperience.feedback(done, done))
        assertNull(ConnectionTestExperience.feedback(previous, normal(43)))
        assertNull(ConnectionTestExperience.feedback(previous, previous.copy(result = SpeedTestResult(1f, 2f))))
        assertEquals(DashFeedback.WARNING, ConnectionTestExperience.feedback(previous,
            previous.copy(phase = ConnectionTestPhase.FAILED)))
        assertEquals(DashFeedback.WARNING, ConnectionTestExperience.feedback(previous,
            previous.copy(phase = ConnectionTestPhase.COMPLETED)))
        assertNull(ConnectionTestExperience.feedback(previous, previous.copy(phase = ConnectionTestPhase.INTERRUPTED)))
        val first = extreme().copy(completed = 0)
        assertEquals(DashFeedback.TAP, ConnectionTestExperience.feedback(first, first.copy(completed = 1, runs = listOf(run()))))
        assertEquals(DashFeedback.WARNING, ConnectionTestExperience.feedback(first,
            first.copy(completed = 1, runs = listOf(run("RPC failed")))))
    }
}
