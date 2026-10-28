package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ConnectionTestNotificationUiTest {
    private fun active(total: Int = 5) = ConnectionTestState(id = 42,
        kind = ConnectionTestKind.EXTREME, phase = ConnectionTestPhase.RUNNING,
        startedAt = 1000, total = total, completed = 1)
    private fun finished() = active().let { state ->
        val runs = listOf(ExtremeRun(1, 1000, 100f, 10f, 20f, .5f, 0f,
            "CONNECTED", emptyList(), 12000, 100))
        state.copy(phase = ConnectionTestPhase.COMPLETED, completed = 5, endedAt = 161000,
            extreme = ExtremeSession(state.id, 5, 1000, 161000, runs), runs = runs)
    }

    @Test fun normalAndExtremeNativeTransferLinesStartAtAndroid16() {
        assertFalse(ConnectionTestNotificationUi.usesProgressStyle(active(), 35))
        assertTrue(ConnectionTestNotificationUi.usesProgressStyle(active(), 36))
        assertTrue(ConnectionTestNotificationUi.usesProgressStyle(active(), 37))
        assertTrue(ConnectionTestNotificationUi.usesProgressStyle(active().copy(kind = ConnectionTestKind.NORMAL), 36))
        assertFalse(ConnectionTestNotificationUi.usesProgressStyle(finished(), 36))
    }

    @Test fun promotionDenialDemotionAndOlderAndroidDoNotChangeLayoutPolicy() {
        for (kind in ConnectionTestKind.entries) {
            val state = active().copy(kind = kind)
            assertTrue(ConnectionTestNotificationUi.requestsPromotion(state, 36, true, false))
            assertFalse(ConnectionTestNotificationUi.requestsPromotion(state, 35, true, false))
            assertFalse(ConnectionTestNotificationUi.requestsPromotion(state, 36, false, false))
            assertFalse(ConnectionTestNotificationUi.requestsPromotion(state, 36, true, true))
            assertTrue(ConnectionTestNotificationUi.usesProgressStyle(state, 36))
        }
        assertFalse(ConnectionTestNotificationUi.requestsPromotion(finished(), 36, true, false))
    }

    @Test fun segmentLengthsPreserveAllRoundsIncludingFrameworkLimitAndEveryCompletion() {
        for (total in listOf(5, 10, 11, 15)) {
            for (completed in 0..total) {
                val state = active(total).copy(completed = completed)
                val segments = ConnectionTestNotificationUi.segmentLengths(state)
                assertEquals(total, segments.sum())
                assertTrue(segments.size <= 10)
                assertEquals(total to completed, ExtremeTestNotificationModel.progress(state))
                if (total <= 10) assertEquals(List(total) { 1 }, segments)
                else assertEquals(listOf(total), segments)
            }
        }
        assertTrue(ConnectionTestNotificationUi.segmentLengths(active().copy(total = 0)).isEmpty())
        assertTrue(ConnectionTestNotificationUi.segmentLengths(active().copy(kind = ConnectionTestKind.NORMAL)).isEmpty())
    }

    @Test fun customResultRequiresRealCompletionAndMatchingResultIdentity() {
        assertTrue(ConnectionTestNotificationUi.usesCustomResult(finished(), 36, 1f, 393))
        assertTrue(ConnectionTestNotificationUi.usesCustomResult(finished(), 26, 1f, 393))
        assertFalse(ConnectionTestNotificationUi.usesCustomResult(active(), 36, 1f, 393))
        assertFalse(ConnectionTestNotificationUi.usesCustomResult(finished().copy(id = 99), 36, 1f, 393))
        assertFalse(ConnectionTestNotificationUi.usesCustomResult(finished().copy(extreme = null), 36, 1f, 393))
        for (phase in listOf(ConnectionTestPhase.FAILED, ConnectionTestPhase.INTERRUPTED)) {
            assertFalse(ConnectionTestNotificationUi.usesCustomResult(finished().copy(phase = phase), 36, 1f, 393))
        }
    }

    @Test fun nativeFallbackProtectsEnlargedFontsAndNarrowScreens() {
        assertTrue(ConnectionTestNotificationUi.usesCustomResult(finished(), 36, 1f, 360))
        assertFalse(ConnectionTestNotificationUi.usesCustomResult(finished(), 36, 1.01f, 393))
        assertFalse(ConnectionTestNotificationUi.usesCustomResult(finished(), 36, 1.3f, 393))
        assertFalse(ConnectionTestNotificationUi.usesCustomResult(finished(), 36, 1.31f, 393))
        assertFalse(ConnectionTestNotificationUi.usesCustomResult(finished(), 36, 2f, 393))
        assertFalse(ConnectionTestNotificationUi.usesCustomResult(finished(), 36, 1f, 320))
        assertFalse(ConnectionTestNotificationUi.usesCustomResult(finished(), 25, 1f, 393))
    }

    @Test fun completedExtremeReusesClassificationAveragesAndExactElapsedTime() {
        val model = ConnectionTestResultModel.from(finished())
        assertEquals(R.string.test_quality_good, model.title)
        assertEquals(R.drawable.ic_test_result_check, model.statusIcon)
        assertEquals(TestMetrics(100f, 10f, 20f), model.metrics)
        assertEquals(160L, model.durationSeconds)
        assertTrue(model.averages)
    }

    @Test fun failureAndInterruptionNeverGetSuccessIconOrFinalAverages() {
        val failed = ConnectionTestResultModel.from(finished().copy(phase = ConnectionTestPhase.FAILED))
        val interrupted = ConnectionTestResultModel.from(finished().copy(phase = ConnectionTestPhase.INTERRUPTED))
        assertEquals(R.string.test_failed, failed.title)
        assertEquals(R.drawable.ic_test_result_warning, failed.statusIcon)
        assertEquals(R.string.test_interrupted, interrupted.title)
        assertEquals(R.drawable.ic_test_result_interrupted, interrupted.statusIcon)
        assertFalse(failed.averages)
        assertFalse(interrupted.averages)
    }

    @Test fun normalResultDoesNotFabricateLatencyAndMissingMeasurementsDoNotBecomeZero() {
        val state = finished().copy(kind = ConnectionTestKind.NORMAL,
            result = SpeedTestResult(0f, 0f, null, downloadMeasured = true, uploadMeasured = false))
        val model = ConnectionTestResultModel.from(state)
        assertEquals(TestMetrics(0f, null, null), model.metrics)
        assertFalse(model.averages)
        assertEquals(R.string.test_done, model.title)
        assertTrue(ConnectionTestNotificationUi.usesCustomResult(state, 36, 1f, 393))
        assertFalse(ConnectionTestNotificationUi.usesCustomResult(state.copy(result = null), 36, 1f, 393))
    }

    @Test fun invalidQualityInputsReceiveNeutralTitleAndIcon() {
        val state = finished()
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 101f)) {
            val runs = state.runs.map { it.copy(packetLossPercent = bad) }
            val model = ConnectionTestResultModel.from(state.copy(extreme = state.extreme?.copy(runs = runs)))
            assertEquals(R.string.test_done, model.title)
            assertEquals(R.drawable.ic_test_result_info, model.statusIcon)
        }
        val runs = state.runs.map { it.copy(uploadMbps = Float.NaN) }
        assertEquals(R.string.test_done, ConnectionTestResultModel.from(
            state.copy(extreme = state.extreme?.copy(runs = runs))).title)
    }

    @Test fun invalidTimeIsOmittedInsteadOfShowingAnInventedDuration() {
        assertNull(ConnectionTestResultModel.from(finished().copy(startedAt = 0)).durationSeconds)
        assertNull(ConnectionTestResultModel.from(finished().copy(endedAt = 999)).durationSeconds)
        assertEquals(0L, ConnectionTestResultModel.from(finished().copy(endedAt = 1000)).durationSeconds)
    }

    @Test fun lastRoundShortTextOmitsRepeatedRateUnitsWithoutLosingMeasuredZero() {
        val values = TestMetrics(255f, 0f, 20f)
        assertEquals("↓ 255,0 · ↑ 0,0 · 20,0 ms", ExtremeTestNotificationModel.compactMetrics(
            values, Locale.forLanguageTag("pt-BR"), includeRateUnit = false))
        assertEquals("", ExtremeTestNotificationModel.compactMetrics(TestMetrics(), Locale.US, false))
    }
}
