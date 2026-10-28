package io.github.strongsand.lshell

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Serializes bounded local file operations, not network collection or RPC implementation. */
internal object BackupGate {
    val monitor = Any()
    private val recovering = ThreadLocal.withInitial { false }
    @Volatile var restoring = false; private set
    @Volatile var pending = false; private set
    private var starts = 0
    val revision = MutableStateFlow(0L)
    @Synchronized fun beginExecution(): Boolean {
        if (restoring || pending) return false
        starts++; return true
    }
    @Synchronized fun endExecution() { check(starts > 0); starts-- }
    @Synchronized fun beginRestore() {
        if (restoring || pending || starts > 0) throw BackupException(BackupException.Reason.BUSY)
        restoring = true
    }
    @Synchronized fun endRestore(journalPending: Boolean) { restoring = false; pending = journalPending }
    fun ensureRecovered(context: Context) {
        if (recovering.get() == true || (restoring && Thread.holdsLock(monitor))) return
        val root = File(context.applicationContext.filesDir, "backup_restore_v1")
        if (!File(root, "journal.json").exists()) return
        synchronized(monitor) {
            recovering.set(true); pending = true
            try {
                BackupCoordinator(context).recoverInternal(); pending = false
                revision.value++; TrafficSessionStore.revision.value++; ConnectionHealthStore.revision.value++
            }
            finally { recovering.set(false) }
        }
    }
}

internal data class BackupCreated(val manifest: BackupManifest, val bytes: Long)

internal class BackupCoordinator(context: Context) {
    private val app = context.applicationContext
    private val cache = File(app.cacheDir, "backup_v1").apply { mkdirs() }
    private val root = File(app.filesDir, "backup_restore_v1")
    private val backend = AndroidBackupBackend(app)
    private fun engine() = BackupRestoreEngine(root, backend)
    fun recoverInternal() = engine().recover()
    fun recover() {
        BackupGate.ensureRecovered(app)
        ConnectionTestController.get(app).refreshRestoredHistory()
    }

