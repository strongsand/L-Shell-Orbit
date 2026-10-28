package io.github.strongsand.lshell.beacon

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BeaconBootAnchorTest {
    private val epoch = 1_800_000_000_000L
    private fun anchor(boot: Long, uptime: Long = 10_000) = JSONObject().put("boot_id", boot)
        .put("epoch_ms", epoch).put("uptime_ms", uptime).put("source", "host")
    private fun page(vararg anchors: JSONObject) = JSONObject().put("beacon_boot_id", 9)
        .put("beacon_uptime_ms", 40_000).put("boot_anchors", JSONArray(anchors.toList()))
    private fun row(boot: Long, uptime: Long) = JSONObject().put("boot_id", boot).put("capture_uptime_ms", uptime)

    @Test fun previousBootUsesItsPersistedAnchorNotReceipt() {
        assertEquals(epoch + 5_000, BeaconHistoryClock.timestamp(row(7, 15_000), page(anchor(7)), epoch + 86_400_000))
    }
    @Test fun currentBootUsesTheSameFormula() {
        assertEquals(epoch + 5_000, BeaconHistoryClock.timestamp(row(9, 15_000), page(anchor(9)), epoch + 1_000))
    }
    @Test fun multipleBootsUseIndependentReferences() {
        val p = page(anchor(7), anchor(8, 20_000), anchor(9, 30_000))
        assertEquals(listOf(epoch + 5_000, epoch - 5_000, epoch - 15_000),
            listOf(7L, 8L, 9L).map { BeaconHistoryClock.timestamp(row(it, 15_000), p, epoch) })
    }
    @Test fun negativeCapturedUptimeIsRealDishCatchup() {
        assertEquals(epoch - 15_000, BeaconHistoryClock.timestamp(row(7, -5_000), page(anchor(7)), epoch))
    }
    @Test fun unknownOldBootNeverBorrowsAnotherAnchor() {
        assertThrows(BeaconHistoryTimestampException::class.java) {
            BeaconHistoryClock.timestamp(row(6, 15_000), page(anchor(7)), epoch)
        }
    }
    @Test fun duplicateBootAnchorsAreRejected() {
        assertThrows(BeaconHistoryTimestampException::class.java) {
            BeaconHistoryClock.timestamp(row(7, 15_000), page(anchor(7), anchor(7)), epoch)
        }
    }
    @Test fun invalidUtcOrSourceCannotInventTimestamp() {
        for (a in listOf(anchor(7).put("epoch_ms", 0), anchor(7).put("source", "unknown"), anchor(7, -1))) {
            assertThrows(BeaconHistoryTimestampException::class.java) {
                BeaconHistoryClock.timestamp(row(7, 15_000), page(a), epoch)
            }
        }
    }
    @Test fun overflowIsRejected() {
        assertThrows(BeaconHistoryTimestampException::class.java) {
            BeaconHistoryClock.timestamp(row(7, Long.MIN_VALUE), page(anchor(7)), epoch)
        }
    }
    @Test fun futureCaptureForCurrentBootIsRejected() {
        assertThrows(BeaconHistoryTimestampException::class.java) {
            BeaconHistoryClock.timestamp(row(9, 40_001), page(anchor(9)), epoch)
        }
    }
    @Test fun excessivelyLargeTableIsRejected() {
        assertThrows(BeaconHistoryTimestampException::class.java) {
            BeaconHistoryClock.timestamp(row(7, 15_000), page(*Array(33) { anchor(it.toLong()) }), epoch)
        }
    }
}
