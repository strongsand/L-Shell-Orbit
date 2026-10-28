package io.github.strongsand.lshell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.coroutineContext

enum class ReportExportFormat { JSON, CSV, BOTH }

class ReportArchivePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("report_archive", Context.MODE_PRIVATE)
    val tree: Uri? get() = prefs.getString("tree", null)?.let(Uri::parse)
    val automatic: Boolean get() = prefs.getBoolean("automatic", false)
    val format: ReportExportFormat get() = runCatching {
        ReportExportFormat.valueOf(prefs.getString("format", "BOTH")!!)
    }.getOrDefault(ReportExportFormat.BOTH)
    val lastExport: Long get() = prefs.getLong("last_export", 0)
    fun setTree(uri: Uri) = prefs.edit().putString("tree", uri.toString()).remove("failed").apply()
    fun setAutomatic(value: Boolean) = prefs.edit().putBoolean("automatic", value).apply()
    fun setFormat(value: ReportExportFormat) = prefs.edit().putString("format", value.name).apply()
    fun completed() = prefs.edit().putLong("last_export", System.currentTimeMillis()).remove("failed").apply()
    fun failed() = prefs.edit().putBoolean("failed", true).apply()
    val failed: Boolean get() = prefs.getBoolean("failed", false)
    fun observe(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)
    fun stopObserving(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
}

object ReportArchiveSchedule {
    fun enqueue(context: Context, end: Long) {
        val prefs = ReportArchivePreferences(context)
        if (!prefs.automatic || prefs.tree == null) return
        val request = OneTimeWorkRequestBuilder<ReportArchiveWorker>()
            .setInputData(workDataOf("report_end" to end)).build()
        // Regeneration supersedes an older export; filenames remain deterministic.
        WorkManager.getInstance(context).enqueueUniqueWork("report_archive_$end", ExistingWorkPolicy.REPLACE, request)
    }
}

class ReportArchiveWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val prefs = ReportArchivePreferences(applicationContext)
        if (!prefs.automatic) return Result.success()
        val report = DailyReportStore(applicationContext).get(inputData.getLong("report_end", 0))
            ?: return Result.success()
        return try {
            ReportArchive.export(applicationContext, report)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            prefs.failed()
            Result.failure() // Never retry a revoked permission in a loop.
        }
    }
}

/** SAF operations are confined to a user-granted tree. The internal DB stays authoritative. */
object ReportArchive {
    private val mutex = Mutex()
    private val directoryMime = DocumentsContract.Document.MIME_TYPE_DIR

