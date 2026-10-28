package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ObstructionRevealTest {
    @Test fun centerWakesBeforeEdgesAndFinishedRevealShowsEveryClass() {
        assertTrue(ObstructionRevealPolicy.alpha(.3f, 0f) > ObstructionRevealPolicy.alpha(.3f, .8f))
        for (radius in listOf(0f, .25f, .5f, 1f, 1.009f)) {
            assertEquals(0f, ObstructionRevealPolicy.alpha(0f, radius), 0f)
            // Renderer explicitly uses 1 at completion (including cells clipped by the rim).
            assertTrue(ObstructionRevealPolicy.alpha(.99f, radius) > .9f)
        }
    }

    @Test fun waveIsMonotonicAndBoundedWithoutChangingRadius() {
        for (radius in listOf(0f, .1f, .5f, 1f)) {
            val alphas = (0..100).map { ObstructionRevealPolicy.alpha(it / 100f, radius) }
            assertTrue(alphas.all { it in 0f..1f })
            assertTrue(alphas.zipWithNext().all { (a, b) -> b >= a })
        }
    }

    @Test fun existingClassificationThresholdsAndUnreadCellsAreRetained() {
        val cells = ObstructionRevealPolicy.cells(5, 5, List(25) { i ->
            when (i % 4) { 0 -> -1f; 1 -> 1f; 2 -> .8f; else -> .1f }
        }, false, 0f)
        assertEquals(SkyCellClass.entries.toSet(), cells.map { it.kind }.toSet())
        assertTrue(cells.all { it.east * it.east + it.north * it.north <= 1.02f })
        assertTrue(cells.any { it.east == 0f && it.north == 0f })
    }

    @Test fun terminalRotationRetainsActualCellPositionsRadialDistanceAndClasses() {
        val data = List(25) { if (it == 12) -1f else .9f }
        val earth = ObstructionRevealPolicy.cells(5, 5, data, false, 0f)
        val terminal = ObstructionRevealPolicy.cells(5, 5, data, true, 90f)
        earth.zip(terminal).forEach { (e, t) ->
            assertEquals(-e.north, t.east, .00001f)
            assertEquals(-e.east, t.north, .00001f)
            assertEquals(e.radius, t.radius, 0f); assertEquals(e.kind, t.kind)
        }
    }

    @Test fun earthFrameDoesNotRotateWithBoresightAndSourceIsNeverMutated() {
        val data = MutableList(25) { if (it % 2 == 0) 1f else -1f }; val before = data.toList()
        assertEquals(ObstructionRevealPolicy.cells(5, 5, data, false, 0f),
            ObstructionRevealPolicy.cells(5, 5, data, false, 250f))
        assertEquals(before, data)
    }
}
