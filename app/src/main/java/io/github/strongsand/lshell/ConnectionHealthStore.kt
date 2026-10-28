package io.github.strongsand.lshell

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

internal object ConnectionHealthStore {
    private val mutex = Mutex()
    val revision = MutableStateFlow(0L)
    /** Only complete periods may be installed. SQL identity prevents cross-instance duplicates. */
    fun install(db: SQLiteDatabase, report: HealthReport): Boolean {
        require(report.closed && report.through == report.period.end)
        return db.insertWithOnConflict("connection_reports", null, ContentValues().apply {
            put("report_key", report.period.key); put("start_ms", report.period.start); put("end_ms", report.period.end)
            put("kind", report.period.kind.name); put("zone", report.period.zone); put("grade", report.grade.name)
            put("generated_ms", report.generatedAt)
            put("payload", ConnectionHealthJson.report(report).toString())
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun all(context: Context): List<HealthReport> = HistoryStore(context).use { store ->
        store.readableDatabase.rawQuery("SELECT payload FROM connection_reports ORDER BY start_ms DESC LIMIT 12", null).use { c ->
            buildList { while (c.moveToNext()) add(ConnectionHealthJson.report(JSONObject(c.getString(0)))) }
        }
    }
    fun get(context: Context, key: String): HealthReport? = HistoryStore(context).use { store ->
        store.readableDatabase.rawQuery("SELECT payload FROM connection_reports WHERE report_key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) ConnectionHealthJson.report(JSONObject(c.getString(0))) else null
        }
    }
    fun list(context: Context, limit: Int = 50): List<HealthReportListing> = HistoryStore(context).use { store ->
        store.readableDatabase.rawQuery("SELECT report_key, kind, start_ms, end_ms, zone, grade, generated_ms, payload FROM connection_reports ORDER BY start_ms DESC LIMIT ?",
            arrayOf(limit.coerceAtLeast(1).toString())).use { c -> buildList {
            while (c.moveToNext()) {
                val (coverage, stability) = HealthReportUiPolicy.listingSummary(JSONObject(c.getString(7)))
                add(HealthReportListing(c.getString(0), HealthPeriod(HealthPeriodKind.valueOf(c.getString(1)),
                    c.getLong(2), c.getLong(3), c.getString(4)), HealthGrade.valueOf(c.getString(5)), c.getLong(6), coverage, stability))
            }
        } }
    }
    private fun day(store: HistoryStore, start: Long, end: Long, zone: ZoneId, closed: Boolean): HealthDay {
        val db = store.writableDatabase
        val key = "$start:${zone.id}"
        val saved = db.rawQuery("SELECT payload FROM observation_days WHERE day_key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) JSONObject(c.getString(0)) else null
        }
        val cached = saved?.getJSONObject("day")?.let(ConnectionHealthJson::day)
        // Read rows and results from one SQLite snapshot. CPU aggregation happens after releasing it.
        lateinit var rows: List<HistoryEntry>; lateinit var tests: List<RecordedTest>; lateinit var signature: String
        db.beginTransactionNonExclusive()
        try {
            val historySignature = db.rawQuery("SELECT COUNT(*), COALESCE(MAX(id),0) FROM history WHERE time_ms >= ? AND time_ms < ?",
                arrayOf(start.toString(), end.toString())).use { c -> c.moveToFirst(); "${c.getLong(0)}:${c.getLong(1)}" }
            tests = TestHistory.between(store, start, end)
            val testDigest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(tests.joinToString("|") { it.json().toString() }.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            signature = "$historySignature:$testDigest"
            if (closed && saved?.optString("signature") == signature && cached?.end == end && cached.hasPreservedData) return cached
            rows = store.between(start - 5_000, end)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val computed = ConnectionHealthAnalyzer.day(rows, tests, start, end, zone)
        // Raw compaction cannot erase previously captured coverage/distribution. Test ledger stays fresh.
        val result = if (cached != null && cached.end <= end) {
            val prefix = if (cached.end == end) computed else ConnectionHealthAnalyzer.day(
                rows.filter { it.timestamp < cached.end }, tests, start, cached.end, zone)
            val tail = if (cached.end < end) ConnectionHealthAnalyzer.day(
                rows.filter { it.timestamp >= cached.end - 5_000 }, tests, cached.end, end, zone) else null
            ConnectionHealthAnalyzer.retainPrefix(cached, prefix, tail, computed)
        } else computed
        // Cache current-day prefixes too, so ordinary next-day compaction cannot erase them.
        if (cached == null || end >= cached.end) {
            if (result.hasPreservedData) db.insertWithOnConflict("observation_days", null, ContentValues().apply {
                put("day_key", key); put("start_ms", start)
                put("payload", JSONObject().put("signature", signature).put("day", ConnectionHealthJson.day(result)).toString())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            else if (cached != null) db.delete("observation_days", "day_key = ?", arrayOf(key))
        }
        // Empty calendar days still participate in report duration, coverage and gaps.
        return result
    }
    private fun prepare(store: HistoryStore, period: HealthPeriod, now: Long, history: List<HealthReport>): HealthReport {
        val days = period.days(now).map { (a, b) ->
            val midnight = Instant.ofEpochMilli(a).atZone(ZoneId.of(period.zone)).toLocalDate().plusDays(1)
                .atStartOfDay(ZoneId.of(period.zone)).toInstant().toEpochMilli()
            day(store, a, b, ZoneId.of(period.zone), b == midnight && b <= now)
        }
        val previous = history.firstOrNull { it.period == period.previous() }
            ?: store.readableDatabase.rawQuery("SELECT payload FROM connection_reports WHERE report_key = ?", arrayOf(period.previous().key)).use { c ->
                if (c.moveToFirst()) ConnectionHealthJson.report(JSONObject(c.getString(0))) else null
            }
        return ConnectionHealthAnalyzer.report(period, days, previous, now, history)
    }
    suspend fun refresh(context: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) =
        withContext(Dispatchers.IO) { mutex.withLock {
            if (!ConnectionHealthSchedule.enabled(context)) return@withLock
            TestHistory.importLegacy(context)
            HistoryStore(context).use { store ->
                val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                // Capture closed days before 30-day retention; at most 31 bounded day queries per run.
                for (offset in 30 downTo 1) {
                    val date = today.minusDays(offset.toLong())
                    day(store, date.atStartOfDay(zone).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), zone, true)
                }
                day(store, today.atStartOfDay(zone).toInstant().toEpochMilli(), now, zone, false)
                val history = all(context).toMutableList()
                for (kind in HealthPeriodKind.entries) {
                    val current = HealthPeriod.containing(kind, now, zone)
                    var period = HealthPeriod.containing(kind, today.minusDays(30).atStartOfDay(zone).toInstant().toEpochMilli(), zone)
                    while (period.end <= current.start) {
                        if (history.none { it.period == period }) {
                            val report = prepare(store, period, now, history)
                            if (report.total.observedMs > 0 || report.total.tests.isNotEmpty()) {
                                if (install(store.writableDatabase, report)) history.add(report)
                            }
                        }
                        period = HealthPeriod.containing(kind, period.end, zone)
                    }
                }
                // Compact bounded caches; immutable reports retain their own daily aggregates.
                store.writableDatabase.delete("observation_days", "start_ms < ?", arrayOf((now - 400L * 86_400_000).toString()))
            }
            revision.value += 1
        } }
    suspend fun current(context: Context, kind: HealthPeriodKind, now: Long = System.currentTimeMillis()): HealthReport =
        withContext(Dispatchers.IO) { mutex.withLock {
            HistoryStore(context).use { prepare(it, HealthPeriod.containing(kind, now, ZoneId.systemDefault()), now, all(context)) }
        } }
}
