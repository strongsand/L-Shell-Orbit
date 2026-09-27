package io.github.strongsand.lshell.core.network.grpc

import io.github.strongsand.lshell.core.model.DishState
import io.github.strongsand.lshell.core.model.LatencyStats
import io.github.strongsand.lshell.core.model.DishDeviceInfo
import io.github.strongsand.lshell.proto.DishGetStatusResponse
import io.github.strongsand.lshell.proto.DishOutage
import io.github.strongsand.lshell.proto.DeviceState
import org.junit.Assert.*
import org.junit.Test

class TelemetryTest {
    /** Explicit synthetic baseline: no values originate from a terminal capture. */
    private fun syntheticStatus(): DishGetStatusResponse = DishGetStatusResponse.newBuilder()
        .setDeviceState(DeviceState.newBuilder().setUptimeS(123L).build())
        .setPopPingLatencyMs(20f)
        .setDownlinkThroughputBps(2_000_000f)
        .setUplinkThroughputBps(200_000f)
        .setEthSpeedMbps(1000)
        .setIsSnrAboveNoiseFloor(true)
        .build()

    @Test fun syntheticResponseDecodesEthernetSignalAndConnectedState() {
        val snapshot = DishyTelemetryMapper.map(syntheticStatus())
        assertEquals(DishState.CONNECTED, snapshot.state)
        assertEquals(1000, snapshot.deviceInfo.ethSpeedMbps)
        assertEquals("1000 Mbps", snapshot.deviceInfo.formattedEthernetSpeed)
        assertEquals(true, snapshot.latency.isSnrAboveNoiseFloor)
        assertEquals("Adequado", snapshot.latency.formattedSignal)
        assertTrue(snapshot.latency.popPingLatencyMs > 0)
    }

    @Test fun rawOutageFieldDoesNotBecomeEthernetSpeed() {
        // Field 1014, length 2, nested field 1 = NO_SCHEDULE (4).
        val interruption = byteArrayOf(0xb2.toByte(), 0x3f, 0x02, 0x08, 0x04)
        val status = DishGetStatusResponse.parseFrom(syntheticStatus().toByteArray() + interruption)
        val snapshot = DishyTelemetryMapper.map(status)
        assertEquals(DishState.SEARCHING, snapshot.state)
        assertEquals(1000, snapshot.deviceInfo.ethSpeedMbps)
    }

    @Test fun allKnownOutagesMapAndUnknownDoesNotBecomeConnected() {
        val states = mapOf(1 to DishState.BOOTING, 2 to DishState.STOWED,
            3 to DishState.THERMAL_SHUTDOWN, 4 to DishState.SEARCHING,
            5 to DishState.NO_SATS, 6 to DishState.OBSTRUCTED,
            7 to DishState.NO_DOWNLINK, 8 to DishState.NO_PINGS,
            9 to DishState.ACTUATOR_ACTIVITY, 10 to DishState.CABLE_TEST,
            11 to DishState.SLEEPING, 13 to DishState.SEARCHING,
            14 to DishState.INHIBIT_RF, 0 to DishState.UNKNOWN, 99 to DishState.UNKNOWN)
        states.forEach { (cause, expected) ->
            val status = syntheticStatus().toBuilder()
                .setOutage(DishOutage.newBuilder().setCauseValue(cause))
                .build()
            assertEquals(expected, DishyTelemetryMapper.map(status).state)
        }
    }

    @Test fun absentMeasurementsDoNotInventZerosOrGoodSignal() {
        assertEquals("Não informado", LatencyStats().formattedSignal)
        assertEquals("Não informado", DishDeviceInfo().formattedEthernetSpeed)
        val status = syntheticStatus().toBuilder()
            .clearEthSpeedMbps()
            .setIsSnrAboveNoiseFloor(false)
            .build()
        val snapshot = DishyTelemetryMapper.map(status)
        assertNull(snapshot.deviceInfo.ethSpeedMbps)
        assertEquals("Baixo", snapshot.latency.formattedSignal)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyResponseIsNotConnected() {
        DishyTelemetryMapper.map(DishGetStatusResponse.getDefaultInstance())
    }

    @Test fun thermalShutdownUsesFieldTwoNotThrottle() {
        // Status field 1005 (alerts), nested field 2 = true.
        val alerts = byteArrayOf(0xea.toByte(), 0x3e, 0x02, 0x10, 0x01)
        val status = DishGetStatusResponse.parseFrom(
            syntheticStatus().toBuilder().clearAlerts().build().toByteArray() + alerts
        )
        val snapshot = DishyTelemetryMapper.map(status)
        assertTrue(snapshot.alerts.thermalShutdown)
        assertFalse(snapshot.alerts.thermalThrottle)
    }
}