    fun create(destination: Uri, progress: (BackupCategory?) -> Unit): BackupCreated = synchronized(BackupGate.monitor) {
        BackupGate.ensureRecovered(app)
        val work = File(cache, "write-${UUID.randomUUID()}"); check(work.mkdirs())
        try {
            val writer = BackupWriter(work)
            progress(BackupCategory.READINGS)
            HistoryStore(app).use { store -> BackupDatabaseAdapter(store.writableDatabase).export(writer, lastTest(System.currentTimeMillis()), restoredDetail = { id ->
                require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
                val restored = File(app.filesDir, "restored_tests/result_$id.json")
                if (restored.exists()) readJson(restored) else File(app.filesDir, "extreme_tests/extreme_$id.json")
                    .takeIf { it.exists() }?.let { archivalExtreme(readJson(it)).getJSONObject("detail") }
            }, fallbackTests = { emit -> files("extreme_tests", Regex("extreme_[0-9]+\\.json")).forEach {
                emit(archivalExtreme(readJson(it)))
            } }, progress = { progress(it) }) }
            progress(BackupCategory.EXTREME)
            writer.records(BackupCategory.EXTREME) { emit -> files("extreme_tests", Regex("extreme_[0-9]+\\.json")).forEach {
                val data = readJson(it); emit(BackupRecordCodec.wrap(BackupCategory.EXTREME, data))
            } }
            progress(BackupCategory.TRAFFIC)
            writer.records(BackupCategory.TRAFFIC) { emit -> TrafficSessionStore(app).read().forEach { session ->
                val saved = if (session.active) session.copy(endedAt = System.currentTimeMillis().coerceAtLeast(session.startedAt), interrupted = true, bootCount = -1)
                    else session.copy(bootCount = -1)
                emit(BackupRecordCodec.wrap(BackupCategory.TRAFFIC, TrafficSessionMetadata.json(saved)))
            } }
            progress(BackupCategory.DAILY)
            writer.records(BackupCategory.DAILY) { emit -> files("daily_reports", Regex("report_[0-9]+\\.json")).forEach {
                emit(BackupRecordCodec.wrap(BackupCategory.DAILY, readJson(it)))
            } }
            progress(BackupCategory.SETTINGS)
            writer.settings(BackupSettingsCodec.encode(backupPreferences(app)))
            val zip = File(work, "backup.zip")
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val manifest = writer.finish(zip, info.versionName ?: "unknown",
                androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info), BackupDatabaseSchema.VERSION)
            BackupValidator.validate(zip, cache).use { check(it.manifest.id == manifest.id) }
            progress(null)
            app.contentResolver.openOutputStream(destination, "wt")?.use { out -> zip.inputStream().use { it.copyTo(out) } }
                ?: throw BackupException(BackupException.Reason.STORAGE)
            // Definitive success requires reading and validating the actual SAF document, not only our temp ZIP.
            select(destination).use { check(it.manifest.id == manifest.id) }
            BackupCreated(manifest, zip.length())
        } finally { work.deleteRecursively() }
    }
    fun select(uri: Uri): ValidatedBackup {
        val file = File(cache, "input-${UUID.randomUUID()}.zip")
        try {
            app.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().buffered().use { out ->
                val buffer = ByteArray(32 * 1024); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break
                    total += n; if (total > BackupLimits.MAX_ARCHIVE) throw BackupException(BackupException.Reason.LIMIT)
                    out.write(buffer, 0, n) }
            } } ?: throw BackupException(BackupException.Reason.STORAGE)
            return BackupValidator.validate(file, cache)
        } finally { file.delete() }
    }
    fun plan(backup: ValidatedBackup): BackupRestorePlan = synchronized(BackupGate.monitor) {
        BackupGate.ensureRecovered(app); backup.verifyStaged()
        val counts = HistoryStore(app).use { BackupDatabaseAdapter(it.writableDatabase).plan(backup) }
        val staging = File(cache, "plan-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            BackupRestorePlan(counts + backend.prepare(backup, staging),
                BackupSettingsCodec.plan(backupJson(backup.file(BackupCategory.SETTINGS).readBytes()), false))
        } finally { staging.deleteRecursively() }
    }
    fun restore(backup: ValidatedBackup, allowAutomation: Boolean): Map<BackupCategory, BackupMergeCount> = synchronized(BackupGate.monitor) {
        BackupGate.ensureRecovered(app)
        BackupGate.beginRestore()
        try {
            if (ConnectionTestController.get(app).state.value.active || TrafficSessionStore(app).read().any { it.active })
                throw BackupException(BackupException.Reason.BUSY)
            engine().restore(backup, allowAutomation).also {
                BackupGate.revision.value++; TrafficSessionStore.revision.value++; ConnectionHealthStore.revision.value++
                ConnectionTestController.get(app).refreshRestoredHistory()
            }
        } finally { BackupGate.endRestore(engine().pending()) }
    }
    fun cleanupOrphans() = synchronized(BackupGate.monitor) {
        // Only our owned cache directory; never the active durable journal or external documents.
        val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
        cache.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        if (!engine().pending()) engine().recover() // Prepared payloads abandoned before journal creation.
    }
    private fun files(folder: String, pattern: Regex) = File(app.filesDir, folder).listFiles()
        .orEmpty().filter { it.isFile && pattern.matches(it.name) }.sortedBy { it.name }
    private fun readJson(file: File): JSONObject {
        if (file.length() > BackupLimits.MAX_LINE) throw BackupException(BackupException.Reason.LIMIT)
        return backupJson(file.readBytes())
    }
    private fun archivalExtreme(data: JSONObject): JSONObject {
        val session = executionSessionFromJson(data)
        val phase = if (session.runs.size == session.requestedTests) ConnectionTestPhase.COMPLETED else ConnectionTestPhase.INTERRUPTED
        return backupArchivedTest(historicalTestDetail(ConnectionTestState(id = session.id, kind = ConnectionTestKind.EXTREME,
            phase = phase, startedAt = session.startedAt, endedAt = session.endedAt, total = session.requestedTests,
            completed = session.runs.size, runs = session.runs, origin = TestOrigin.EXTREME)), session.endedAt)
    }
    private fun lastTest(now: Long): JSONObject? {
        val file = AtomicFile(File(app.filesDir, "connection_test_execution.json"))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val detail = file.openRead().use { backupJson(BackupValidator.readBounded(it, BackupLimits.MAX_LINE.toLong())) }
        if (detail.optLong("id") <= 0 || detail.optString("phase") == "IDLE") return null
        return backupArchivedTest(detail, now)
    }
}

