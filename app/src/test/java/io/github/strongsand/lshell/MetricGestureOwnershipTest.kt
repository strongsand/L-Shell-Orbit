package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class MetricGestureOwnershipTest {
    @Test fun subSlopMovementRemainsAvailableToParent() {
        val gesture = MetricGestureOwnership(10f)
        gesture.move(4f, 5f, false)
        assertFalse(gesture.inspecting)
        assertFalse(gesture.yielded)
        gesture.move(10f, 0f, false)
        assertFalse(gesture.inspecting)
    }

    @Test fun inspectionCrossesOriginalSamplesAndGapsWithoutReleasing() {
        val gesture = MetricGestureOwnership(10f)
        val points = listOf(
            MetricHistoryPoint(1_000, TrafficValue(2.0, TrafficOrigin.LOCAL_PHONE, 1_000)),
            MetricHistoryPoint(2_000, TrafficValue(8.0, TrafficOrigin.BEACON, 1_000)),
            MetricHistoryPoint(3_000, null),
            MetricHistoryPoint(4_000, TrafficValue(0.0, TrafficOrigin.LOCAL_PHONE, 1_000)),
        )
        val samples = ChartSampleIndex(MetricHistorySeries(1_000, 5_000, points, 3_000, emptyList()))
        gesture.move(11f, 1f, false)
        val selected = (1_000L..4_000L step 500).map { time ->
            gesture.move((time / 10).toFloat(), 2f, false)
            assertTrue(gesture.inspecting)
            samples.inspect(time)?.time
        }
        assertTrue(selected.containsAll(listOf(1_000L, 2_000L, 4_000L)))
        assertTrue(selected.contains(null))
    }

    @Test fun crossingAndReversingOverSelectedPointDoesNotRestartOwnership() {
        val gesture = MetricGestureOwnership(10f)
        gesture.move(15f, 0f, false)
        listOf(30f, 31f, 30f, 29f, 0f, -30f).forEach { x ->
            gesture.move(x, 0f, false)
            assertTrue(gesture.inspecting)
        }
    }

    @Test fun diagonalAndPredominantlyVerticalMovementStayOwnedAfterRecognition() {
        val gesture = MetricGestureOwnership(10f)
        gesture.move(12f, 3f, false)
        gesture.move(13f, 80f, false)
        assertTrue(gesture.inspecting)
        gesture.move(0f, -100f, false)
        assertTrue(gesture.inspecting)
    }

    @Test fun intentionalVerticalScrollYieldsAndCannotBecomeInspectionMidStream() {
        val gesture = MetricGestureOwnership(10f)
        gesture.move(2f, 12f, false)
        assertTrue(gesture.yielded)
        gesture.move(100f, 12f, false)
        assertTrue(gesture.yielded)
        assertFalse(gesture.inspecting)
    }

    @Test fun consumptionBeforeRecognitionYieldsEvenForHorizontalMovement() {
        val gesture = MetricGestureOwnership(10f)
        gesture.move(15f, 0f, true)
        assertTrue(gesture.yielded)
        assertFalse(gesture.inspecting)
    }

    @Test fun consumptionAfterRecognitionCannotGiveStreamBackToScroll() {
        val gesture = MetricGestureOwnership(10f)
        gesture.move(15f, 0f, false)
        gesture.move(30f, 60f, true)
        assertTrue(gesture.inspecting)
        assertFalse(gesture.yielded)
    }

    @Test fun upAndCancellationReleaseOwnershipAndNextStreamStartsPending() {
        // Both exits use the same finally path in the Canvas pointer handler.
        val gesture = MetricGestureOwnership(10f)
        gesture.move(15f, 0f, false)
        gesture.end()
        assertFalse(gesture.inspecting)
        gesture.move(100f, 0f, false)
        assertFalse(gesture.inspecting)
        val next = MetricGestureOwnership(10f)
        next.move(0f, 15f, false)
        assertTrue(next.yielded)
    }
}
