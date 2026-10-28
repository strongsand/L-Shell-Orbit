package io.github.strongsand.lshell.ar

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ArCameraPoseTest {
    @Test fun displayRotationKeepsTheGroundBelowForAllFourUprightScreenOrientations() {
        val matrices = listOf(
            floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            floatArrayOf(0f, 1f, 0f, 0f, 0f, -1f, -1f, 0f, 0f),
            floatArrayOf(-1f, 0f, 0f, 0f, 0f, -1f, 0f, -1f, 0f),
            floatArrayOf(0f, -1f, 0f, 0f, 0f, -1f, 1f, 0f, 0f),
        )
        matrices.forEachIndexed { rotation, matrix ->
            val pose = arCameraPose(matrix, rotation, 0f)
            assertEquals(0f, pose.azimuth, 0.001f)
            assertEquals(0f, pose.elevation, 0.001f)
            assertEquals(0f, pose.roll, 0.001f)
            val horizon = projectHorizon(400f, 800f, pose.elevation, pose.roll)
            assertTrue(horizon.groundDistance(200f, 700f) > 0f)
            assertTrue(horizon.groundDistance(200f, 100f) < 0f)
        }
    }

    @Test fun tiltedHorizonFollowsGravityRatherThanMirroringThePhoneRoll() {
        for (degrees in listOf(-90.0, -45.0, 30.0, 90.0, 180.0)) {
            val c = cos(Math.toRadians(degrees)).toFloat()
            val s = sin(Math.toRadians(degrees)).toFloat()
            val matrix = floatArrayOf(c, s, 0f, 0f, 0f, -1f, -s, c, 0f)
            val pose = arCameraPose(matrix, 0, 0f)
            val horizon = projectHorizon(400f, 800f, pose.elevation, pose.roll)
            assertEquals(-matrix[6], horizon.normalX, 0.001f)
            assertEquals(matrix[7], horizon.normalY, 0.001f)
        }
    }

    @Test fun zenithAndNadirKeepTheLastDefinedRollWithoutInventingAnOrientation() {
        for (z in listOf(-1f, 1f)) {
            val matrix = floatArrayOf(1f, 0f, 0f, 0f, z, 0f, 0f, 0f, z)
            val pose = arCameraPose(matrix, 0, 37f)
            assertEquals(37f, pose.roll, 0f)
            assertEquals(-z * 90f, pose.elevation, 0.001f)
            assertTrue(pose.azimuth.isFinite())
        }
    }

    @Test fun displayRemappingDoesNotChangeTheBackCameraPointingDirection() {
        val matrix = floatArrayOf(1f, 0f, 0f, 0f, 0.8f, -0.6f, 0f, 0.6f, 0.8f)
        val reference = arCameraPose(matrix, 0, 0f)
        (1..3).forEach { rotation ->
            val pose = arCameraPose(matrix, rotation, 0f)
            assertEquals(reference.azimuth, pose.azimuth, 0f)
            assertEquals(reference.elevation, pose.elevation, 0f)
        }
    }
}