internal fun backupArchivedTest(detail: JSONObject, now: Long): JSONObject {
    val d = JSONObject(detail.toString()) // Normalize only the backup copy, never the active execution.
    val active = d.getString("phase") in setOf("STARTING", "RUNNING", "BETWEEN_ROUNDS")
    if (active) d.put("phase", "INTERRUPTED").put("end", maxOf(now, d.getLong("start")))
    val origin = d.optString("origin").ifBlank { if (d.getString("kind") == "EXTREME") "EXTREME" else "UNKNOWN" }
    val down = mutableListOf<Double>(); val up = mutableListOf<Double>(); val latency = mutableListOf<Double>()
    fun metric(j: JSONObject, key: String, measured: String?, target: MutableList<Double>) {
        val value = j.optDouble(key)
        val valid = value.isFinite() && value >= 0 && (measured == null ||
            if (j.has(measured) && !j.isNull(measured)) j.getBoolean(measured) else measured in setOf("down_measured", "up_measured") || value > 0)
        if (valid) target += value
    }
    if (d.getString("kind") == "EXTREME") d.optJSONObject("extreme")?.let { extreme ->
        extreme.put("end", d.getLong("end"))
        val runs = extreme.getJSONArray("runs")
        repeat(runs.length()) { val r = runs.getJSONObject(it)
            if (r.isNull("failure")) { metric(r, "down", "download_measured", down); metric(r, "up", "upload_measured", up); metric(r, "latency", null, latency) }
        }
    } else d.optJSONObject("result")?.let { r ->
        metric(r, "down", "down_measured", down); metric(r, "up", "up_measured", up); metric(r, "latency", null, latency)
    }
    return JSONObject().put("schema", 1).put("id", d.getLong("id").toString()).put("origin", origin)
        .put("start", d.getLong("start")).put("end", d.getLong("end")).put("outcome", d.getString("phase"))
        .put("download", JSONArray(down)).put("upload", JSONArray(up)).put("latency", JSONArray(latency)).put("detail", d)
}

