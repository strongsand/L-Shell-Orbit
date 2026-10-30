package io.github.strongsand.lshell

import io.github.strongsand.lshell.ar.*
import io.github.strongsand.lshell.beacon.*
import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import io.github.strongsand.lshell.proto.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceTest {
    @Test fun amoledAlwaysUsesDarkAndSystemFollowsDevice() {
        assertTrue(OrbitTheme.AMOLED.isDark(false))
        assertFalse(OrbitTheme.LIGHT.isDark(true))
        assertTrue(OrbitTheme.SYSTEM.isDark(true))
        assertFalse(OrbitTheme.SYSTEM.isDark(false))
        assertEquals(ExperienceSettings(), ExperienceSettings(OrbitTheme.SYSTEM, false))
    }
    @Test fun searchHandlesAccentsCaseAndMultipleTerms() {
        assertTrue(matchesFunctionSearch("obstrucao", "Antena e obstruções", "Starlink"))
        assertTrue(matchesFunctionSearch("BEACON estado", "Estado do Beacon", "Beacon"))
        assertFalse(matchesFunctionSearch("wifi", "Energia", "Monitoramento"))
        assertTrue(matchesFunctionSearch("   ", "AR", "Starlink"))
    }
    @Test fun optionalStatusDoesNotInventZeroOrPublishIdentity() {
        val status = DishGetStatusResponse.newBuilder().setDeviceInfo(DeviceInfo.newBuilder().setId("private-unit-id"))
            .setPopPingLatencyMs(20f).setDownlinkThroughputBps(Float.NaN).build()
        val fields = TechnicalTelemetry.status(status)
        assertEquals("20.0", fields.single { it.name == "pop_ping_latency_ms" }.value)
        assertNull(fields.single { it.name == "downlink_throughput_bps" }.value)
        assertNull(fields.single { it.name == "pop_ping_drop_rate" }.value)
        assertFalse(TechnicalTelemetry.json(fields).contains("private-unit-id"))
    }
    @Test fun circularHistoryHandlesIndependentEmptyArraysAndRealZero() {
        assertEquals(listOf(2f, 3f, 1f), TechnicalTelemetry.historyValues(listOf(1f, 2f, 3f), 4L))
        assertTrue(TechnicalTelemetry.historyValues(listOf(1f), 0L).isEmpty())
        val history = DishyGrpcClient.HistoryReading(4, listOf(0f, 1f), emptyList(), listOf(Float.NaN), listOf(0f), listOf(36f, 72f))
        val fields = TechnicalTelemetry.history(history)
        assertEquals("0.5", fields.single { it.name == "download_mbps_mean" }.value)
        assertNull(fields.single { it.name == "upload_mbps_mean" }.value)
        assertEquals("0.0", fields.single { it.name == "drop_percent_mean" }.value)
        assertEquals("0.03", fields.single { it.name == "energy_in_history_wh" }.value)
    }
    private fun map(frame: DishyGrpcClient.ObstructionMapReading.ReferenceFrame = DishyGrpcClient.ObstructionMapReading.ReferenceFrame.EARTH,
        theta: Float? = 80f) = DishyGrpcClient.ObstructionMapReading(5, 5,
            List(25) { index -> if (index == 2) 0f else if (index == 22) -1f else 1f }, theta, frame)
    @Test fun geographicOverlayPlacesNorthAndLeavesUnmappedUnmapped() {
        val projection = requireNotNull(DishyMapProjection.from(map()))
        assertEquals(DishySkyRegion.OBSTRUCTED, projection.region(0f, 10f))
        assertEquals(DishySkyRegion.UNMAPPED, projection.region(180f, 10f))
        assertEquals(DishySkyRegion.CLEAR, projection.region(90f, 90f))
        assertEquals(DishySkyRegion.OUTSIDE, projection.region(0f, 0f))
    }
    @Test fun uncertainFramesAndMissingThetaCannotFabricateProjection() {
        assertNull(DishyMapProjection.from(map(DishyGrpcClient.ObstructionMapReading.ReferenceFrame.UNKNOWN)))
        assertNull(DishyMapProjection.from(map(DishyGrpcClient.ObstructionMapReading.ReferenceFrame.USER_TERMINAL)))
        assertNull(DishyMapProjection.from(map(theta = null)))
        assertEquals(DishySkyRegion.UNMAPPED, DishyMapProjection.classify(Float.NaN))
        assertEquals(DishySkyRegion.UNMAPPED, DishyMapProjection.classify(2f))
    }
    @Test fun overlayWorkIsBoundedAndNeverPaintsEmptyTiles() {
        val large = DishyGrpcClient.ObstructionMapReading(123, 123, List(123 * 123) { -1f }, 80f,
            DishyGrpcClient.ObstructionMapReading.ReferenceFrame.EARTH)
        assertTrue(requireNotNull(DishyMapProjection.from(large)).cells().isEmpty())
        assertTrue(requireNotNull(DishyMapProjection.from(large.copy(usableFractions = List(123 * 123) { 1f }))).cells().size <= 576)
    }
    @Test fun eventHistoryIsBoundedAndUnknownStatusIsNotZero() {
        var events = emptyList<BeaconUiEvent>()
        repeat(100) { events = appendBeaconEvent(events, BeaconUiEvent(it.toLong(), BeaconUiEventKind.ACK)) }
        assertEquals(16, events.size)
        assertEquals(84L, events.first().timestamp)
        val status = BeaconRuntimeStatus(BeaconDevice("test", "Beacon", "192.0.2.1", 80))
        assertNull(status.bootId)
        assertNull(status.acknowledgedSequence)
        assertNull(status.lastImportedRecords)
    }
}
