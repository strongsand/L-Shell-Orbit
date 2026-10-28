package io.github.strongsand.lshell

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class OrbitMotionTest {
    @Test fun distanceUsesVisualCoordinatesAndNearSelectionsSettleFaster() {
        assertEquals(500f, OrbitMotion.travelDistance(1f, 1f, 300f, 400f), .001f)
        val near = OrbitMotion.chartSpring(10f)
        val middle = OrbitMotion.chartSpring(80f)
        val far = OrbitMotion.chartSpring(300f)
        assertTrue(near.stiffness > middle.stiffness)
        assertTrue(middle.stiffness > far.stiffness)
        assertTrue(far.dampingRatio >= .85f)
        assertTrue(OrbitMotion.maximumChartTravelMs <= 500)
    }

    @Test fun springStartsAtCurrentPositionRetainsVelocityAndHasSmallOvershoot() {
        val animation = TargetBasedAnimation(OrbitMotion.chartSpring(300f), Float.VectorConverter,
            0f, 1f, initialVelocity = .8f)
        assertEquals(0f, animation.getValueFromNanos(0), .0001f)
        assertEquals(.8f, animation.getVelocityVectorFromNanos(0).value, .001f)
        for (ms in 0L..600L step 4) {
            assertTrue(animation.getValueFromNanos(ms * 1_000_000) <= 1.01f)
        }
        assertEquals(1f, animation.getValueFromNanos(animation.durationNanos), .0001f)
    }

    @Test fun noTravelForMissingSameSampleCrossGapOrDirectFingerInput() {
        val a = ChartMotionTarget(20, Offset(.1f, .4f), 10)
        val b = ChartMotionTarget(270, Offset(.9f, .6f), 10)
        assertTrue(chartCanTravel(a, b, false))
        assertFalse(chartCanTravel(a, b, true))
        assertFalse(chartCanTravel(a, b.copy(segment = 200), false))
        assertFalse(chartCanTravel(a, a.copy(position = Offset(.2f, .5f)), false))
        assertFalse(chartCanTravel(null, b, false))
        assertFalse(chartCanTravel(a, null, false))
    }

    @Test fun reducedMotionSnapsForEverySemanticRole() {
        for (role in OrbitMotionRole.entries) assertTrue(OrbitMotion.spec<Float>(role, false) is SnapSpec)
    }

    @Test fun interruptionsStartAtTheDisplayedPositionWithItsExistingVelocity() {
        val first = TargetBasedAnimation(OrbitMotion.chartSpring(300f), Float.VectorConverter, .1f, .9f)
        val at = 70_000_000L
        val position = first.getValueFromNanos(at)
        val velocity = first.getVelocityVectorFromNanos(at).value
        val redirected = TargetBasedAnimation(OrbitMotion.chartSpring(100f), Float.VectorConverter,
            position, .2f, initialVelocity = velocity)
        assertEquals(position, redirected.getValueFromNanos(0), .00001f)
        assertEquals(velocity, redirected.getVelocityVectorFromNanos(0).value, .00001f)
        assertEquals(.2f, redirected.getValueFromNanos(redirected.durationNanos), .00001f)
    }

    @Test fun periodGeometryReducedMotionAndTelemetryUpdatesDoNotRestartTravel() {
        val point = ChartMotionTarget(20, Offset(.1f, .4f), 10)
        val before = ChartMotionRequest(point, false, true, "period A", Size(300f, 200f))
        val next = before.copy(target = point.copy(time = 270, position = Offset(.9f, .6f)))
        assertTrue(chartShouldTravel(before, next))
        assertFalse(chartShouldTravel(before, next.copy(domain = "period B")))
        assertFalse(chartShouldTravel(before, next.copy(sizeDp = Size(200f, 300f))))
        assertFalse(chartShouldTravel(before, next.copy(enabled = false)))
        assertFalse(chartShouldTravel(before, before.copy(target = point.copy(position = Offset(.2f, .3f)))))
    }

    @Test fun invalidDrawingCoordinatesNeverStartTravelAndZeroIsAValidBoundary() {
        assertTrue(chartPositionValid(Offset.Zero))
        assertTrue(chartPositionValid(Offset(1f, 1f)))
        for (x in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 1.1f)) {
            assertFalse(chartPositionValid(Offset(x, .5f)))
            assertFalse(chartPositionValid(Offset(.5f, x)))
        }
    }

    @Test fun continuityIndexSeparatesExplicitMissingAndUnsupportedTime() {
        fun p(t: Long, value: Double?, support: Long = 1000) = MetricHistoryPoint(t,
            value?.let { TrafficValue(it, TrafficOrigin.LOCAL_PHONE, support) })
        val points = listOf(p(0, 0.0), p(1000, 1.0), p(2000, null), p(3000, 2.0), p(8000, 3.0))
        val index = ChartSampleIndex(MetricHistorySeries(0, 10000, points, 0, emptyList()))
        assertEquals(index.segmentAt(0), index.segmentAt(1000))
        assertNull(index.segmentAt(2000))
        assertNotEquals(index.segmentAt(1000), index.segmentAt(3000))
        assertNotEquals(index.segmentAt(3000), index.segmentAt(8000))
        assertEquals(0.0, index.inspect(0)!!.measurement!!.value, 0.0)
    }
}
