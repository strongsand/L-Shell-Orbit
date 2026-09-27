package com.hurricane.lshell

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hurricane.lshell.core.model.DishySnapshot
import com.hurricane.lshell.core.network.grpc.DishyGrpcClient
import com.hurricane.lshell.beacon.BeaconSample
import com.hurricane.lshell.beacon.BeaconSampleSource

data class HistoryEntry(
    val timestamp: Long,
    val kind: String,
    val label: String,
    val downloadMbps: Float = Float.NaN,
    val uploadMbps: Float = Float.NaN,
    val latencyMs: Float = Float.NaN,
    val dropPercent: Float = Float.NaN,
    val obstructionPercent: Float = Float.NaN,
    val uptimeSeconds: Long? = null,
    val ethernetMbps: Int? = null,
    val dishState: String? = null,
    val activeAlerts: String? = null,
    val powerWatts: Float? = null,
    val signal: Float? = null,
    val source: BeaconSampleSource = BeaconSampleSource.LOCAL_PHONE,
    val beaconId: String? = null,
    val beaconSequence: Long? = null
)

data class HistoryMinuteGroup(
    val minuteStartMillis: Long,
    val sampleCount: Int,
    val averageDownloadMbps: Float?,
    val minimumDownloadMbps: Float?,
    val maximumDownloadMbps: Float?,
    val averageUploadMbps: Float?,
    val minimumUploadMbps: Float?,
    val maximumUploadMbps: Float?,
    val averageLatencyMs: Float?,
    val minimumLatencyMs: Float?,
    val maximumLatencyMs: Float?,
    val averageDropPercent: Float?,
    val beaconSamples: Int,
    val localSamples: Int
) {
    val predominantSource: BeaconSampleSource
        get() = if (beaconSamples >= localSamples) BeaconSampleSource.BEACON else BeaconSampleSource.LOCAL_PHONE
    val hasMixedSources: Boolean get() = beaconSamples > 0 && localSamples > 0
}