    fun selectFolder(context: Context, uri: Uri, flags: Int) {
        require(DocumentsContract.isTreeUri(uri))
        val grants = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(grants and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        val prefs = ReportArchivePreferences(context)
        context.contentResolver.takePersistableUriPermission(uri, grants)
        val old = prefs.tree
        prefs.setTree(uri)
        if (old != null && old != uri) {
            runCatching { context.contentResolver.releasePersistableUriPermission(old,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
    }

    private fun root(context: Context): Uri {
        val uri = ReportArchivePreferences(context).tree ?: error("No archive folder selected.")
        require(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) {
            "Archive permission was revoked."
        }
        return DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    }

    private fun document(context: Context, parent: Uri, name: String, mime: String): Uri {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == name) {
                require(cursor.getString(2) == mime) { "Archive filename conflicts with another document." }
                return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
            }
        } ?: error("Archive folder unavailable.")
        return DocumentsContract.createDocument(resolver, parent, mime, name) ?: error("Cannot create archive document.")
    }

    suspend fun testAccess(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val test = DocumentsContract.createDocument(context.contentResolver, root(context),
                "text/plain", "lshell_access_test_${System.currentTimeMillis()}.tmp") ?: error("Folder is not writable.")
            try {
                context.contentResolver.openOutputStream(test, "wt")?.use { it.write("L-Shell Orbit".toByteArray()) }
                    ?: error("Folder is not writable.")
            } finally {
                check(DocumentsContract.deleteDocument(context.contentResolver, test))
            }
        }
    }

    suspend fun exportTraffic(context: Context, session: TrafficSession, analysis: TrafficAnalysis) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val preferences = ReportArchivePreferences(context)
            val day = Instant.ofEpochMilli(session.startedAt).atZone(ZoneId.of(session.timezone)).toLocalDate()
            var parent = root(context)
            for (part in listOf("L-Shell Orbit", "Traffic sessions", day.year.toString(),
                "%02d".format(java.util.Locale.ROOT, day.monthValue), day.toString()))
                parent = document(context, parent, part, directoryMime)
            @Suppress("DEPRECATION")
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            val prefix = "traffic_${session.id}"
            suspend fun write(name: String, mime: String, content: (Appendable) -> Unit) {
                coroutineContext.ensureActive()
                val uri = document(context, parent, name, mime)
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { content(it) }
                    ?: error("Cannot write archive document.")
            }
            if (preferences.format != ReportExportFormat.CSV) write("$prefix.json", "application/json") { TrafficSessionExport.writeJson(it, session, analysis, version) }
            if (preferences.format != ReportExportFormat.JSON) write("$prefix.csv", "text/csv") { TrafficSessionExport.writeCsv(it, analysis) }
            write("$prefix.manifest.json", "application/json") { it.append(TrafficSessionExport.manifest(session, analysis, version).toString(2)) }
            preferences.completed()
        }
    }

    suspend fun export(context: Context, report: DailyReport) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val preferences = ReportArchivePreferences(context)
            val zone = ZoneId.systemDefault()
            val day = Instant.ofEpochMilli(report.start).atZone(zone).toLocalDate()
            var parent = root(context)
            for (part in listOf("L-Shell Orbit", "Reports", day.year.toString(),
                "%02d".format(java.util.Locale.ROOT, day.monthValue), day.toString())) {
                parent = document(context, parent, part, directoryMime)
            }
            suspend fun write(name: String, mime: String, content: String) {
                coroutineContext.ensureActive()
                val uri = document(context, parent, name, mime)
                context.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                } ?: error("Cannot write archive document.")
            }
            val prefix = "report_${report.end}"
            if (preferences.format != ReportExportFormat.CSV) write("$prefix.json", "application/json", report.toJson().toString(2))
            if (preferences.format != ReportExportFormat.JSON) write("$prefix.csv", "text/csv", reportCsv(report))
            val charts = listOf(report.latencyChart, report.lossChart, report.obstructionChart, report.trafficChart)
            val origins = charts.flatten().filter { it.value?.isFinite() == true }.map { it.origin }.toSet()
            val source = when {
                ReportBucketOrigin.MIXED in origins || (ReportBucketOrigin.LOCAL in origins && ReportBucketOrigin.BEACON in origins) -> "MIXED"
                ReportBucketOrigin.BEACON in origins -> "BEACON"
                ReportBucketOrigin.LOCAL in origins -> "LOCAL_PHONE"
                else -> "UNKNOWN"
            }
            val manifest = JSONObject().apply {
                @Suppress("DEPRECATION")
                put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                put("report_version", report.formatVersion)
                put("export_version", 1)
                put("period_start_ms", report.start); put("period_end_exclusive_ms", report.end)
                put("timezone", zone.id); put("timezone_basis", "export")
                put("source", source)
                put("generated_at_ms", report.createdAt); put("exported_at_ms", System.currentTimeMillis())
                put("coverage", JSONObject().apply {
                    listOf("latency", "loss", "obstruction", "download").zip(charts).forEach { (name, buckets) ->
                        val coverage = reportChartCoverage(buckets)
                        put(name, JSONObject().put("filled", coverage.filled).put("total", coverage.total)
                            .put("percent", coverage.percent.toDouble()).put("gaps", coverage.total - coverage.filled))
                    }
                })
            }
            write("$prefix.manifest.json", "application/json", manifest.toString(2))
            preferences.completed()
        }
    }
}

internal fun csvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

/** Open tabular format. Empty numeric cells represent missing data; genuine zero remains zero. */
internal fun reportCsv(report: DailyReport): String = buildString {
    append("metric,interval_start_ms,interval_end_exclusive_ms,value,sample_count,local_count,beacon_count,source\r\n")
    listOf("latency_ms" to report.latencyChart, "loss_percent" to report.lossChart,
        "obstruction_percent" to report.obstructionChart, "download_mbps" to report.trafficChart).forEach { (metric, buckets) ->
        buckets.forEach { b ->
            val source = when (b.origin) {
                ReportBucketOrigin.LOCAL -> "LOCAL_PHONE"
                ReportBucketOrigin.BEACON -> "BEACON"
                ReportBucketOrigin.MIXED -> "MIXED"
                ReportBucketOrigin.NONE -> "UNKNOWN"
            }
            append("$metric,${b.start},${b.end},${b.value?.takeIf { it.isFinite() } ?: ""},${b.sampleCount},${b.localCount},${b.beaconCount},$source\r\n")
        }
    }
    append("\r\nevent,timestamp_ms,title,detail\r\n")
    report.timeline.forEach { append("timeline,${it.timestamp},${csvCell(it.title)},${csvCell(it.detail)}\r\n") }
    append("\r\nmetric,count,minimum,maximum,average,median,p95,minimum_at_ms,maximum_at_ms\r\n")
    listOf("download_mbps" to report.trafficDownload, "upload_mbps" to report.trafficUpload,
        "latency_ms" to report.latency, "loss_percent" to report.packetLoss,
        "obstruction_percent" to report.obstruction).forEach { (name, metric) ->
        if (metric != null) {
            fun numeric(value: Float) = value.takeIf { it.isFinite() }?.toString().orEmpty()
            append("$name,${metric.count},${numeric(metric.minimum)},${numeric(metric.maximum)},${numeric(metric.average)},${numeric(metric.median)},${numeric(metric.p95)},${metric.minimumAt},${metric.maximumAt}\r\n")
        }
    }
}
