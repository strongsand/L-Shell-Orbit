package io.github.strongsand.lshell.beacon

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class BeaconSyncTest {
    private val device = BeaconDevice("synthetic", "Beacon", "example.local", 80, protocolVersion = 1)
    private val actions = mutableListOf<String>()
    private var durable = 0L
    private var cursor = 0L
    private var failAck = false
    private var failPersist = false
    private val preferences = object : BeaconSyncCursor {
        override fun configuredBeacon() = device
        override fun pairedBeacon(): PairedBeacon? = null
        override fun lastSyncedSequence() = cursor
        override fun setLastSyncedSequence(sequence: Long) { actions += "cursor"; cursor = sequence }
    }
    private val sink = object : BeaconHistorySink {
        override fun latestBeaconSequence(beaconId: String) = durable
        override fun importBeaconSamples(samples: List<BeaconSample>): Int {
            if (failPersist) error("synthetic storage failure")
            actions += "persist"
            durable = samples.lastOrNull()?.sequence ?: durable
            return samples.size
        }
        override fun close() {}
    }
    private fun manager(sample: BeaconSample = BeaconSample(device.id, 1L, 1234L, downloadMbps = 0f), highest: Long = 1L) =
        BeaconSyncManager(sink, preferences, object : BeaconSyncTransport {
            override suspend fun historyInfo(device: BeaconDevice) =
                Result.success(BeaconHistoryInfo(1, 1, 1, 1, 1, 10))
            override suspend fun recordsAfter(device: BeaconDevice, sequence: Long, limit: Int) =
                Result.success(BeaconSyncPage(if (sequence < 1) listOf(sample) else emptyList(), highest, false))
            override suspend fun acknowledge(device: BeaconDevice, sequence: Long): Result<Unit> {
                actions += "ack"
                return if (failAck) Result.failure(IllegalStateException("synthetic disconnect")) else Result.success(Unit)
            }
        }, log = {})

    @Test fun persistencePrecedesAckAndCursor() = runBlocking {
        assertTrue(manager().synchronize(device).isSuccess)
        assertEquals(listOf("persist", "ack", "cursor"), actions)
        assertEquals(1L, cursor)
    }
    @Test fun pageFeedbackOccursOnlyAfterDurableAckAndCursor() = runBlocking {
        val counts = mutableListOf<Int>()
        assertTrue(manager().synchronize(device, onPageAcknowledged = {
            counts += it
            assertEquals(listOf("persist", "ack", "cursor"), actions)
        }).isSuccess)
        assertEquals(listOf(1), counts)
    }
    @Test fun failedAckNeverClaimsCompletedPage() = runBlocking {
        failAck = true
        val counts = mutableListOf<Int>()
        assertTrue(manager().synchronize(device, onPageAcknowledged = { counts += it }).isFailure)
        assertTrue(counts.isEmpty())
    }
    @Test fun firstPageAckIsNotCompletionAndLaterPageCanStillFail() = runBlocking {
        var finalResult: Result<BeaconSyncResult>? = null
        val feedback = mutableListOf<Int>()
        val sync = BeaconSyncManager(sink, preferences, object : BeaconSyncTransport {
            override suspend fun historyInfo(device: BeaconDevice) =
                Result.success(BeaconHistoryInfo(1, 1, 1, 2, 2, 10))
            override suspend fun recordsAfter(device: BeaconDevice, sequence: Long, limit: Int): Result<BeaconSyncPage> {
                if (sequence == 1L) return Result.failure(IllegalStateException("synthetic second-page failure"))
                return Result.success(BeaconSyncPage(listOf(BeaconSample(device.id, 1, 1234)), 1, true))
            }
            override suspend fun acknowledge(device: BeaconDevice, sequence: Long) = Result.success(Unit)
        }, log = {})
        finalResult = sync.synchronize(device, onPageAcknowledged = {
            feedback += it
            assertNull(finalResult)
            assertEquals(1L, cursor)
        })
        assertEquals(listOf(1), feedback)
        assertTrue(finalResult!!.isFailure)
        assertEquals(1L, durable)
        assertEquals(1L, cursor)
    }
    @Test fun storageFailureNeverAcknowledges() = runBlocking {
        failPersist = true
        assertTrue(manager().synchronize(device).isFailure)
        assertTrue(actions.isEmpty())
        assertEquals(0L, cursor)
    }
    @Test fun lostAckRetriesDurablePageWithoutReimport() = runBlocking {
        failAck = true
        assertTrue(manager().synchronize(device).isFailure)
        assertEquals(1L, durable)
        assertEquals(0L, cursor)
        failAck = false
        actions.clear()
        assertTrue(manager().synchronize(device).isSuccess)
        assertEquals(listOf("ack", "cursor", "persist"), actions)
    }
    @Test fun invalidMetricNeverPersistsOrAcknowledges() = runBlocking {
        assertTrue(manager(BeaconSample(device.id, 1, 1234, powerWatts = Float.NaN)).synchronize(device).isFailure)
        assertTrue(actions.isEmpty())
    }
    @Test fun periodicIntervalsRespectAndroidMinimum() {
        assertTrue(BeaconSyncSchedule.intervals.all { it >= 15 })
        assertEquals(30L, BeaconSyncSchedule.normalizeInterval(1))
        assertEquals(180L, BeaconSyncSchedule.normalizeInterval(180))
    }
    @Test fun invalidPageCursorDoesNotReachPersistence() = runBlocking {
        assertTrue(manager(highest = 2L).synchronize(device).isFailure)
        assertTrue(actions.isEmpty())
    }
    @Test fun absentMetricsDoNotPreventAnOtherwiseValidImport() = runBlocking {
        assertTrue(manager(BeaconSample(device.id, 1L, 1234L)).synchronize(device).isSuccess)
        assertEquals(listOf("persist", "ack", "cursor"), actions)
    }
    @Test fun foregroundAndWorkerCallsNeverFetchPagesConcurrently() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val transport = object : BeaconSyncTransport {
            override suspend fun historyInfo(device: BeaconDevice) = Result.success(BeaconHistoryInfo(1, 1, 0, 0, 0, 10))
            override suspend fun recordsAfter(device: BeaconDevice, sequence: Long, limit: Int): Result<BeaconSyncPage> {
                maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
                try { delay(30); return Result.success(BeaconSyncPage(emptyList(), 0, false)) }
                finally { active.decrementAndGet() }
            }
            override suspend fun acknowledge(device: BeaconDevice, sequence: Long) = Result.success(Unit)
        }
        val first = BeaconSyncManager(sink, preferences, transport, log = {})
        val second = BeaconSyncManager(sink, preferences, transport, log = {})
        val a = async { first.synchronize(device) }
        val b = async { second.synchronize(device) }
        assertTrue(a.await().isSuccess)
        assertTrue(b.await().isSuccess)
        assertEquals(1, maximum.get())
        assertEquals(0, active.get())
    }
}
