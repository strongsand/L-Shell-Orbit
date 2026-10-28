package io.github.strongsand.lshell

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Explicit logical projection. Neither SQLite IDs nor files from the database directory leave the app. */
internal class BackupDatabaseAdapter(private val db: SQLiteDatabase) {
    fun export(writer: BackupWriter, lastTest: JSONObject?, restoredDetail: (String) -> JSONObject? = { null },
        fallbackTests: ((JSONObject) -> Unit) -> Unit = {}, progress: (BackupCategory) -> Unit = {}) {
        db.beginTransactionNonExclusive()
        try {
            listOf(BackupCategory.READINGS, BackupCategory.EVENTS).forEach { category -> writer.records(category) { emit ->
                progress(category)
                history(category).use { c -> while (c.moveToNext()) emit(BackupRecordCodec.wrap(category, c.historyData())) }
            } }
            writer.records(BackupCategory.TESTS) { emit ->
                progress(BackupCategory.TESTS)
                var foundLast = false
                db.rawQuery("SELECT payload FROM connection_tests ORDER BY started_at, test_id", null).use { c ->
                    while (c.moveToNext()) {
                        val data = JSONObject(c.getString(0))
                        if (lastTest?.getString("id") == data.getString("id")) {
                            if (!data.has("detail")) data.put("detail", lastTest.getJSONObject("detail"))
                            foundLast = true
                        }
                        if (!data.has("detail")) restoredDetail(data.getString("id"))?.let { data.put("detail", it) }
                        emit(BackupRecordCodec.wrap(BackupCategory.TESTS, data))
                    }
                }
                if (lastTest != null && !foundLast) emit(BackupRecordCodec.wrap(BackupCategory.TESTS, lastTest))
                fallbackTests { data -> if (data.getString("id") != lastTest?.getString("id") && !exists("connection_tests", "test_id", data.getString("id")))
                    emit(BackupRecordCodec.wrap(BackupCategory.TESTS, data)) }
            }
            writer.records(BackupCategory.DAYS) { emit ->
                progress(BackupCategory.DAYS)
                db.rawQuery("SELECT day_key, start_ms, payload FROM observation_days ORDER BY start_ms", null).use { c ->
                    while (c.moveToNext()) {
                        val payload = JSONObject(c.getString(2))
                        val data = JSONObject().put("key", c.getString(0)).put("start", c.getLong(1))
                            .put("day", payload.getJSONObject("day"))
                        if (BackupRecordCodec.hasRestorableData(BackupCategory.DAYS, data))
                            emit(BackupRecordCodec.wrap(BackupCategory.DAYS, data))
                    }
                }
            }
            listOf(BackupCategory.WEEKLY, BackupCategory.MONTHLY).forEach { category -> writer.records(category) { emit ->
                progress(category)
                db.rawQuery("SELECT payload FROM connection_reports WHERE kind = ? ORDER BY start_ms",
                    arrayOf(if (category == BackupCategory.WEEKLY) "WEEK" else "MONTH")).use { c ->
                    while (c.moveToNext()) emit(BackupRecordCodec.wrap(category, JSONObject(c.getString(0))))
                }
            } }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun plan(backup: ValidatedBackup) = databaseOperation(backup, null)
    fun merge(backup: ValidatedBackup, restoreId: String) = databaseOperation(backup, restoreId)

    /** TEMP index lives on the transaction's pinned SQLite connection, not a giant Kotlin hash set. */
    private fun databaseOperation(backup: ValidatedBackup, restoreId: String?): Map<BackupCategory, BackupMergeCount> {
        val counts = mutableMapOf<BackupCategory, BackupMergeCount>()
        db.execSQL("PRAGMA temp_store=FILE")
        db.beginTransactionNonExclusive()
        try {
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS backup_history_keys (identity TEXT PRIMARY KEY)")
            db.execSQL("DELETE FROM backup_history_keys")
            db.compileStatement("INSERT OR IGNORE INTO backup_history_keys VALUES (?)").use { seed ->
                db.rawQuery("SELECT ${BackupRecordCodec.historyFields.joinToString(",")} FROM history", null).use { c ->
                    while (c.moveToNext()) {
                        val data = c.historyData()
                        seed.clearBindings(); seed.bindString(1, BackupRecordCodec.identity(BackupCategory.READINGS, data)); seed.executeInsert()
                    }
                }
            }
            db.compileStatement("INSERT OR IGNORE INTO backup_history_keys VALUES (?)").use { keyInsert ->
                listOf(BackupCategory.READINGS, BackupCategory.EVENTS, BackupCategory.TESTS,
                    BackupCategory.DAYS, BackupCategory.WEEKLY, BackupCategory.MONTHLY).forEach { category ->
                    var added = 0L; var existing = 0L; var ignored = 0L
                    backup.records(category) { record ->
                        val key = record.getString("key"); val data = record.getJSONObject("data")
                        if (!BackupRecordCodec.hasRestorableData(category, data)) {
                            ignored++
                            return@records
                        }
                        val duplicate = when (category) {
                            BackupCategory.READINGS, BackupCategory.EVENTS -> {
                                keyInsert.clearBindings(); keyInsert.bindString(1, key); keyInsert.executeInsert() == -1L
                            }
                            BackupCategory.TESTS -> exists("connection_tests", "test_id", key)
                            BackupCategory.DAYS -> preservedDayExists(key)
                            else -> exists("connection_reports", "report_key", key)
                        }
                        val seenKey = "${if (category in setOf(BackupCategory.WEEKLY, BackupCategory.MONTHLY)) "report" else category.name}:$key"
                        keyInsert.clearBindings(); keyInsert.bindString(1, seenKey)
                        val repeated = keyInsert.executeInsert() == -1L
                        if (duplicate || repeated) existing++ else {
                            added++
                            if (restoreId != null) when (category) {
                                BackupCategory.READINGS, BackupCategory.EVENTS -> db.insertOrThrow("history", null, historyValues(data))
                                BackupCategory.TESTS -> {
                                    // Preserve future labels without breaking the existing current-analysis enum decoder.
                                    val origin = data.getString("origin")
                                    if (TestOrigin.entries.none { it.name == origin }) data.put("originalOrigin", origin).put("origin", TestOrigin.UNKNOWN.name)
                                    db.insertOrThrow("connection_tests", null, ContentValues().apply {
                                        put("test_id", key); put("started_at", data.getLong("start")); put("payload", data.toString())
                                    })
                                }
                                BackupCategory.DAYS -> check(db.insertWithOnConflict("observation_days", null, ContentValues().apply {
                                    put("day_key", key); put("start_ms", data.getLong("start"))
                                    put("payload", JSONObject().put("signature", "restored:$restoreId").put("day", data.getJSONObject("day")).toString())
                                }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                                else -> db.insertOrThrow("connection_reports", null, ContentValues().apply {
                                    put("report_key", key); put("start_ms", data.getLong("start")); put("end_ms", data.getLong("end"))
                                    put("kind", data.getString("kind")); put("zone", data.getString("zone")); put("grade", data.getString("grade"))
                                    put("generated_ms", data.getLong("generated")); put("payload", data.toString())
                                })
                            }
                        }
                    }
                    counts[category] = BackupMergeCount(added, existing, ignored)
                }
            }
            if (restoreId != null) db.insertOrThrow("backup_restore_receipts", null, ContentValues().apply {
                put("restore_id", restoreId); put("backup_id", backup.manifest.id); put("committed_ms", System.currentTimeMillis())
            })
            db.execSQL("DROP TABLE backup_history_keys")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return counts
    }
    // A legacy empty placeholder must not suppress a real summary imported under the same key.
    private fun preservedDayExists(key: String): Boolean = db.rawQuery(
        "SELECT payload FROM observation_days WHERE day_key = ?", arrayOf(key)).use { c ->
        c.moveToFirst() && ConnectionHealthJson.day(JSONObject(c.getString(0)).getJSONObject("day")).hasPreservedData
    }
    private fun exists(table: String, field: String, key: String) = db.rawQuery(
        "SELECT 1 FROM $table WHERE $field = ? LIMIT 1", arrayOf(key)).use { it.moveToFirst() }
    private fun history(category: BackupCategory) = db.rawQuery(
        "SELECT ${BackupRecordCodec.historyFields.joinToString(",")} FROM history WHERE ${if (category == BackupCategory.EVENTS) "kind = 'event'" else "kind != 'event'"} ORDER BY time_ms, id", null)
    private fun Cursor.historyData() = JSONObject().apply {
        BackupRecordCodec.historyFields.forEachIndexed { index, name ->
            put(name, if (isNull(index)) JSONObject.NULL else when (name) {
                in BackupRecordCodec.historyTexts -> getString(index)
                in BackupRecordCodec.historyIntegers -> getLong(index)
                else -> getDouble(index)
            })
        }
    }
    private fun historyValues(data: JSONObject) = ContentValues().apply {
        BackupRecordCodec.historyFields.forEach { field ->
            if (data.isNull(field)) putNull(field) else when (field) {
                in BackupRecordCodec.historyTexts -> put(field, data.getString(field))
                in BackupRecordCodec.historyIntegers -> put(field, data.getLong(field))
                else -> put(field, data.getDouble(field))
            }
        }
    }
}
