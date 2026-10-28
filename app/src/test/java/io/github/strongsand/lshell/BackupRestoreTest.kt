package io.github.strongsand.lshell

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupRestoreTest {
    @get:Rule val temp = TemporaryFolder()
    private class ProcessDeath : Error()
    private inner class Backend : BackupRestoreBackend {
        val rows = linkedMapOf<String, String>()
        val receipts = mutableSetOf<String>()
        val installed = linkedMapOf<String, String>()
        var preferences = emptyList<BackupPreference>(); var databaseCalls = 0; var scheduleCalls = 0
        var scheduledPreferences = emptyList<BackupPreference>()
        var failDatabase = false; var dieBeforeCommit = false; var dieAfterCommit = false
        var failFile = false; var failSettings = false
        override fun prepare(backup: ValidatedBackup, work: File): Map<BackupCategory, BackupMergeCount> {
            val payload = File(work, "item0.json"); backupAtomicWrite(payload, "{\"complete\":true}".toByteArray())
            val op = JSONObject().put("target", "restored_tests/result_synthetic.json").put("payload", payload.name).put("sha256", backupHash(payload))
            backupAtomicWrite(File(work, "operations.jsonl"), (op.toString() + "\n").toByteArray())
            return emptyMap()
        }
        override fun mergeDatabase(backup: ValidatedBackup, restoreId: String): Map<BackupCategory, BackupMergeCount> {
            databaseCalls++; val transaction = rows.toMutableMap(); var added = 0L; var existing = 0L
            backup.records(BackupCategory.READINGS) { record ->
                val key = record.getString("key")
                if (transaction.containsKey(key)) existing++ else { transaction[key] = record.toString(); added++ }
                if (failDatabase) throw java.io.IOException("injected transaction failure")
            }
            if (dieBeforeCommit) throw ProcessDeath()
            rows.clear(); rows.putAll(transaction); receipts += restoreId
            if (dieAfterCommit) throw ProcessDeath()
            return mapOf(BackupCategory.READINGS to BackupMergeCount(added, existing))
        }
        override fun committed(restoreId: String) = restoreId in receipts
        override fun installFile(target: String, payload: File) {
            if (failFile) throw java.io.IOException("injected file failure")
            installed.putIfAbsent(target, payload.readText())
        }
        override fun installSettings(settings: BackupSettingsPlan) {
            if (failSettings) throw java.io.IOException("injected settings failure")
            preferences = settings.compatible
        }
        override fun schedulesChanged() { scheduleCalls++; scheduledPreferences = preferences.toList() }
    }
    private fun backup(count: Int = 2, settings: List<BackupPreference> = listOf(BackupPreference("orbit_experience", "theme", "DARK"))): ValidatedBackup {
        val writer = BackupWriter(temp.newFolder())
        writer.records(BackupCategory.READINGS) { emit -> repeat(count) { i ->
            val d = JSONObject().apply { BackupRecordCodec.historyFields.forEach { put(it, JSONObject.NULL) }
                put("time_ms", 1000 + i); put("kind", "reading"); put("label", "Synthetic"); put("source", "LOCAL_PHONE"); put("download", 0) }
            emit(BackupRecordCodec.wrap(BackupCategory.READINGS, d))
        } }
        writer.settings(BackupSettingsCodec.encode(settings))
        val zip = File(temp.newFolder(), "backup.zip"); writer.finish(zip, "test", 2, 8)
        return BackupValidator.validate(zip, temp.newFolder())
    }
    @Test fun cleanInstallationRestoreAndRepeatAreIdempotent() {
        val backend = Backend(); val root = temp.newFolder(); val engine = BackupRestoreEngine(root, backend)
        backup().use { b ->
            assertEquals(BackupMergeCount(2, 0), engine.restore(b, false)[BackupCategory.READINGS])
            assertEquals(BackupMergeCount(0, 2), engine.restore(b, false)[BackupCategory.READINGS])
        }
        assertEquals(2, backend.rows.size); assertEquals(1, backend.installed.size)
        assertEquals("DARK", backend.preferences.single().value); assertFalse(engine.pending())
        assertFalse(File(root, "prepared").exists())
    }
    @Test fun mergePreservesCurrentExtraDataAndCountsDuplicates() {
        val backend = Backend(); backend.rows["existing-extra"] = "unchanged"
        val engine = BackupRestoreEngine(temp.newFolder(), backend)
        backup(1).use { engine.restore(it, false) }
        backup(2).use { assertEquals(BackupMergeCount(1, 1), engine.restore(it, false)[BackupCategory.READINGS]) }
        assertEquals("unchanged", backend.rows["existing-extra"]); assertEquals(3, backend.rows.size)
    }
    @Test fun databaseFailureRollsBackWithoutFileOrPreferenceWrites() {
        val backend = Backend().apply { failDatabase = true; rows["current"] = "preserved" }
        val engine = BackupRestoreEngine(temp.newFolder(), backend)
        backup().use { assertThrows(java.io.IOException::class.java) { engine.restore(it, false) } }
        assertEquals(mapOf("current" to "preserved"), backend.rows); assertTrue(backend.installed.isEmpty())
        assertTrue(backend.preferences.isEmpty()); assertFalse(engine.pending())
    }
    @Test fun processDeathBeforeDbCommitDiscardsOnlyPreparedWork() {
        val backend = Backend().apply { dieBeforeCommit = true }; val root = temp.newFolder()
        backup().use { assertThrows(ProcessDeath::class.java) { BackupRestoreEngine(root, backend).restore(it, false) } }
        assertTrue(BackupRestoreEngine(root, backend).recover())
        assertTrue(backend.rows.isEmpty()); assertTrue(backend.installed.isEmpty()); assertTrue(backend.preferences.isEmpty())
    }
    @Test fun processDeathImmediatelyAfterDbCommitUsesReceiptEvenWithPreparedPhase() {
        val backend = Backend().apply { dieAfterCommit = true }; val root = temp.newFolder()
        backup().use { assertThrows(ProcessDeath::class.java) { BackupRestoreEngine(root, backend).restore(it, false) } }
        assertFalse(JSONObject(File(root, "journal.json").readText()).getBoolean("databaseCommitted"))
        assertTrue(BackupRestoreEngine(root, backend).recover())
        assertEquals(1, backend.databaseCalls); assertEquals(2, backend.rows.size); assertEquals(1, backend.installed.size)
        assertEquals("DARK", backend.preferences.single().value); assertFalse(File(root, "journal.json").exists())
    }
    @Test fun fileFailureRemainsVisibleAndRollsForwardOnNextStartup() {
        val backend = Backend().apply { failFile = true }; val root = temp.newFolder()
        backup().use { b -> val failure = assertThrows(BackupException::class.java) { BackupRestoreEngine(root, backend).restore(b, false) }
            assertEquals(BackupException.Reason.RECOVERY, failure.reason) }
        assertEquals(2, backend.rows.size); assertTrue(File(root, "journal.json").exists())
        backend.failFile = false; BackupRestoreEngine(root, backend).recover()
        assertEquals(1, backend.databaseCalls); assertEquals(1, backend.installed.size); assertEquals(1, backend.preferences.size)
    }
    @Test fun settingsFailureRecoversWithoutDuplicatingAlreadyInstalledFiles() {
        val backend = Backend().apply { failSettings = true }; val root = temp.newFolder()
        backup().use { assertThrows(BackupException::class.java) { BackupRestoreEngine(root, backend).restore(it, false) } }
        assertEquals(1, backend.installed.size); assertTrue(backend.preferences.isEmpty())
        backend.failSettings = false; BackupRestoreEngine(root, backend).recover()
        assertEquals(1, backend.installed.size); assertEquals(2, backend.rows.size); assertEquals(1, backend.preferences.size)
    }
    @Test fun recoveryCorruptionCannotBeHiddenAsSuccess() {
        val backend = Backend().apply { dieAfterCommit = true }; val root = temp.newFolder()
        backup().use { assertThrows(ProcessDeath::class.java) { BackupRestoreEngine(root, backend).restore(it, false) } }
        File(root, "prepared/item0.json").writeText("tampered")
        assertThrows(BackupException::class.java) { BackupRestoreEngine(root, backend).recover() }
        assertTrue(File(root, "journal.json").exists()); assertTrue(backend.installed.isEmpty())
    }
    @Test fun stagedBackupTamperingProducesZeroDatabaseCalls() {
        val backend = Backend(); backup().use { b -> b.file(BackupCategory.READINGS).appendText("corrupt")
            assertThrows(IllegalArgumentException::class.java) { BackupRestoreEngine(temp.newFolder(), backend).restore(b, false) } }
        assertEquals(0, backend.databaseCalls); assertTrue(backend.rows.isEmpty())
    }
    @Test fun corruptedOrFutureArchiveCannotReachAnyRestoreWrite() {
        listOf(false, true).forEach { future ->
            val backend = Backend(); val engine = BackupRestoreEngine(temp.newFolder(), backend)
            backup().use { original ->
                val zip = File(temp.newFolder(), "invalid.zip")
                ZipOutputStream(zip.outputStream()).use { out ->
                    original.manifest.files.forEach { f ->
                        out.putNextEntry(ZipEntry(f.category.path)); out.write(original.file(f.category).readBytes())
                        if (!future && f.category == BackupCategory.READINGS) out.write('x'.code)
                        out.closeEntry()
                    }
                    out.putNextEntry(ZipEntry("manifest.json"))
                    val manifest = original.manifest.json()
                    if (future) manifest.put("backupSchemaVersion", 2)
                    out.write(manifest.toString().toByteArray(Charsets.UTF_8)); out.closeEntry()
                }
                assertThrows(BackupException::class.java) {
                    BackupValidator.validate(zip, temp.newFolder()).use { engine.restore(it, false) }
                }
            }
            assertEquals(0, backend.databaseCalls); assertTrue(backend.rows.isEmpty())
            assertTrue(backend.installed.isEmpty()); assertTrue(backend.preferences.isEmpty())
            assertFalse(engine.pending())
        }
    }
    @Test fun incompatibleSettingsDoNotPreventDataMergeOrBecomeRuntimeState() {
        val backend = Backend()
        backup(settings = listOf(BackupPreference("orbit_experience", "theme", "LIGHT"),
            BackupPreference("old_settings", "removed", true), BackupPreference("daily_report_preferences", "hour", 100),
            BackupPreference("automatic_speed_tests", "smart", true))).use {
            BackupRestoreEngine(temp.newFolder(), backend).restore(it, false)
        }
        assertEquals(2, backend.rows.size)
        assertEquals(listOf(BackupPreference("orbit_experience", "theme", "LIGHT")), backend.preferences)
    }
    @Test fun emptyBackupRestoresSafely() { val backend = Backend()
        backup(0).use { assertEquals(BackupMergeCount(), BackupRestoreEngine(temp.newFolder(), backend).restore(it, false)[BackupCategory.READINGS]) }
    }
    @Test fun restoredBeaconIntervalIsInstalledBeforeSchedulesAreRebuilt() {
        val interval = BackupPreference("beacon_preferences", "sync_interval_minutes", 60L)
        val backend = Backend()
        backup(settings = listOf(interval, BackupPreference("automatic_speed_tests", "smart", true))).use {
            BackupRestoreEngine(temp.newFolder(), backend).restore(it, false)
        }
        assertEquals(1, backend.scheduleCalls)
        assertEquals(interval.group, backend.scheduledPreferences.single().group)
        assertEquals(interval.name, backend.scheduledPreferences.single().name)
        assertEquals(60L, (backend.scheduledPreferences.single().value as Number).toLong())
        // Rebuilding a configured Beacon schedule does not grant consent for speed tests.
        assertFalse(backend.scheduledPreferences.any { it.name == "smart" })
    }
    @Test fun maliciousPreparedTargetsAreNotAllowed() {
        listOf("../history.db", "/data/history.db", "local.properties", "connection_test_execution.json", "daily_reports/report_-1.json").forEach {
            assertFalse(BackupRestoreEngine.validTarget(it)) }
        assertTrue(BackupRestoreEngine.validTarget("traffic_sessions.json"))
    }
    @Test fun activeTestBackupBecomesInterruptedCopyAndKeepsMeasuredZero() {
        val original = historicalTestDetail(ConnectionTestState(id = 10, phase = ConnectionTestPhase.RUNNING,
            startedAt = 1000, result = SpeedTestResult(0f, Float.NaN, null, true, false)))
        val data = backupArchivedTest(original, 5000)
        assertEquals("RUNNING", original.getString("phase")); assertEquals("INTERRUPTED", data.getString("outcome"))
        assertEquals(5000L, data.getLong("end")); assertEquals(0.0, data.getJSONArray("download").getDouble(0), 0.0)
        assertEquals(0, data.getJSONArray("upload").length())
        BackupRecordCodec.validate(BackupCategory.TESTS, BackupRecordCodec.wrap(BackupCategory.TESTS, data))
    }
    @Test fun restoredOldDailySnapshotNeverRegeneratesOnOpenOrBeaconStaleMark() {
        val dir = temp.newFolder(); val store = DailyReportStore(dir)
        val report = DailyReport(1000, 2000, 3000, "Original", 0, 0, 0, null, 0, null, null, null, 0,
            null, 0, 0, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 1)
        store.save(report); File(dir, "report_2000.snapshot").writeText("frozen")
        store.markStaleForRange(1100, 1200)
        assertFalse(store.isStale(report))
        val opened = store.refreshIfStale(2000) { _, _ -> error("Closed snapshot must not be recalculated") }
        assertEquals(report, opened)
        BackupRecordCodec.validate(BackupCategory.DAILY, BackupRecordCodec.wrap(BackupCategory.DAILY, report.toJson()))
    }
    @Test fun normalLegacyMeasuredZeroUsesTheExistingReaderDefault() {
        val detail = historicalTestDetail(ConnectionTestState(id = 10, phase = ConnectionTestPhase.COMPLETED,
            startedAt = 1000, endedAt = 2000, result = SpeedTestResult(0f, 0f, null)))
        detail.getJSONObject("result").remove("down_measured"); detail.getJSONObject("result").remove("up_measured")
        val archived = backupArchivedTest(detail, 3000)
        assertEquals(0.0, archived.getJSONArray("download").getDouble(0), 0.0)
        assertEquals(0.0, archived.getJSONArray("upload").getDouble(0), 0.0)
    }
    @Test fun noPendingJournalDoesNotLeaveAnEternalRecoveryFlag() {
        val root = temp.newFolder(); File(root, "prepared").mkdirs(); File(root, "prepared/orphan").writeText("temp")
        assertFalse(BackupRestoreEngine(root, Backend()).recover()); assertFalse(File(root, "prepared").exists())
    }
    @Test fun restoreAndExecutionStartReservationsCannotRace() {
        assertTrue(BackupGate.beginExecution())
        try { assertThrows(BackupException::class.java) { BackupGate.beginRestore() } }
        finally { BackupGate.endExecution() }
        BackupGate.beginRestore()
        try { assertFalse(BackupGate.beginExecution()) }
        finally { BackupGate.endRestore(false) }
        BackupGate.endRestore(true)
        try { assertFalse(BackupGate.beginExecution()) }
        finally { BackupGate.endRestore(false) }
    }
}
