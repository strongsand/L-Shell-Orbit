package io.github.strongsand.lshell

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

internal enum class TestOrigin { MANUAL, EXTREME, SCHEDULED, SMART, UNKNOWN }

/** Additive schema 6 -> 7. Telemetry identities, retention and ACK tables are untouched. */
internal object ObservabilitySchema {
    val statements = listOf(
        "CREATE TABLE IF NOT EXISTS connection_tests (test_id TEXT PRIMARY KEY, started_at INTEGER NOT NULL, payload TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS connection_tests_time ON connection_tests(started_at)",
        "CREATE TABLE IF NOT EXISTS observation_days (day_key TEXT PRIMARY KEY, start_ms INTEGER NOT NULL, payload TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS connection_reports (report_key TEXT PRIMARY KEY, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, kind TEXT NOT NULL, zone TEXT NOT NULL, grade TEXT NOT NULL, generated_ms INTEGER NOT NULL, payload TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS connection_reports_time ON connection_reports(start_ms)"
    )
}

internal data class RecordedTest(
    val id: String, val origin: TestOrigin, val start: Long, val end: Long,
    val outcome: String, val downloads: List<Double>, val uploads: List<Double>, val latencies: List<Double>
) {
    fun json() = JSONObject().apply {
        put("id", id); put("origin", origin.name); put("start", start); put("end", end)
        put("outcome", outcome); put("download", JSONArray(downloads)); put("upload", JSONArray(uploads))
        put("latency", JSONArray(latencies)); put("schema", 1)
    }
    companion object {
        fun from(state: ConnectionTestState): RecordedTest? {
            if (state.id <= 0 || state.active || state.phase == ConnectionTestPhase.IDLE) return null
            fun valid(n: Float?) = n?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
            val runs = state.runs.filter { it.failure == null }
            val metrics = ConnectionTestPresentation.metrics(state)
            return RecordedTest(state.id.toString(), state.origin, state.startedAt, state.endedAt,
                state.phase.name,
                if (state.kind == ConnectionTestKind.EXTREME) runs.filter { it.downloadMeasured == true || (it.downloadMeasured == null && it.downloadMbps > 0) }.mapNotNull { valid(it.downloadMbps) } else listOfNotNull(valid(metrics.download)),
                if (state.kind == ConnectionTestKind.EXTREME) runs.filter { it.uploadMeasured == true || (it.uploadMeasured == null && it.uploadMbps > 0) }.mapNotNull { valid(it.uploadMbps) } else listOfNotNull(valid(metrics.upload)),
                if (state.kind == ConnectionTestKind.EXTREME) runs.mapNotNull { valid(it.latencyMs) } else listOfNotNull(valid(metrics.latency)))
        }
        fun parse(j: JSONObject): RecordedTest {
            fun values(key: String) = j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getDouble(it) } }
            return RecordedTest(j.getString("id"), TestOrigin.valueOf(j.getString("origin")), j.getLong("start"),
                j.getLong("end"), j.getString("outcome"), values("download"), values("upload"), values("latency"))
        }
    }
}

/** Compact results, not a second telemetry history. Repeated recovery writes are idempotent. */
internal object TestHistory {
    fun importLegacy(context: Context) {
        val prefs = context.getSharedPreferences("connection_health_reports", Context.MODE_PRIVATE)
        if (prefs.getBoolean("legacy_extreme_imported", false)) return
        ExtremeSessionStore(context).all().forEach { session ->
            save(context, ConnectionTestState(id = session.id, kind = ConnectionTestKind.EXTREME,
                phase = if (session.runs.size == session.requestedTests) ConnectionTestPhase.COMPLETED else ConnectionTestPhase.INTERRUPTED,
                startedAt = session.startedAt, endedAt = session.endedAt,
                total = session.requestedTests, completed = session.runs.size,
                runs = session.runs, origin = TestOrigin.EXTREME))
        }
        check(prefs.edit().putBoolean("legacy_extreme_imported", true).commit())
    }
    fun save(context: Context, state: ConnectionTestState) {
        val test = RecordedTest.from(state) ?: return
        HistoryStore(context).use { store ->
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                db.insertWithOnConflict("connection_tests", null, ContentValues().apply {
                    put("test_id", test.id); put("started_at", test.start)
                    put("payload", test.json().put("detail", historicalTestDetail(state)).toString())
                }, SQLiteDatabase.CONFLICT_REPLACE)
                // One year of compact test results; report snapshots have independent retention.
                db.delete("connection_tests", "started_at < ?", arrayOf((System.currentTimeMillis() - 366L * 86_400_000).toString()))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
    fun between(store: HistoryStore, start: Long, end: Long): List<RecordedTest> =
        store.readableDatabase.rawQuery("SELECT payload FROM connection_tests WHERE started_at >= ? AND started_at < ? ORDER BY started_at, test_id",
            arrayOf(start.toString(), end.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(RecordedTest.parse(JSONObject(cursor.getString(0)))) }
        }
}
