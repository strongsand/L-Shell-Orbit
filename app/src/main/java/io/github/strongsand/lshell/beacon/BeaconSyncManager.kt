package io.github.strongsand.lshell.beacon

import io.github.strongsand.lshell.HistoryStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BeaconHistoryInfo(
    val protocolVersion: Int,
    val recordVersion: Int,
    val oldestSequence: Long,
    val newestSequence: Long,
    val recordCount: Long,
    val capacity: Long
)

data class BeaconSyncPage(
    val records: List<BeaconSample>,
    val highestSequence: Long,
    val hasMore: Boolean
)

interface BeaconSyncTransport {
    suspend fun historyInfo(device: BeaconDevice): Result<BeaconHistoryInfo>
    suspend fun recordsAfter(device: BeaconDevice, sequence: Long, limit: Int): Result<BeaconSyncPage>
    suspend fun acknowledge(device: BeaconDevice, sequence: Long): Result<Unit>
}

data class BeaconSyncResult(val imported: Int, val highestSequence: Long)

/** Incremental, idempotent synchronization. No network protocol is assumed here. */
class BeaconSyncManager(
    private val historyStore: HistoryStore,
    private val preferences: BeaconPreferences,
    private val transport: BeaconSyncTransport,
    private val onHistoryPersisted: (start: Long, endExclusive: Long) -> Unit = { _, _ -> }
) : AutoCloseable {
    suspend fun synchronize(
        device: BeaconDevice,
        onProgress: (processed: Long, total: Long) -> Unit = { _, _ -> }
    ): Result<BeaconSyncResult> = withContext(Dispatchers.IO) { runCatching {
        val info = transport.historyInfo(device).getOrThrow()
        Log.i(TAG, "BEACON_HISTORY_INFO")
        require(info.protocolVersion == 1) { "Versão do protocolo Beacon incompatível." }
        require(info.recordVersion == 1) { "Versão de registro do Beacon incompatível." }
        val acknowledgedCursor = preferences.lastSyncedSequence().coerceAtLeast(0L)
        val persistedCursor = historyStore.latestBeaconSequence(device.id).coerceAtLeast(0L)
        var cursor = maxOf(acknowledgedCursor, persistedCursor)
        val initialCursor = cursor
        val totalPending = (info.newestSequence - cursor).coerceAtLeast(0L)
        Log.i(TAG, "SYNC_START pending=$totalPending")
        onProgress(0L, totalPending)
        require(info.capacity > 0L && info.recordCount in 0L..info.capacity) {
            "Metadados de armazenamento inválidos no Beacon."
        }
        require(info.recordCount == 0L ||
            (info.oldestSequence > 0L && info.newestSequence >= info.oldestSequence &&
                info.newestSequence - info.oldestSequence + 1L == info.recordCount)) {
            "Faixa de histórico inválida no Beacon."
        }
        require(cursor == 0L || info.newestSequence >= cursor) {
            "A sequência do Beacon foi reiniciada. Reconfigure o dispositivo antes de sincronizar."
        }
        // A previous attempt may have persisted a page and lost the connection before its ACK.
        // Re-acknowledge the durable cursor before requesting anything newer.
        if (persistedCursor > acknowledgedCursor) {
            transport.acknowledge(device, persistedCursor).getOrThrow()
            Log.i(TAG, "SYNC_ACK_OK")
            preferences.setLastSyncedSequence(persistedCursor)
        }
        var imported = 0
        do {
            Log.i(TAG, "SYNC_PAGE_REQUEST after=$cursor")
            val page = transport.recordsAfter(device, cursor, PAGE_SIZE).getOrThrow()
            Log.i(TAG, "SYNC_PAGE_OK count=${page.records.size}")
            require(page.records.all { sample ->
                sample.beaconId == device.id && sample.sequence > cursor && sample.timestampMillis > 0L &&
                    listOfNotNull(sample.downloadMbps, sample.uploadMbps, sample.latencyMs,
                        sample.dropPercent, sample.obstructionPercent, sample.powerWatts, sample.signal).all { it.isFinite() }
            }) { "O Beacon retornou um registro inválido ou de outro dispositivo." }
            val ordered = page.records.distinctBy { it.sequence }.sortedBy { it.sequence }
            require(ordered.size == page.records.size) { "O Beacon retornou sequências duplicadas na mesma página." }
            require(ordered.zipWithNext().all { (a, b) -> b.sequence > a.sequence }) {
                "A sequência do Beacon não é monotônica."
            }
            val pageImported = historyStore.importBeaconSamples(ordered)
            imported += pageImported
            if (pageImported > 0 && ordered.isNotEmpty()) {
                val firstTimestamp = ordered.minOf { it.timestampMillis }
                val lastTimestamp = ordered.maxOf { it.timestampMillis }
                runCatching { onHistoryPersisted(firstTimestamp, lastTimestamp + 1L) }
                    .onFailure { Log.w(TAG, "REPORT_STALE_MARK_FAILED=${it.javaClass.simpleName}") }
            }
            Log.i(TAG, "SYNC_PERSIST_OK")
            val confirmed = ordered.lastOrNull()?.sequence ?: cursor
            require(page.highestSequence == confirmed) { "O cursor informado pelo Beacon não corresponde aos registros recebidos." }
            if (confirmed > cursor) {
                transport.acknowledge(device, confirmed).getOrThrow()
                Log.i(TAG, "SYNC_ACK_OK")
                preferences.setLastSyncedSequence(confirmed)
                cursor = confirmed
                onProgress((cursor - initialCursor).coerceAtMost(totalPending), totalPending)
            }
            if (page.hasMore && ordered.isEmpty()) error("O Beacon informou mais dados sem avançar a sequência.")
        } while (page.hasMore)
        Log.i(TAG, "SYNC_COMPLETE")
        BeaconSyncResult(imported, cursor)
    }.onFailure { Log.e(TAG, "SYNC_FAILED=${it.javaClass.simpleName}") } }

    private companion object { const val PAGE_SIZE = 500; const val TAG = "L-ShellBeaconSync" }

    override fun close() = historyStore.close()
}