/** One local row per background poll plus state/alert events; never stores identifiers or location. */
class HistoryStore(context: Context) : SQLiteOpenHelper(context, "dish_history.db", null, 6) {
    private var writesSinceTrim = 0
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, time_ms INTEGER NOT NULL, kind TEXT NOT NULL, label TEXT NOT NULL, download REAL, upload REAL, latency REAL, drop_rate REAL, obstruction REAL, source_counter INTEGER, source_boot INTEGER, uptime_seconds INTEGER, ethernet_mbps INTEGER, dish_state TEXT, active_alerts TEXT, power_watts REAL, signal REAL, source TEXT NOT NULL DEFAULT 'LOCAL_PHONE', beacon_id TEXT, beacon_sequence INTEGER)")
        db.execSQL("CREATE INDEX history_time ON history(time_ms)")
        db.execSQL("CREATE UNIQUE INDEX history_source ON history(source_boot, source_counter) WHERE kind = 'terminal_history'")
        db.execSQL("CREATE UNIQUE INDEX history_beacon_source ON history(beacon_id, beacon_sequence) WHERE source = 'BEACON'")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE history ADD COLUMN source_counter INTEGER")
            db.execSQL("ALTER TABLE history ADD COLUMN source_boot INTEGER")
            db.execSQL("CREATE UNIQUE INDEX history_source ON history(source_boot, source_counter) WHERE kind = 'terminal_history'")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE history ADD COLUMN uptime_seconds INTEGER")
            db.execSQL("ALTER TABLE history ADD COLUMN ethernet_mbps INTEGER")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE history ADD COLUMN dish_state TEXT")
            db.execSQL("ALTER TABLE history ADD COLUMN active_alerts TEXT")
            db.execSQL("ALTER TABLE history ADD COLUMN power_watts REAL")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE history ADD COLUMN source TEXT NOT NULL DEFAULT 'LOCAL_PHONE'")
            db.execSQL("ALTER TABLE history ADD COLUMN beacon_id TEXT")
            db.execSQL("ALTER TABLE history ADD COLUMN beacon_sequence INTEGER")
            db.execSQL("CREATE UNIQUE INDEX history_beacon_source ON history(beacon_id, beacon_sequence) WHERE source = 'BEACON'")
        }
        if (oldVersion < 6) db.execSQL("ALTER TABLE history ADD COLUMN signal REAL")
    }

    fun addReading(snapshot: DishySnapshot) = insert(HistoryEntry(
        timestamp = snapshot.timestamp,
        kind = "reading",
        label = snapshot.state.displayName,
        downloadMbps = snapshot.throughput.downlinkMbps,
        uploadMbps = snapshot.throughput.uplinkMbps,
        latencyMs = snapshot.latency.popPingLatencyMs,
        dropPercent = snapshot.latency.popPingDropRate * 100f,
        obstructionPercent = snapshot.obstruction.obstructionPercentage,
        uptimeSeconds = snapshot.deviceInfo.uptimeSeconds.takeIf { it > 0L },
        ethernetMbps = snapshot.deviceInfo.ethSpeedMbps,
        dishState = snapshot.state.name,
        activeAlerts = snapshot.alerts.getActiveAlertsList().joinToString("|").takeIf { it.isNotBlank() }
    ))

    fun addEvent(label: String, timestamp: Long = System.currentTimeMillis()) =
        insert(HistoryEntry(timestamp, "event", label))

    /**
     * Imports missing Beacon rows into the same history used by reports and charts. Sequence IDs
     * make retries idempotent; a nearby valid phone/terminal row wins to avoid double samples.
     */
    fun importBeaconSamples(samples: List<BeaconSample>): Int {
        if (samples.isEmpty()) return 0
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            samples.sortedBy { it.sequence }.forEach { sample ->
                if (sample.beaconId.isBlank() || sample.sequence < 0L || sample.timestampMillis <= 0L) return@forEach
                val finiteMetrics = listOfNotNull(sample.downloadMbps, sample.uploadMbps, sample.latencyMs,
                    sample.dropPercent, sample.obstructionPercent, sample.powerWatts, sample.signal).all { it.isFinite() }
                if (!finiteMetrics) return@forEach
                val overlap = db.rawQuery(
                    "SELECT 1 FROM history WHERE source = 'LOCAL_PHONE' AND kind IN ('reading','terminal_history') AND time_ms BETWEEN ? AND ? LIMIT 1",
                    arrayOf((sample.timestampMillis - 500L).toString(), (sample.timestampMillis + 500L).toString())
                ).use { it.moveToFirst() }
                if (overlap) return@forEach
                val values = ContentValues().apply {
                    put("time_ms", sample.timestampMillis)
                    put("kind", "beacon_history")
                    put("label", "Histórico do Beacon")
                    sample.downloadMbps?.let { put("download", it.coerceAtLeast(0f)) }
                    sample.uploadMbps?.let { put("upload", it.coerceAtLeast(0f)) }
                    sample.latencyMs?.let { put("latency", it.coerceAtLeast(0f)) }
                    sample.dropPercent?.let { put("drop_rate", it.coerceIn(0f, 100f)) }
                    sample.obstructionPercent?.let { put("obstruction", it.coerceIn(0f, 100f)) }
                    sample.powerWatts?.takeIf { it > 0f }?.let { put("power_watts", it) }
                    sample.signal?.let { put("signal", it) }
                    sample.dishCounter?.let { put("source_counter", it) }
                    sample.dishState?.let { put("dish_state", it) }
                    sample.activeAlerts.joinToString("|").takeIf { it.isNotBlank() }
                        ?.let { put("active_alerts", it) }
                    put("source", sample.source.name)
                    put("beacon_id", sample.beaconId)
                    put("beacon_sequence", sample.sequence)
                }
                if (db.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun latestBeaconSequence(beaconId: String): Long = readableDatabase.rawQuery(
        "SELECT MAX(beacon_sequence) FROM history WHERE source = 'BEACON' AND beacon_id = ?",
        arrayOf(beaconId)
    ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L }

    /** The terminal's 1 Hz circular buffer; `current` points past its newest sample. */
    fun importTerminalHistory(data: DishyGrpcClient.HistoryReading, afterCounter: Long, boot: Long): Int {
        val size = listOf(data.downloadMbps.size, data.uploadMbps.size,
            data.latencyMs.size, data.dropPercent.size).minOrNull() ?: 0
        if (size == 0 || data.current <= 0L) return 0
        val first = maxOf(0L, data.current - size, afterCounter + 1)
        val nowSecond = System.currentTimeMillis() / 1_000L
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            for (counter in first until data.current) {
                val index = (counter % size).toInt()
                val down = data.downloadMbps[index]
                val up = data.uploadMbps[index]
                val latency = data.latencyMs[index]
                val drop = data.dropPercent[index]
                val power = data.powerWatts.takeIf { it.isNotEmpty() }
                    ?.let { it[(counter % it.size).toInt()] }
                    ?.takeIf { it.isFinite() && it > 0f }
                if (!listOf(down, up, latency, drop).all { it.isFinite() }) continue
                val timestamp = (nowSecond - (data.current - 1L - counter)) * 1_000L
                val alreadyCoveredByBeacon = db.rawQuery(
                    "SELECT 1 FROM history WHERE source = 'BEACON' AND time_ms BETWEEN ? AND ? LIMIT 1",
                    arrayOf((timestamp - 500L).toString(), (timestamp + 500L).toString())
                ).use { it.moveToFirst() }
                if (alreadyCoveredByBeacon) continue
                val values = ContentValues().apply {
                    put("time_ms", timestamp)
                    put("kind", "terminal_history")
                    put("label", "Histórico da antena")
                    put("download", down.coerceAtLeast(0f))
                    put("upload", up.coerceAtLeast(0f))
                    put("latency", latency.coerceAtLeast(0f))
                    put("drop_rate", drop.coerceIn(0f, 100f))
                    put("obstruction", 0f)
                    put("source_counter", counter)
                    put("source_boot", boot)
                    power?.let { put("power_watts", it) }
                }
                if (db.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L)
                    inserted++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return inserted
    }

    private fun insert(entry: HistoryEntry) {
        val values = ContentValues().apply {
            put("time_ms", entry.timestamp)
            put("kind", entry.kind)
            put("label", entry.label)
            entry.downloadMbps.takeIf { it.isFinite() }?.let { put("download", it) }
            entry.uploadMbps.takeIf { it.isFinite() }?.let { put("upload", it) }
            entry.latencyMs.takeIf { it.isFinite() }?.let { put("latency", it) }
            entry.dropPercent.takeIf { it.isFinite() }?.let { put("drop_rate", it) }
            entry.obstructionPercent.takeIf { it.isFinite() }?.let { put("obstruction", it) }
            entry.uptimeSeconds?.let { put("uptime_seconds", it) }
            entry.ethernetMbps?.let { put("ethernet_mbps", it) }
            entry.dishState?.let { put("dish_state", it) }
            entry.activeAlerts?.let { put("active_alerts", it) }
            entry.powerWatts?.let { put("power_watts", it) }
            entry.signal?.let { put("signal", it) }
            put("source", entry.source.name)
            entry.beaconId?.let { put("beacon_id", it) }
            entry.beaconSequence?.let { put("beacon_sequence", it) }
        }
        writableDatabase.insert("history", null, values)
        if (++writesSinceTrim >= 100) {
            writesSinceTrim = 0
            // Keep one-second detail for one day, 10-second detail through seven days and
            // one-minute detail through 30 days. The terminal counter makes this deterministic.
            val now = System.currentTimeMillis()
            writableDatabase.execSQL(
                "DELETE FROM history WHERE kind = 'terminal_history' AND time_ms < ? AND source_counter % 60 != 0",
                arrayOf(now - 7L * 24 * 60 * 60 * 1000)
            )
            writableDatabase.execSQL(
                "DELETE FROM history WHERE kind = 'terminal_history' AND time_ms < ? AND source_counter % 10 != 0",
                arrayOf(now - 24L * 60 * 60 * 1000)
            )
            // Status polls carry state, alerts and obstruction that are absent from get_history.
            // Preserve them at one-minute resolution after the first day and five-minute
            // resolution after one week instead of removing that diagnostic context.
            writableDatabase.execSQL(
                "DELETE FROM history WHERE kind = 'reading' AND time_ms < ? AND id NOT IN " +
                    "(SELECT MAX(id) FROM history WHERE kind = 'reading' AND time_ms < ? GROUP BY time_ms / 300000)",
                arrayOf(now - 7L * 24 * 60 * 60 * 1000, now - 7L * 24 * 60 * 60 * 1000)
            )
            writableDatabase.execSQL(
                "DELETE FROM history WHERE kind = 'reading' AND time_ms < ? AND id NOT IN " +
                    "(SELECT MAX(id) FROM history WHERE kind = 'reading' AND time_ms < ? GROUP BY time_ms / 60000)",
                arrayOf(now - 24L * 60 * 60 * 1000, now - 24L * 60 * 60 * 1000)
            )
            writableDatabase.execSQL("DELETE FROM history WHERE time_ms < ?",
                arrayOf(now - 30L * 24 * 60 * 60 * 1000))
            writableDatabase.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT 500000)")
        }
    }

    fun recent(limit: Int = 300): List<HistoryEntry> {
        val entries = mutableListOf<HistoryEntry>()
        readableDatabase.query("history", null, null, null, null, null, "time_ms DESC, id DESC", limit.coerceIn(1, 10000).toString()).use { cursor ->
            entries += cursor.readHistoryEntries()
        }
        return entries
    }

    /** Compact, read-only projection used by the History timeline. Stored samples are untouched. */
    fun recentMinuteGroups(
        limit: Int = 180,
        source: BeaconSampleSource? = null
    ): List<HistoryMinuteGroup> {
        val sourceWhere = if (source == null) "" else " AND source = ?"
        val args = if (source == null) emptyArray() else arrayOf(source.name)
        val sql = """
            SELECT (time_ms / 60000) * 60000 AS minute_start,
                   COUNT(*) AS sample_count,
                   AVG(download), MIN(download), MAX(download),
                   AVG(upload), MIN(upload), MAX(upload),
                   AVG(latency), MIN(latency), MAX(latency),
                   AVG(drop_rate),
                   SUM(CASE WHEN source = 'BEACON' THEN 1 ELSE 0 END) AS beacon_count,
                   SUM(CASE WHEN source = 'LOCAL_PHONE' THEN 1 ELSE 0 END) AS local_count
              FROM history
             WHERE kind IN ('reading', 'terminal_history', 'beacon_history')$sourceWhere
             GROUP BY minute_start
             ORDER BY minute_start DESC
             LIMIT ${limit.coerceIn(1, 10000)}
        """.trimIndent()
        return readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(HistoryMinuteGroup(
                    minuteStartMillis = cursor.getLong(0),
                    sampleCount = cursor.getInt(1),
                    averageDownloadMbps = cursor.floatOrNull(2),
                    minimumDownloadMbps = cursor.floatOrNull(3),
                    maximumDownloadMbps = cursor.floatOrNull(4),
                    averageUploadMbps = cursor.floatOrNull(5),
                    minimumUploadMbps = cursor.floatOrNull(6),
                    maximumUploadMbps = cursor.floatOrNull(7),
                    averageLatencyMs = cursor.floatOrNull(8),
                    minimumLatencyMs = cursor.floatOrNull(9),
                    maximumLatencyMs = cursor.floatOrNull(10),
                    averageDropPercent = cursor.floatOrNull(11),
                    beaconSamples = cursor.getInt(12),
                    localSamples = cursor.getInt(13)
                ))
            }
        }
    }

    fun entriesInMinute(
        minuteStartMillis: Long,
        source: BeaconSampleSource? = null
    ): List<HistoryEntry> {
        val selection = buildString {
            append("time_ms >= ? AND time_ms < ? AND kind IN ('reading','terminal_history','beacon_history')")
            if (source != null) append(" AND source = ?")
        }
        val args = mutableListOf(
            minuteStartMillis.toString(),
            (minuteStartMillis + 60_000L).toString()
        ).apply { source?.let { add(it.name) } }.toTypedArray()
        return readableDatabase.query(
            "history", null, selection, args, null, null, "time_ms DESC, id DESC"
        ).use { it.readHistoryEntries() }
    }

    fun recentEvents(
        sinceMillis: Long = 0L,
        limit: Int = 180,
        source: BeaconSampleSource? = null
    ): List<HistoryEntry> {
        val selection = buildString {
            append("kind = 'event' AND time_ms >= ?")
            if (source != null) append(" AND source = ?")
        }
        val args = mutableListOf(sinceMillis.toString()).apply {
            source?.let { add(it.name) }
        }.toTypedArray()
        return readableDatabase.query(
            "history", null, selection, args, null, null, "time_ms DESC, id DESC",
            limit.coerceIn(1, 1000).toString()
        ).use { it.readHistoryEntries() }
    }

    fun between(start: Long, end: Long): List<HistoryEntry> {
        val entries = mutableListOf<HistoryEntry>()
        readableDatabase.query("history", null, "time_ms >= ? AND time_ms < ?",
            arrayOf(start.toString(), end.toString()), null, null, "time_ms ASC, id ASC").use { cursor ->
            entries += cursor.readHistoryEntries()
        }
        return entries
    }

    private fun android.database.Cursor.readHistoryEntries(): List<HistoryEntry> {
        val entries = mutableListOf<HistoryEntry>()
        run {
            val cursor = this
            val time = cursor.getColumnIndexOrThrow("time_ms")
            val kind = cursor.getColumnIndexOrThrow("kind")
            val label = cursor.getColumnIndexOrThrow("label")
            val download = cursor.getColumnIndexOrThrow("download")
            val upload = cursor.getColumnIndexOrThrow("upload")
            val latency = cursor.getColumnIndexOrThrow("latency")
            val drop = cursor.getColumnIndexOrThrow("drop_rate")
            val obstruction = cursor.getColumnIndexOrThrow("obstruction")
            val uptime = cursor.getColumnIndex("uptime_seconds")
            val ethernet = cursor.getColumnIndex("ethernet_mbps")
            val state = cursor.getColumnIndex("dish_state")
            val alerts = cursor.getColumnIndex("active_alerts")
            val power = cursor.getColumnIndex("power_watts")
            val signal = cursor.getColumnIndex("signal")
            val source = cursor.getColumnIndex("source")
            val beaconId = cursor.getColumnIndex("beacon_id")
            val beaconSequence = cursor.getColumnIndex("beacon_sequence")
            while (cursor.moveToNext()) entries += HistoryEntry(
                cursor.getLong(time), cursor.getString(kind), cursor.getString(label),
                cursor.metricOrMissing(download), cursor.metricOrMissing(upload), cursor.metricOrMissing(latency),
                cursor.metricOrMissing(drop), cursor.metricOrMissing(obstruction),
                if (uptime >= 0 && !cursor.isNull(uptime)) cursor.getLong(uptime) else null,
                if (ethernet >= 0 && !cursor.isNull(ethernet)) cursor.getInt(ethernet) else null,
                if (state >= 0 && !cursor.isNull(state)) cursor.getString(state) else null,
                if (alerts >= 0 && !cursor.isNull(alerts)) cursor.getString(alerts) else null,
                if (power >= 0 && !cursor.isNull(power)) cursor.getFloat(power) else null,
                if (signal >= 0 && !cursor.isNull(signal)) cursor.getFloat(signal) else null,
                if (source >= 0 && !cursor.isNull(source)) runCatching {
                    BeaconSampleSource.valueOf(cursor.getString(source))
                }.getOrDefault(BeaconSampleSource.LOCAL_PHONE) else BeaconSampleSource.LOCAL_PHONE,
                if (beaconId >= 0 && !cursor.isNull(beaconId)) cursor.getString(beaconId) else null,
                if (beaconSequence >= 0 && !cursor.isNull(beaconSequence)) cursor.getLong(beaconSequence) else null
            )
        }
        return entries
    }

    private fun android.database.Cursor.floatOrNull(index: Int): Float? =
        if (isNull(index)) null else getFloat(index).takeIf { it.isFinite() }

    private fun android.database.Cursor.metricOrMissing(index: Int): Float =
        storedMetricValue(isNull(index), if (isNull(index)) 0f else getFloat(index))
}

internal fun storedMetricValue(isNull: Boolean, value: Float): Float =
    if (isNull) Float.NaN else value
