package io.github.strongsand.lshell

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class BackupMergeCount(val added: Long = 0, val existing: Long = 0, val ignored: Long = 0) {
    fun json() = JSONObject().put("added", added).put("existing", existing).put("ignored", ignored)
}
internal data class BackupRestorePlan(val counts: Map<BackupCategory, BackupMergeCount>, val settings: BackupSettingsPlan)

/** Same commit as restored SQLite rows. A file flag alone cannot prove SQLite committed. */
internal object BackupDatabaseSchema {
    const val VERSION = 8
    const val RECEIPT_SQL = "CREATE TABLE IF NOT EXISTS backup_restore_receipts (restore_id TEXT PRIMARY KEY, backup_id TEXT NOT NULL, committed_ms INTEGER NOT NULL)"
}

internal interface BackupRestoreBackend {
    /** Prepare only private journal payloads; no application rows/files/preferences may change yet. */
    fun prepare(backup: ValidatedBackup, work: File): Map<BackupCategory, BackupMergeCount>
    /** Transaction: merge all DB categories plus receipt, or roll back all of them. */
    fun mergeDatabase(backup: ValidatedBackup, restoreId: String): Map<BackupCategory, BackupMergeCount>
    fun committed(restoreId: String): Boolean
    fun installFile(target: String, payload: File)
    fun installSettings(settings: BackupSettingsPlan)
    fun schedulesChanged()
}

/** Pure coordinator exercised with failure-injecting backends on the JVM. */
internal class BackupRestoreEngine(private val root: File, private val backend: BackupRestoreBackend) {
    private val journal = File(root, "journal.json")
    private val work = File(root, "prepared")
    fun pending() = journal.exists()
    fun restore(backup: ValidatedBackup, allowAutomation: Boolean): Map<BackupCategory, BackupMergeCount> {
        check(!pending())
        backup.verifyStaged() // Do not trust a preview after the staged content has changed.
        val settings = BackupSettingsCodec.plan(backupJson(backup.file(BackupCategory.SETTINGS).readBytes()), allowAutomation)
        work.deleteRecursively(); check(work.mkdirs())
        try {
            val fileCounts = backend.prepare(backup, work)
            val settingsFile = File(work, "settings.json")
            backupAtomicWrite(settingsFile, BackupSettingsCodec.encode(settings.compatible).toString().toByteArray(Charsets.UTF_8))
            val id = UUID.randomUUID().toString()
            val state = JSONObject().put("restoreId", id).put("backupId", backup.manifest.id)
                .put("startedAt", System.currentTimeMillis()).put("phase", "PREPARED")
                .put("databaseCommitted", false).put("settingsCommitted", false).put("finished", false)
                .put("operationsHash", backupHash(File(work, "operations.jsonl"))).put("settingsHash", backupHash(settingsFile))
                .put("ignoredSettings", settings.ignored)
            save(state) // Every payload is durable before the first application write.
            val databaseCounts = backend.mergeDatabase(backup, id)
            state.put("databaseCommitted", true).put("phase", "DATABASE_COMMITTED"); save(state)
            finish(state)
            return fileCounts + databaseCounts
        } catch (e: Exception) {
            if (journal.exists()) {
                val state = read()
                if (!backend.committed(state.getString("restoreId"))) cleanup()
                else throw BackupException(BackupException.Reason.RECOVERY, e)
            } else work.deleteRecursively()
            throw e
        }
    }
    /** No full history replay at startup: durable DB receipt disambiguates the commit boundary. */
    fun recover(): Boolean {
        if (!journal.exists()) { work.deleteRecursively(); return false }
        try {
            val state = read()
            if (!backend.committed(state.getString("restoreId"))) { cleanup(); return true }
            finish(state); return true
        } catch (e: Exception) { throw BackupException(BackupException.Reason.RECOVERY, e) }
    }
    private fun finish(state: JSONObject) {
        val operations = File(work, "operations.jsonl"); val settingsFile = File(work, "settings.json")
        require(backupHash(operations) == state.getString("operationsHash"))
        require(backupHash(settingsFile) == state.getString("settingsHash"))
        // Validate ALL prepared file hashes before installing even the first file.
        backupLines(operations) { op ->
            require(validTarget(op.getString("target")))
            require(op.getString("payload").matches(Regex("item[0-9]+\\.json")))
            require(backupHash(File(work, op.getString("payload"))) == op.getString("sha256"))
        }
        state.put("phase", "FILES").put("databaseCommitted", true); save(state)
        backupLines(operations) { backend.installFile(it.getString("target"), File(work, it.getString("payload"))) }
        state.put("phase", "SETTINGS"); save(state)
        // The prepared allowlist already incorporates explicit automation consent.
        backend.installSettings(BackupSettingsCodec.plan(backupJson(settingsFile.readBytes()), true))
        state.put("settingsCommitted", true).put("phase", "SCHEDULING"); save(state)
        backend.schedulesChanged() // Idempotent configure, never replay an old serialized job.
        state.put("finished", true).put("phase", "FINISHED"); save(state)
        cleanup()
    }
    private fun read(): JSONObject {
        require(journal.length() <= BackupLimits.MAX_SETTINGS)
        return backupJson(journal.readBytes()).also {
            UUID.fromString(it.getString("restoreId")); UUID.fromString(it.getString("backupId"))
            require(it.getLong("startedAt") > 0)
        }
    }
    private fun save(state: JSONObject) = backupAtomicWrite(journal, state.toString().toByteArray(Charsets.UTF_8))
    private fun cleanup() {
        // Delete the marker first: if cleanup is interrupted, the orphan work is harmless.
        check(!journal.exists() || journal.delete()); work.deleteRecursively()
    }
    companion object {
        fun validTarget(target: String) = target == "traffic_sessions.json" ||
            target.matches(Regex("daily_reports/report_[0-9]+\\.(json|snapshot)")) ||
            target.matches(Regex("extreme_tests/extreme_[0-9]+\\.json")) ||
            target.matches(Regex("restored_tests/result_[A-Za-z0-9_-]{1,100}\\.json"))
    }
}

internal fun backupAtomicWrite(file: File, bytes: ByteArray) {
    check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
    val temporary = File(file.parentFile, file.name + ".backup-tmp")
    try {
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temporary.delete() }
}
