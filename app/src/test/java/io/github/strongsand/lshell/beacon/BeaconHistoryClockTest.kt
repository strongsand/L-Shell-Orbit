package io.github.strongsand.lshell.beacon

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BeaconHistoryClockTest {
    private val receipt = 1_800_000_000_000L
    private val page = JSONObject().put("beacon_uptime_ms", 3_600_000L).put("beacon_boot_id", 7)
    private fun row(capture: Long = 1_000, boot: Int = 7) =
        JSONObject().put("capture_uptime_ms", capture).put("boot_id", boot)

    @Test fun absoluteCaptureTimeSurvivesLaterSyncAndDifferentBoot() {
        assertEquals(receipt - 86_400_000,
            BeaconHistoryClock.timestamp(row(boot = 6).put("timestamp_ms", receipt - 86_400_000), page, receipt))
    }
    @Test fun sameBootReconstructsEachHistoricalTimeInsteadOfPageReceipt() {
        val times = (0..499).map { BeaconHistoryClock.timestamp(row(it * 5_000L), page, receipt) }
        assertEquals(500, times.toSet().size)
        assertEquals(5_000L, times[1] - times[0])
        assertEquals(receipt - 3_600_000, times.first())
    }
    @Test fun dishHistoryBeforeBeaconStartupCanHaveNegativeCaptureUptime() {
        assertEquals(receipt - 3_605_000, BeaconHistoryClock.timestamp(row(-5_000), page, receipt))
    }
    @Test fun olderBootCannotBorrowCurrentUptime() {
        assertThrows(BeaconHistoryTimestampException::class.java) {
            BeaconHistoryClock.timestamp(row(boot = 6), page, receipt)
        }
    }
    @Test fun missingBootOrTimestampIsNotNow() {
        for (sample in listOf(JSONObject(), row().also { it.remove("boot_id") }, row(3_600_001))) {
            assertThrows(BeaconHistoryTimestampException::class.java) {
                BeaconHistoryClock.timestamp(sample, page, receipt)
            }
        }
    }
    @Test fun missingPageBootAndInvalidAbsoluteTimeDoNotMatchDefaults() {
        assertThrows(BeaconHistoryTimestampException::class.java) {
            BeaconHistoryClock.timestamp(JSONObject().put("timestamp_ms", 0), JSONObject(), receipt)
        }
    }
    @Test fun invalidClockArithmeticCannotOverflowIntoAValidDate() {
        assertThrows(BeaconHistoryTimestampException::class.java) {
            BeaconHistoryClock.timestamp(row(Long.MIN_VALUE), page, receipt)
        }
    }
    @Test fun unresolvedPageNeverPersistsAcknowledgesOrAdvancesCursor() = runBlocking {
        val device = BeaconDevice("synthetic", "Beacon", "example.local", 80)
        val actions = mutableListOf<String>()
        val cursor = object : BeaconSyncCursor {
            override fun configuredBeacon() = device
            override fun pairedBeacon(): PairedBeacon? = null
            override fun lastSyncedSequence() = 0L
            override fun setLastSyncedSequence(sequence: Long) { actions += "cursor" }
        }
        val sink = object : BeaconHistorySink {
            override fun latestBeaconSequence(beaconId: String) = 0L
            override fun importBeaconSamples(samples: List<BeaconSample>): Int { actions += "persist"; return samples.size }
            override fun close() {}
        }
        val transport = object : BeaconSyncTransport {
            override suspend fun historyInfo(device: BeaconDevice) = Result.success(BeaconHistoryInfo(1, 1, 1, 1, 1, 500))
            override suspend fun recordsAfter(device: BeaconDevice, sequence: Long, limit: Int): Result<BeaconSyncPage> =
                runCatching {
                    BeaconSyncPage(listOf(BeaconSample(device.id, 1,
                        BeaconHistoryClock.timestamp(row(boot = 6), page, receipt))), 1, false)
                }
            override suspend fun acknowledge(device: BeaconDevice, sequence: Long): Result<Unit> {
                actions += "ack"; return Result.success(Unit)
            }
        }
        val result = BeaconSyncManager(sink, cursor, transport, log = {}).synchronize(device)
        assertTrue(result.exceptionOrNull() is BeaconHistoryTimestampException)
        assertTrue(actions.isEmpty())
    }
}
