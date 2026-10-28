package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class ChartOrderTest {
    private val defaults = listOf("download", "upload", "latency", "loss")
    @Test fun emptyPreferenceGetsDefaultOrder() { assertEquals(defaults, ChartOrderPolicy.normalize(emptyList(), defaults)) }
    @Test fun persistedOrderSurvivesRoundTrip() {
        val reordered = ChartOrderPolicy.move(defaults, defaults, "upload", -1)
        assertEquals(listOf("upload", "download", "latency", "loss"), ChartOrderPolicy.normalize(reordered.joinToString(",").split(','), defaults))
    }
    @Test fun oldPreferencesCannotHideNewChartsOrDuplicateEntries() {
        assertEquals(listOf("upload", "download", "latency", "loss"), ChartOrderPolicy.normalize(listOf("unknown", "upload", "upload"), defaults))
    }
    @Test fun hiddenQualityChartsKeepTheirPositions() {
        val order = listOf("download", "latency", "upload", "loss")
        assertEquals(listOf("upload", "latency", "download", "loss"), ChartOrderPolicy.move(order, listOf("download", "upload"), "upload", -1))
    }
    @Test fun boundariesAndUnknownIdAreNoOps() {
        assertEquals(defaults, ChartOrderPolicy.move(defaults, defaults, "download", -1))
        assertEquals(defaults, ChartOrderPolicy.move(defaults, defaults, "loss", 1))
        assertEquals(defaults, ChartOrderPolicy.move(defaults, defaults, "missing", 1))
    }
}
