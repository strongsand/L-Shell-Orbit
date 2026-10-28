package io.github.strongsand.lshell.beacon

import io.github.strongsand.lshell.beaconprotocol.BeaconProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AndroidBeaconCompatibilityTest {
    private val now = 1_800_000_000_000L
    @Test fun androidAbsoluteTimeNeverBorrowsAnEsp32UptimeOrAnchor() {
        val page = JSONObject().put("beacon_uptime_ms", 1000).put("beacon_boot_id", 7)
        val row = JSONObject().put("capture_uptime_ms", 500).put("boot_id", 7)
        assertThrows(BeaconHistoryTimestampException::class.java) { BeaconHistoryClock.timestamp(row, page, now, true) }
        assertEquals(now - 500, BeaconHistoryClock.timestamp(row, page, now))
        row.put("timestamp_ms", now - 86400000)
        assertEquals(now - 86400000, BeaconHistoryClock.timestamp(row, page, now, true))
    }
    @Test fun androidMissingInvalidAndZeroTimeAreRejected() {
        for (timestamp in listOf(JSONObject.NULL, 0, 3600, Long.MAX_VALUE)) {
            assertThrows(BeaconHistoryTimestampException::class.java) {
                BeaconHistoryClock.timestamp(JSONObject().put("timestamp_ms", timestamp), JSONObject(), now, true)
            }
        }
    }
    @Test fun androidAndEsp32UseTheSameChallengeCanonicalization() {
        val json = JSONObject().put("beacon_id", "a".repeat(32)).put("nonce", "b".repeat(32)).put("boot_id", 0)
            .put("time_anchor_available", false).put("from_ack", 1).put("through_sequence", 5).put("record_count", 4)
        assertEquals(BeaconManagementAuth.challengeResponse("ack", "c".repeat(32), json),
            BeaconProtocol.challengeResponse("ack", "c".repeat(32), "a".repeat(32), "b".repeat(32), 0, false, 1, 5, 4))
    }
    @Test fun configuredAndroidCannotDowngradeToLegacyAck() {
        val device = BeaconDevice("12345678-1234-1234-1234-123456789abc", "Android", "192.168.1.2", 1234,
            protocolVersion = 1, implementation = "android", capabilities = BeaconProtocol.capabilities)
        assertEquals(BeaconAckPolicy.Mode.AUTHENTICATED, BeaconAckPolicy.mode(device, device))
        assertThrows(IllegalArgumentException::class.java) { BeaconAckPolicy.mode(device, device.copy(capabilities = emptySet())) }
        val legacy = BeaconDevice("a".repeat(32), "ESP32", "192.168.1.3",80, protocolVersion = 1)
        assertEquals(BeaconAckPolicy.Mode.LEGACY, BeaconAckPolicy.mode(legacy, legacy))
    }
}
