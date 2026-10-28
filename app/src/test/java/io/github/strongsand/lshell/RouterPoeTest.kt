package io.github.strongsand.lshell

import com.google.protobuf.CodedOutputStream
import io.github.strongsand.lshell.core.network.grpc.*
import io.github.strongsand.lshell.proto.DeviceInfo
import io.github.strongsand.lshell.proto.PoeState
import io.github.strongsand.lshell.proto.PoeStats
import io.github.strongsand.lshell.proto.WifiGetStatusResponse
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class RouterPoeTest {
    private fun status(volts: Float? = 56.26529f, state: PoeState = PoeState.POE_STATE_ON) =
        WifiGetStatusResponse.newBuilder().setDeviceInfo(DeviceInfo.newBuilder().setId("router-A"))
            .setDishId("dish-A").setPoeStats(PoeStats.newBuilder().setPoeState(state).apply {
                volts?.let { setVsnsVin(it) }
            }).build()

    @Test fun inputVoltageIsReadUnscaledFromKnownWireFieldsAndIgnoresPower() {
        val bytes = ByteArrayOutputStream()
        val wire = CodedOutputStream.newInstance(bytes)
        wire.writeEnum(1, 3)
        wire.writeFloat(2, 8.968381f) // poe_power is a different field, never voltage.
        wire.writeFloat(7, 56.26529f)
        wire.flush()
        val outerBytes = ByteArrayOutputStream()
        val outerWire = CodedOutputStream.newInstance(outerBytes)
        outerWire.writeMessage(3, DeviceInfo.newBuilder().setId("router-A").build())
        outerWire.writeByteArray(1022, bytes.toByteArray())
        outerWire.writeString(1023, "dish-A")
        outerWire.flush()
        val reading = WifiGetStatusResponse.parseFrom(outerBytes.toByteArray()).poeInputReading(1_000)!!
        assertEquals(56.26529f, reading.inputVolts, .00001f)
        assertEquals("router-A", reading.routerId)
        assertEquals("dish-A", reading.dishId)
        assertEquals(1_000L, reading.receivedAtElapsedMs)
        assertEquals(56.26529f, reading.visibleInputVolts("dish-A", true, 2_000)!!, .00001f)
    }

    @Test fun omittedMessageAndOmittedVoltageStayMissing() {
        assertNull(status().toBuilder().clearPoeStats().build().poeInputReading(1_000))
        val missing = WifiGetStatusResponse.parseFrom(status(null).toByteArray())
        assertTrue(missing.hasPoeStats())
        assertFalse(missing.poeStats.hasVsnsVin())
        assertNull(missing.poeInputReading(1_000))
        assertNull(WifiGetStatusResponse.getDefaultInstance().poeInputReading(1_000))
    }

    @Test fun explicitZeroIsPreservedInTheModelButNeverAnEnergizedVoltagePlaceholder() {
        val zero = WifiGetStatusResponse.parseFrom(status(0f).toByteArray())
        assertTrue(zero.poeStats.hasVsnsVin())
        val reading = zero.poeInputReading(1_000)!!
        assertEquals(0f, reading.inputVolts, 0f)
        assertNull(reading.visibleInputVolts("dish-A", true, 2_000))
        assertNull(status(null).poeInputReading(1_000))
    }

    @Test fun invalidVoltageIsNeverCoercedToZero() {
        for (v in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull("invalid=$v", status(v).poeInputReading(1_000))
        }
    }

    @Test fun offNegotiatingWaterDetectionAndFutureStatesAreNotCurrentActivePoe() {
        for (s in PoeState.entries.filter { it != PoeState.POE_STATE_ON && it != PoeState.UNRECOGNIZED }) {
            assertNull(status(state = s).poeInputReading(1_000))
        }
        assertNull(status().toBuilder().setPoeStats(status().poeStats.toBuilder().setPoeStateValue(999))
            .build().poeInputReading(1_000))
    }

    @Test fun associationRequiresActualMatchingDishAndRouterIdentities() {
        assertNull(status().toBuilder().clearDishId().build().poeInputReading(1_000))
        assertNull(status().toBuilder().clearDeviceInfo().build().poeInputReading(1_000))
        val reading = status().poeInputReading(1_000)!!
        assertNull(reading.visibleInputVolts("dish-B", true, 2_000))
        assertNull(reading.visibleInputVolts("", true, 2_000))
        assertNull(reading.visibleInputVolts("dish-A", false, 2_000))
    }

    @Test fun expiredFutureAndOfflineReadingsAreHiddenWithoutHistoricalFallback() {
        val reading = status().poeInputReading(1_000)!!
        assertNotNull(reading.visibleInputVolts("dish-A", true, 31_000))
        assertNull(reading.visibleInputVolts("dish-A", true, 31_001))
        assertNull(reading.visibleInputVolts("dish-A", true, 999))
        assertNull(reading.visibleInputVolts("dish-A", false, 2_000))
        assertNull((null as RouterPoeReading?).visibleInputVolts("dish-A", true, 2_000))
    }
}
