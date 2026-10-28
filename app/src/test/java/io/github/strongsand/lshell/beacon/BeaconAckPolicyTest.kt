package io.github.strongsand.lshell.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BeaconAckPolicyTest {
    private val device = BeaconDevice("a".repeat(32), "Beacon", "example.local", 80, protocolVersion = 1)

    @Test fun currentFirmwareRequiresAuthenticatedAck() {
        val reported = device.copy(capabilities = setOf("management_v1", "authenticated_ack_v1"))
        assertEquals(BeaconAckPolicy.Mode.AUTHENTICATED, BeaconAckPolicy.mode(device, reported))
    }

    @Test fun olderFirmwareUsesLegacyAck() {
        val reported = device.copy(capabilities = setOf("management_v1", "history_v1"))
        assertEquals(BeaconAckPolicy.Mode.LEGACY, BeaconAckPolicy.mode(device, reported))
    }

    @Test fun advertisedAuthenticationNeverDowngradesOnIncompleteCapability() {
        val reported = device.copy(capabilities = setOf("authenticated_ack_v1"))
        assertThrows(IllegalArgumentException::class.java) { BeaconAckPolicy.mode(device, reported) }
    }

    @Test fun changedLineageOrProtocolCannotAck() {
        for (reported in listOf(device.copy(id = "b".repeat(32)), device.copy(protocolVersion = 2))) {
            assertThrows(IllegalArgumentException::class.java) { BeaconAckPolicy.mode(device, reported) }
        }
    }
}