private class AndroidBackupBackend(private val app: Context) : BackupRestoreBackend {
    override fun prepare(backup: ValidatedBackup, work: File): Map<BackupCategory, BackupMergeCount> {
        check(work.mkdirs() || work.isDirectory)
        val counts = mutableMapOf<BackupCategory, BackupMergeCount>(); var index = 0; var preparedBytes = 0L
        val identities = mutableSetOf<String>() // Only bounded metadata/file categories, never raw history.
        java.io.FileOutputStream(File(work, "operations.jsonl")).use { operations ->
            fun addFile(target: String, data: JSONObject) {
                require(BackupRestoreEngine.validTarget(target))
                if (index >= 10000) throw BackupException(BackupException.Reason.LIMIT)
                val name = "item${index++}.json"; val file = File(work, name)
                val bytes = data.toString().toByteArray(Charsets.UTF_8); preparedBytes += bytes.size
                if (preparedBytes > 128L * 1024 * 1024) throw BackupException(BackupException.Reason.LIMIT)
                backupAtomicWrite(file, bytes)
                val operation = JSONObject().put("target", target).put("payload", name).put("sha256", backupHash(file))
                operations.write((operation.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
            listOf(BackupCategory.DAILY, BackupCategory.EXTREME).forEach { category ->
                var added = 0L; var existing = 0L
                backup.records(category) { record -> val data = record.getJSONObject("data")
                    val target = if (category == BackupCategory.DAILY) "daily_reports/report_${data.getLong("end")}.json"
                        else "extreme_tests/extreme_${data.getLong("id")}.json"
                    if (File(app.filesDir, target).exists() || !identities.add(target)) existing++ else {
                        require(index < 10000); added++; addFile(target, data)
                        if (category == BackupCategory.DAILY) addFile(target.removeSuffix("json") + "snapshot", JSONObject().put("backupId", backup.manifest.id))
                    }
                }
                counts[category] = BackupMergeCount(added, existing)
            }
            // Keep full normal/execution details without replacing current execution or a notification's result.
            backup.records(BackupCategory.TESTS) { record -> record.getJSONObject("data").optJSONObject("detail")?.let { detail ->
                val target = "restored_tests/result_${record.getString("key")}.json"
                if (!File(app.filesDir, target).exists() && identities.add(target)) addFile(target, detail)
            } }
            val current = TrafficSessionStore(app).read(); val ids = current.map { it.id }.toMutableSet()
            val added = mutableListOf<TrafficSession>(); var existing = 0L
            backup.records(BackupCategory.TRAFFIC) { record ->
                val data = record.getJSONObject("data")
                if (!ids.add(data.getString("id"))) existing++ else added += TrafficSessionMetadata.decode(
                    JSONObject().put("version", 1).put("sessions", JSONArray().put(data)).toString()).single()
            }
            if (current.size + added.size > 1000) throw BackupException(BackupException.Reason.LIMIT)
            if (added.isNotEmpty()) addFile("traffic_sessions.json", JSONObject(TrafficSessionMetadata.encode((current + added).sortedByDescending { it.startedAt })))
            counts[BackupCategory.TRAFFIC] = BackupMergeCount(added.size.toLong(), existing)
            operations.fd.sync()
        }
        return counts
    }
    override fun mergeDatabase(backup: ValidatedBackup, restoreId: String) = HistoryStore(app).use {
        BackupDatabaseAdapter(it.writableDatabase).merge(backup, restoreId)
    }
    override fun committed(restoreId: String): Boolean = HistoryStore(app).use { store ->
        store.readableDatabase.rawQuery("SELECT 1 FROM backup_restore_receipts WHERE restore_id = ?", arrayOf(restoreId)).use { it.moveToFirst() }
    }
    override fun installFile(target: String, payload: File) {
        val destination = File(app.filesDir, target)
        require(destination.canonicalPath.startsWith(app.filesDir.canonicalPath + File.separator))
        if (target == "traffic_sessions.json") {
            val current = TrafficSessionStore(app).read(); val ids = current.map { it.id }.toSet()
            val incoming = TrafficSessionMetadata.decode(payload.readText(Charsets.UTF_8)).filter { it.id !in ids }
            require(current.size + incoming.size <= 1000)
            backupAtomicWrite(destination, TrafficSessionMetadata.encode((current + incoming).sortedByDescending { it.startedAt }).toByteArray(Charsets.UTF_8))
        } else if (!destination.exists()) backupAtomicWrite(destination, payload.readBytes())
    }
    override fun installSettings(settings: BackupSettingsPlan) {
        settings.compatible.groupBy { it.group }.forEach { (group, values) ->
            val prefs = app.getSharedPreferences(group, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            values.forEach { p -> when (val v = p.value) {
                is Boolean -> editor.putBoolean(p.name, v)
                is String -> editor.putString(p.name, v)
                is Number -> if (group == "beacon_preferences" && p.name == "sync_interval_minutes") editor.putLong(p.name, v.toLong()) else editor.putInt(p.name, v.toInt())
            } }
            // Install a fresh scheduling baseline exactly once. Recovery must not postpone it repeatedly.
            val enabling = values.any { it.value == true && it.name in setOf("scheduled", "enabled") && !prefs.getBoolean(it.name, false) }
            if (enabling && group in setOf("automatic_speed_tests", "daily_report_preferences")) editor.putLong("enabled_at", System.currentTimeMillis())
            check(editor.commit())
        }
    }
    override fun schedulesChanged() {
        DailyReportScheduler.schedule(app); ConnectionHealthSchedule.schedule(app); AutomaticTestSchedule.configure(app, replace = true)
        io.github.strongsand.lshell.beacon.BeaconSyncSchedule.configure(app)
    }
}

private fun backupPreferences(app: Context): List<BackupPreference> {
    val defaults: Map<String, Any> = mapOf("theme" to "SYSTEM", "haptics" to "STANDARD", "technical" to false,
        "silent_alerts" to false, "interval_seconds" to 60, "ar_max_distance_km" to 1000,
        "hour" to 21, "minute" to 0, "notify" to true, "history" to true, "retention" to "THIRTY", "format" to "BOTH",
        "scheduled" to false, "smart" to false, "interval" to 6, "cooldown" to 12, "smart_limit" to 1,
        "custom" to false, "custom_times" to "480",
        "wifi_only" to true, "retention_days" to 30, "notifications" to true, "notify_offline" to true,
        "notify_dishy_unavailable" to true, "sync_interval_minutes" to 30L,
        "antenna" to "latency,loss,download,upload,power", "traffic" to TrafficMetric.entries.joinToString(",") { it.name })
    return BackupSettingsCodec.rules.map { rule ->
        val default = if (rule.name == "enabled") rule.group == "connection_health_reports" else defaults.getValue(rule.name)
        val value = app.getSharedPreferences(rule.group, Context.MODE_PRIVATE).all[rule.name] ?: default
        BackupPreference(rule.group, rule.name, value)
    }.filter { p -> BackupSettingsCodec.rules.first { it.group == p.group && it.name == p.name }.accepts(p.value) }
}
