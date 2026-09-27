package io.github.strongsand.lshell.ar

import org.junit.Assert.*
import org.junit.Test

class HorizonProjectionTest {
    @Test fun levelCameraSeparatesSkyFromGround() {
        val horizon = projectHorizon(400f, 800f, 0f, 0f)
        assertEquals(0f, horizon.groundDistance(200f, 400f), 0.001f)
        assertTrue(horizon.groundDistance(200f, 100f) < 0f)
        assertTrue(horizon.groundDistance(200f, 700f) > 0f)
    }

    @Test fun rollRotatesTheGroundSideWithTheHorizon() {
        val clockwise = projectHorizon(400f, 800f, 0f, 90f)
        assertTrue(clockwise.groundDistance(350f, 400f) > 0f)
        assertTrue(clockwise.groundDistance(50f, 400f) < 0f)
        val counterclockwise = projectHorizon(400f, 800f, 0f, -90f)
        assertTrue(counterclockwise.groundDistance(50f, 400f) > 0f)
        val inverted = projectHorizon(400f, 800f, 0f, 180f)
        assertTrue(inverted.groundDistance(200f, 100f) > 0f)
    }

    @Test fun lookingUpDoesNotDarkenTheWholeCamera() {
        val horizon = projectHorizon(400f, 800f, 90f, 0f)
        assertTrue(horizon.groundDistance(0f, 0f) < 0f)
        assertTrue(horizon.groundDistance(400f, 800f) < 0f)
    }

    @Test fun lookingDownShadesAllVisibleGround() {
        val horizon = projectHorizon(400f, 800f, -90f, 0f)
        assertTrue(horizon.groundDistance(0f, 0f) > 0f)
        assertTrue(horizon.groundDistance(400f, 800f) > 0f)
    }

    @Test fun tiltedHorizonUsesTheSameFieldOfViewAsMarkers() {
        val horizon = projectHorizon(600f, 400f, 15f, 45f)
        // Elevation offset is 15 / 60 * 600 = 150 pixels along the ground normal.
        assertEquals(-150f, horizon.groundDistance(300f, 200f), 0.001f)
        assertEquals(0f, horizon.groundDistance(horizon.x, horizon.y), 0.001f)
        assertEquals(42f, horizon.groundDistance(horizon.x + horizon.normalX * 42f,
            horizon.y + horizon.normalY * 42f), 0.001f)
    }
}
