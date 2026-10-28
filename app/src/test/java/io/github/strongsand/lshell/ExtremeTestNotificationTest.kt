package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ExtremeTestNotificationTest {
    @Test fun firstRoundRemainsEmptyAndExpandedLatencyBelongsToTheCompletedRound() {
        val state = ConnectionTestState(kind = ConnectionTestKind.EXTREME,
            phase = ConnectionTestPhase.RUNNING, total = 5, completed = 1,
            result = SpeedTestResult(263.4f, 23.3f, null), runs = listOf(run(1, 263.4f, 23.3f, 24f)))
        assertEquals(TestMetrics(263.4f, 23.3f, 24f), ExtremeTestNotificationModel.activeMetrics(state))
        assertEquals(TestMetrics(), ExtremeTestNotificationModel.activeMetrics(state.copy(completed = 0)))
        assertNull(ExtremeTestNotificationModel.activeMetrics(state.copy(completed = 2)).latency)
        assertNull(ExtremeTestNotificationModel.activeMetrics(state.copy(
            runs = listOf(run(1, 263.4f, 23.3f, 24f, failure = "synthetic")))).latency)
        assertEquals(0f, ExtremeTestNotificationModel.activeMetrics(state.copy(
            runs = listOf(run(1, 263.4f, 23.3f, 0f)))).latency)
    }

    @Test fun activeRatesAreTightLocalizedAndKeepOneUnitOutsideTheNumbers() {
        val metrics = TestMetrics(263.4f, 23.3f, 24f)
        assertEquals("↓263,4 · ↑23,3", ExtremeTestNotificationModel.compactActiveRates(
            metrics, Locale.forLanguageTag("pt-BR")))
        assertEquals("↓263.4 · ↑23.3", ExtremeTestNotificationModel.compactActiveRates(metrics, Locale.US))
        assertEquals("↓263,4 · ↑23,3", ExtremeTestNotificationModel.compactActiveRates(
            metrics, Locale.forLanguageTag("es")))
    }

    @Test fun activeRatesDoNotInventMissingReadingsOrAddLatencyToTheShortLine() {
        assertEquals("", ExtremeTestNotificationModel.compactActiveRates(TestMetrics(), Locale.US))
        assertEquals("", ExtremeTestNotificationModel.compactActiveRates(TestMetrics(latency = 24f), Locale.US))
        assertEquals("↑0.0", ExtremeTestNotificationModel.compactActiveRates(
            TestMetrics(Float.NaN, 0f, 24f), Locale.US))
        assertEquals("↓0.0", ExtremeTestNotificationModel.compactActiveRates(
            TestMetrics(0f, Float.POSITIVE_INFINITY), Locale.US))
    }

    @Test fun progressCountsFinishedRoundsRatherThanTheRoundCurrentlyRunning() {
        val state = ConnectionTestState(kind = ConnectionTestKind.EXTREME,
            phase = ConnectionTestPhase.RUNNING, total = 5)
        for (finished in 0..5) {
            val current = state.copy(completed = finished)
            assertEquals(5 to finished, ExtremeTestNotificationModel.progress(current))
            assertEquals(5 to finished, ExtremeTestNotificationModel.progress(
                current.copy(phase = ConnectionTestPhase.BETWEEN_ROUNDS)))
        }
        val secondRound = state.copy(completed = 1)
        assertEquals("2/5", ConnectionTestPresentation.chip(secondRound))
        val (total, completed) = requireNotNull(ExtremeTestNotificationModel.progress(secondRound))
        assertEquals(.2f, completed.toFloat() / total, 0f)
    }

    @Test fun unknownOrInvalidProgressNeverFabricatesAPercentage() {
        val state = ConnectionTestState(kind = ConnectionTestKind.EXTREME, total = 5)
        assertNull(ExtremeTestNotificationModel.progress(state.copy(kind = ConnectionTestKind.NORMAL)))
        assertNull(ExtremeTestNotificationModel.progress(state.copy(total = 0)))
        assertNull(ExtremeTestNotificationModel.progress(state.copy(completed = -1)))
        assertNull(ExtremeTestNotificationModel.progress(state.copy(completed = 6)))
    }

    @Test fun compactMetricsRespectLocaleAndKeepMeasuredZeroWithoutMissingValues() {
        val metrics = TestMetrics(328.3f, 0f, 20f)
        assertEquals("↓ 328,3 Mbps · ↑ 0,0 Mbps · 20,0 ms",
            ExtremeTestNotificationModel.compactMetrics(metrics, Locale.forLanguageTag("pt-BR")))
        assertEquals("↓ 328.3 Mbps · ↑ 0.0 Mbps · 20.0 ms",
            ExtremeTestNotificationModel.compactMetrics(metrics, Locale.US))
        assertEquals("↓ 328,3 Mbps · ↑ 0,0 Mbps · 20,0 ms",
            ExtremeTestNotificationModel.compactMetrics(metrics, Locale.forLanguageTag("es")))
        assertEquals("", ExtremeTestNotificationModel.compactMetrics(
            TestMetrics(Float.NaN, Float.POSITIVE_INFINITY, -1f), Locale.US))
        assertEquals("20.0 ms", ExtremeTestNotificationModel.compactMetrics(
            TestMetrics(latency = 20f), Locale.US))
    }

    private fun run(index: Int, down: Float, up: Float, latency: Float?,
        bytes: Long = 100, loss: Float = 0f, failure: String? = null) =
        ExtremeRun(index, index * 1000L, down, up, latency, loss, 0f,
            "CONNECTED", emptyList(), 1000, bytes, failure = failure)

    private fun completed(runs: List<ExtremeRun>): ConnectionTestState {
        val session = ExtremeSession(42, runs.size, 1000, 9000, runs)
        return ConnectionTestState(id = 42, kind = ConnectionTestKind.EXTREME,
            phase = ConnectionTestPhase.COMPLETED, startedAt = 1000, endedAt = 9000,
            total = runs.size, completed = runs.size, runs = runs, extreme = session,
            result = SpeedTestResult(999f, 999f, 999f))
    }

    @Test fun completedSummaryUsesAllRoundsAndExistingAnalysisRatherThanLastResult() {
        val state = completed(listOf(run(1, 100f, 10f, 20f), run(2, 200f, 30f, 40f)))
        val stats = requireNotNull(ExtremeTestNotificationModel.resultStats(state))
        assertEquals(ExtremeStats.from(requireNotNull(state.extreme)), stats)
        assertEquals(150f, stats.downloadAverage, 0f)
        assertEquals(20f, stats.uploadAverage, 0f)
        assertEquals(30f, requireNotNull(stats.latencyAverage), 0f)
    }

    @Test fun activeInterruptedOrMismatchedResultNeverClaimsFinalStatistics() {
        val state = completed(listOf(run(1, 100f, 10f, 20f)))
        assertNull(ExtremeTestNotificationModel.resultStats(state.copy(phase = ConnectionTestPhase.RUNNING)))
        assertNull(ExtremeTestNotificationModel.resultStats(state.copy(phase = ConnectionTestPhase.INTERRUPTED)))
        assertNull(ExtremeTestNotificationModel.resultStats(state.copy(id = 43)))
        assertNull(ExtremeTestNotificationModel.resultStats(state.copy(kind = ConnectionTestKind.NORMAL)))
        assertNull(ExtremeTestNotificationModel.resultStats(completed(emptyList())))
    }

    @Test fun qualityLabelsFollowTheExistingResultScreenThresholds() {
        fun label(runs: List<ExtremeRun>) = ExtremeTestNotificationModel.qualityLabel(
            requireNotNull(ExtremeTestNotificationModel.resultStats(completed(runs))))
        assertEquals(R.string.test_quality_excellent, label(listOf(run(1, 100f, 10f, 20f))))
        assertEquals(R.string.test_quality_good, label(listOf(run(1, 100f, 10f, 20f, loss = .5f))))
        assertEquals(R.string.test_quality_variable, label(listOf(run(1, 100f, 10f, 20f, loss = 1f))))
        assertEquals(R.string.test_quality_unstable, label(listOf(run(1, 0f, 0f, null, failure = "synthetic"))))
    }

    @Test fun absentLatencyRemainsMissingInFinalSummary() {
        val stats = requireNotNull(ExtremeTestNotificationModel.resultStats(
            completed(listOf(run(1, 100f, 10f, null)))))
        assertNull(stats.latencyAverage)
    }

    @Test fun invalidQualityInputsDoNotAdvertiseGoodStability() {
        val stats = requireNotNull(ExtremeTestNotificationModel.resultStats(
            completed(listOf(run(1, 100f, 10f, 20f, loss = Float.NaN)))))
        assertEquals(R.string.test_done, ExtremeTestNotificationModel.qualityLabel(stats))
    }

    @Test fun estimatedDataUsesCompletedValidRoundsAndNeverInventsConsumption() {
        assertNull(ExtremeTestNotificationModel.estimatedBytes(ConnectionTestState()))
        val state = completed(listOf(run(1, 100f, 10f, 20f, bytes = 100),
            run(2, 200f, 20f, 20f, bytes = 300),
            run(3, 0f, 0f, null, bytes = 900, failure = "synthetic")))
        assertEquals(400L, ExtremeTestNotificationModel.estimatedBytes(state))
        assertEquals(0L, ExtremeTestNotificationModel.estimatedBytes(
            completed(listOf(run(1, 0f, 0f, null, bytes = 0)))))
    }

    @Test fun lastRoundLossRejectsMissingInvalidAndFailedReadingsButPreservesZero() {
        assertNull(ExtremeTestNotificationModel.lastLoss(ConnectionTestState()))
        for (loss in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 101f)) {
            assertNull(ExtremeTestNotificationModel.lastLoss(completed(listOf(run(1, 10f, 2f, null, loss = loss)))))
        }
        assertEquals(0f, ExtremeTestNotificationModel.lastLoss(completed(listOf(run(1, 10f, 2f, null)))))
        assertNull(ExtremeTestNotificationModel.lastLoss(
            completed(listOf(run(1, 0f, 0f, null, failure = "synthetic")))))
    }

    @Test fun activeMetricsStillOmitMissingAndNeverSubstituteFinalAverages() {
        val state = completed(listOf(run(1, 100f, 10f, 20f)))
            .copy(phase = ConnectionTestPhase.RUNNING, result = SpeedTestResult(0f, 0f, null, false, false))
        assertEquals(TestMetrics(), ConnectionTestPresentation.metrics(state))
        assertNull(ExtremeTestNotificationModel.resultStats(state))
    }
}
