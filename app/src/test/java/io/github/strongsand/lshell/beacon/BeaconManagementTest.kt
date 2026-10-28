package io.github.strongsand.lshell.beacon

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BeaconManagementTest {
    private val device = BeaconDevice("a".repeat(32), "Beacon", "example.local", 80, protocolVersion = 1)
    private fun challenge(id: String = device.id) = JSONObject().put("beacon_id", id).put("nonce", "b".repeat(32))
    private fun preview(ack: Long = 10, through: Long = 15, count: Long = 5) = JSONObject()
        .put("from_ack", ack).put("through_sequence", through).put("record_count", count)

    @Test fun challengeIsBoundToExactBeaconLineage() {
        assertEquals("b".repeat(32), BeaconManagementProtocol.challenge(challenge(), device))
        assertThrows(IllegalArgumentException::class.java) {
            BeaconManagementProtocol.challenge(challenge("c".repeat(32)), device)
        }
    }
    @Test fun malformedNonceRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BeaconManagementProtocol.challenge(challenge().put("nonce", "wrong"), device)
        }
    }
    @Test fun previewOnlyDescribesAnUnrecoverablePendingPrefix() {
        assertEquals(BeaconDiscardPreview(10, 15, 5), BeaconManagementProtocol.discardPreview(preview()))
    }
    @Test fun emptyOrBackwardDiscardIsRejected() {
        for (p in listOf(preview(count = 0), preview(through = 10), preview(through = 9), preview(ack = -1))) {
            assertThrows(IllegalArgumentException::class.java) { BeaconManagementProtocol.discardPreview(p) }
        }
    }
    @Test fun countCannotExceedTheSequenceRange() {
        assertThrows(IllegalArgumentException::class.java) { BeaconManagementProtocol.discardPreview(preview(count = 6)) }
    }
    @Test fun changedPreviewMustBeConfirmedAgain() {
        val original = BeaconManagementProtocol.discardPreview(preview())
        BeaconManagementProtocol.verifyDiscard(original, original)
        assertThrows(IllegalArgumentException::class.java) {
            BeaconManagementProtocol.verifyDiscard(original, BeaconManagementProtocol.discardPreview(preview(through = 16)))
        }
    }
    @Test fun discardedAllPendingDoesNotLookLikeSequenceReset() = emptyRing(next = 16, ack = 15, succeeds = true)
    @Test fun oldFirmwareDrainedRingUsesDurableAck() = emptyRing(next = null, ack = 15, succeeds = true)
    @Test fun realOldIdentitySequenceResetStillFails() = emptyRing(next = 1, ack = 0, succeeds = false)

    private fun emptyRing(next: Long?, ack: Long, succeeds: Boolean) = runBlocking {
        var cursor = 15L
        val actions = mutableListOf<String>()
        val preferences = object : BeaconSyncCursor {
            override fun configuredBeacon() = device
            override fun pairedBeacon(): PairedBeacon? = null
            override fun lastSyncedSequence() = cursor
            override fun setLastSyncedSequence(sequence: Long) { cursor = sequence; actions += "cursor" }
        }
        val sink = object : BeaconHistorySink {
            override fun latestBeaconSequence(beaconId: String) = 10L
            override fun importBeaconSamples(samples: List<BeaconSample>): Int {
                assertTrue(samples.isEmpty()); return 0
            }
            override fun close() {}
        }
        val transport = object : BeaconSyncTransport {
            override suspend fun historyInfo(device: BeaconDevice) = Result.success(BeaconHistoryInfo(1, 1, 0, 0, 0, 500, next, ack))
            override suspend fun recordsAfter(device: BeaconDevice, sequence: Long, limit: Int) = Result.success(BeaconSyncPage(emptyList(), sequence, false))
            override suspend fun acknowledge(device: BeaconDevice, sequence: Long): Result<Unit> { actions += "ack"; return Result.success(Unit) }
        }
        assertEquals(succeeds, BeaconSyncManager(sink, preferences, transport, log = {}).synchronize(device).isSuccess)
        assertEquals(15L, cursor); assertTrue(actions.isEmpty())
    }

    @Test fun hostAnchorPreparationPrecedesEveryHistoryOperation() = runBlocking {
        val actions = mutableListOf<String>()
        val prefs = object : BeaconSyncCursor {
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
            override suspend fun prepareHistoryClock(device: BeaconDevice): Result<Unit> { actions += "anchor"; return Result.success(Unit) }
            override suspend fun historyInfo(device: BeaconDevice): Result<BeaconHistoryInfo> { actions += "info"; return Result.success(BeaconHistoryInfo(1, 1, 1, 1, 1, 500)) }
            override suspend fun recordsAfter(device: BeaconDevice, sequence: Long, limit: Int): Result<BeaconSyncPage> {
                actions += "page"; return Result.success(BeaconSyncPage(listOf(BeaconSample(device.id, 1, 1800000000000)), 1, false))
            }
            override suspend fun acknowledge(device: BeaconDevice, sequence: Long): Result<Unit> { actions += "ack"; return Result.success(Unit) }
        }
        assertTrue(BeaconSyncManager(sink, prefs, transport, log = {}).synchronize(device).isSuccess)
        assertEquals(listOf("anchor", "info", "page", "persist", "ack", "cursor"), actions)
    }

    @Test fun resetLineageCanRestartAtOneWithoutDeletingPreviousPhoneHistory() = runBlocking {
        val oldId = "c".repeat(32)
        val rows = mutableMapOf((oldId to 1L) to BeaconSample(oldId, 1, 1799999900000))
        var cursor = 0L
        val prefs = object : BeaconSyncCursor {
            override fun configuredBeacon() = device
            override fun pairedBeacon(): PairedBeacon? = null
            override fun lastSyncedSequence() = cursor
            override fun setLastSyncedSequence(sequence: Long) { cursor = sequence }
        }
        val sink = object : BeaconHistorySink {
            override fun latestBeaconSequence(beaconId: String) = rows.keys.filter { it.first == beaconId }.maxOfOrNull { it.second } ?: 0L
            override fun importBeaconSamples(samples: List<BeaconSample>): Int {
                var imported = 0
                samples.forEach { if (rows.putIfAbsent(it.beaconId to it.sequence, it) == null) imported++ }
                return imported
            }
            override fun close() {}
        }
        val transport = object : BeaconSyncTransport {
            override suspend fun historyInfo(device: BeaconDevice) = Result.success(BeaconHistoryInfo(1, 1, 1, 1, 1, 500))
            override suspend fun recordsAfter(device: BeaconDevice, sequence: Long, limit: Int) =
                Result.success(BeaconSyncPage(if (sequence == 0L) listOf(BeaconSample(device.id, 1, 1800000000000)) else emptyList(), maxOf(1, sequence), false))
            override suspend fun acknowledge(device: BeaconDevice, sequence: Long) = Result.success(Unit)
        }
        val manager = BeaconSyncManager(sink, prefs, transport, log = {})
        assertEquals(1, manager.synchronize(device).getOrThrow().imported)
        assertEquals(0, manager.synchronize(device).getOrThrow().imported)
        assertEquals(2, rows.size); assertTrue(rows.containsKey(oldId to 1L)); assertTrue(rows.containsKey(device.id to 1L))
    }

    @Test fun validPrefixPersistsButUnrecoverableNextPageNeverAdvancesAck() = runBlocking {
        var cursor = 0L; var durable = 0L
        val actions = mutableListOf<String>()
        val prefs = object : BeaconSyncCursor {
            override fun configuredBeacon() = device
            override fun pairedBeacon(): PairedBeacon? = null
            override fun lastSyncedSequence() = cursor
            override fun setLastSyncedSequence(sequence: Long) { cursor = sequence; actions += "cursor:$sequence" }
        }
        val sink = object : BeaconHistorySink {
            override fun latestBeaconSequence(beaconId: String) = durable
            override fun importBeaconSamples(samples: List<BeaconSample>): Int {
                durable = samples.last().sequence; actions += "persist:$durable"; return samples.size
            }
            override fun close() {}
        }
        val transport = object : BeaconSyncTransport {
            override suspend fun historyInfo(device: BeaconDevice) = Result.success(BeaconHistoryInfo(1, 1, 1, 3, 3, 500))
            override suspend fun recordsAfter(device: BeaconDevice, sequence: Long, limit: Int): Result<BeaconSyncPage> =
                if (sequence == 0L) Result.success(BeaconSyncPage(listOf(BeaconSample(device.id, 1, 1800000000000)), 1, true))
                else Result.failure(BeaconHistoryTimestampException("historical_time_unrecoverable"))
            override suspend fun acknowledge(device: BeaconDevice, sequence: Long): Result<Unit> {
                actions += "ack:$sequence"; return Result.success(Unit)
            }
        }
        assertTrue(BeaconSyncManager(sink, prefs, transport, log = {}).synchronize(device).isFailure)
        assertEquals(listOf("persist:1", "ack:1", "cursor:1"), actions)
        assertEquals(1L, cursor); assertEquals(1L, durable)
    }
}
