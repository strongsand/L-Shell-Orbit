package com.hurricane.lshell.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DishViewFilterTest {
    @Test fun includesDishBoresightAndWraparound() {
        assertTrue(isWithinDishView(359f, 60f, 1f, 60f))
    }

    @Test fun excludesOppositeSideAndInvalidReadings() {
        assertFalse(isWithinDishView(180f, 60f, 0f, 60f))
        assertFalse(isWithinDishView(Float.NaN, 60f, 0f, 60f))
    }

    @Test fun checksAngularDistanceInBothAxes() {
        assertTrue(isWithinDishView(30f, 60f, 0f, 60f))
        assertFalse(isWithinDishView(90f, 20f, 0f, 60f))
    }
}
