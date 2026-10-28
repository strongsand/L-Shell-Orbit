package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class TestTransferActivityTest {
    @Test fun directionFollowsNewDirectionSpecificSamplesWithoutATimer() {
        val detector = SpeedTestActivityDetector()
        assertEquals(SpeedTestActivity.UNKNOWN, detector.observe(1, true, emptyList(), emptyList()))
        assertEquals(SpeedTestActivity.DOWNLOAD, detector.observe(1, true, listOf(10f), emptyList()))
        assertEquals(SpeedTestActivity.DOWNLOAD, detector.observe(1, true, listOf(10f, 20f), emptyList()))
        assertEquals(SpeedTestActivity.UPLOAD, detector.observe(1, true, listOf(10f, 20f), listOf(3f)))
        assertEquals(SpeedTestActivity.UPLOAD, detector.observe(1, true, listOf(10f, 20f), listOf(3f, 4f)))
    }

    @Test fun measuredZeroIsEvidenceButMissingNanNegativeAndInfinityAreNot() {
        val detector = SpeedTestActivityDetector()
        assertEquals(SpeedTestActivity.DOWNLOAD, detector.observe(1, true, listOf(0f), emptyList()))
        assertEquals(SpeedTestActivity.UNKNOWN, detector.observe(1, true, listOf(0f, Float.NaN, -1f, Float.POSITIVE_INFINITY), emptyList()))
        assertEquals(SpeedTestActivity.DOWNLOAD, detector.observe(1, true, listOf(0f, 0f), emptyList()))
    }

    @Test fun concurrentUnchangedAndFinishedSamplesNeverInventASerialStage() {
        val detector = SpeedTestActivityDetector()
        assertEquals(SpeedTestActivity.UNKNOWN, detector.observe(1, true, listOf(1f), listOf(2f)))
        assertEquals(SpeedTestActivity.UNKNOWN, detector.observe(1, true, listOf(1f), listOf(2f)))
        assertEquals(SpeedTestActivity.UNKNOWN, detector.observe(1, false, listOf(1f, 4f), listOf(2f)))
    }

    @Test fun aChangedServerExecutionResetsTheObservationBaseline() {
        val detector = SpeedTestActivityDetector()
        detector.observe(1, true, listOf(1f), emptyList())
        assertEquals(SpeedTestActivity.UNKNOWN, detector.observe(2, true, emptyList(), emptyList()))
        assertEquals(SpeedTestActivity.UPLOAD, detector.observe(2, true, emptyList(), listOf(0f)))
    }

    @Test fun changedLatestSampleIsRecognizedWhenFirmwareUsesAFixedLengthWindow() {
        val detector = SpeedTestActivityDetector()
        detector.observe(1, true, listOf(1f, 2f), emptyList())
        assertEquals(SpeedTestActivity.DOWNLOAD, detector.observe(1, true, listOf(2f, 3f), emptyList()))
        assertEquals(SpeedTestActivity.UNKNOWN, detector.observe(1, true, listOf(2f, 3f), emptyList()))
    }

    @Test fun directionIsEphemeralAndDoesNotChangeRealRoundProgressOrResults() {
        val base = ConnectionTestState(id = 5, kind = ConnectionTestKind.EXTREME,
            phase = ConnectionTestPhase.RUNNING, total = 5, completed = 2,
            result = SpeedTestResult(0f, 3f, downloadMeasured = true, uploadMeasured = true))
        for (activity in SpeedTestActivity.entries) {
            val state = base.copy(liveActivity = activity)
            assertEquals(base.result, state.result)
            assertEquals(2, state.completed)
            assertEquals(.4f, ConnectionTestExperience.progress(state)!!, .001f)
        }
    }

    @Test fun screenMovementAndNativeEndpointsRepresentTheSameSourceAndDestination() {
        val running = ConnectionTestState(phase = ConnectionTestPhase.RUNNING)
        assertEquals(TestTransferMode.PREPARING, TestTransferPresentation.mode(running))
        assertEquals(TestTransferMode.DOWNLOAD, TestTransferPresentation.mode(running.copy(liveActivity = SpeedTestActivity.DOWNLOAD)))
        assertEquals(TestTransferMode.UPLOAD, TestTransferPresentation.mode(running.copy(liveActivity = SpeedTestActivity.UPLOAD)))
        assertEquals(0f, TestTransferPresentation.marker(TestTransferMode.DOWNLOAD, 0f), 0f)
        assertEquals(1f, TestTransferPresentation.marker(TestTransferMode.DOWNLOAD, 1f), 0f)
        assertEquals(1f, TestTransferPresentation.marker(TestTransferMode.UPLOAD, 0f), 0f)
        assertEquals(0f, TestTransferPresentation.marker(TestTransferMode.UPLOAD, 1f), 0f)
    }

    @Test fun startupAndUnmeasuredTimeRemainIndeterminateAndBetweenRoundsKeepRealProgress() {
        val state = ConnectionTestState(phase = ConnectionTestPhase.STARTING)
        assertTrue(TestTransferPresentation.nativeIndeterminate(state))
        assertTrue(TestTransferPresentation.nativeIndeterminate(state.copy(phase = ConnectionTestPhase.RUNNING)))
        assertFalse(TestTransferPresentation.nativeIndeterminate(state.copy(phase = ConnectionTestPhase.BETWEEN_ROUNDS)))
        assertEquals(TestTransferMode.PREPARING, TestTransferPresentation.mode(state.copy(phase = ConnectionTestPhase.BETWEEN_ROUNDS)))
    }

    @Test fun animationStopsWhenHiddenFinishedOrSystemMotionIsDisabled() {
        val state = ConnectionTestState(phase = ConnectionTestPhase.RUNNING)
        assertTrue(TestTransferPresentation.animate(state, true, true))
        assertFalse(TestTransferPresentation.animate(state, false, true))
        assertFalse(TestTransferPresentation.animate(state, true, false))
        for (phase in listOf(ConnectionTestPhase.IDLE, ConnectionTestPhase.COMPLETED, ConnectionTestPhase.INTERRUPTED, ConnectionTestPhase.FAILED)) {
            assertFalse(TestTransferPresentation.animate(state.copy(phase = phase), true, true))
            assertEquals(TestTransferMode.NONE, TestTransferPresentation.mode(state.copy(phase = phase)))
        }
    }
}
