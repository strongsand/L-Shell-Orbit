package io.github.strongsand.lshell

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** Single process-wide execution state; a service or admitted worker, never a screen, owns the job. */
internal class ConnectionTestController private constructor(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.filesDir, "connection_test_execution.json"))
    private val _state = MutableStateFlow(restore())
    val state = _state.asStateFlow()
    private var automaticOwner: Pair<Long, Job>? = null

    /** WorkManager already owns foreground execution; never start a user-only FGS from a worker. */
    @Synchronized fun reserveAutomatic(owner: Job, origin: TestOrigin): Long? {
        if (!BackupGate.beginExecution()) return null
        try {
            require(origin == TestOrigin.SCHEDULED || origin == TestOrigin.SMART)
            if (automaticOwner != null || !ConnectionTestPresentation.canStart(state.value)) return null
            val now = System.currentTimeMillis()
            val next = ConnectionTestState(id = now, startedAt = now, kind = ConnectionTestKind.NORMAL,
                total = 1, phase = ConnectionTestPhase.STARTING, origin = origin)
            save(next) // Durable reservation before the RPC. Recovery remains INTERRUPTED, never replay.
            automaticOwner = next.id to owner
            _state.value = next
            return next.id
        } finally { BackupGate.endExecution() }
    }

    @Synchronized fun releaseAutomatic(id: Long) {
        if (automaticOwner?.first == id) automaticOwner = null
    }

    @Synchronized fun start(kind: ConnectionTestKind, rounds: Int) {
        if (!BackupGate.beginExecution()) return
        try {
            if (automaticOwner != null || !ConnectionTestPresentation.canStart(_state.value)) return
            val now = System.currentTimeMillis()
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled() ||
                app.getSystemService(android.app.NotificationManager::class.java)
                    .getNotificationChannel(ConnectionTestService.CHANNEL)?.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
                _state.value = ConnectionTestState(kind = kind, phase = ConnectionTestPhase.FAILED,
                    error = app.getString(R.string.test_notifications_required)); return
            }
            val next = ConnectionTestState(id = now, startedAt = now, kind = kind,
                total = if (kind == ConnectionTestKind.NORMAL) 1 else rounds.coerceIn(5, 15), phase = ConnectionTestPhase.STARTING,
                origin = if (kind == ConnectionTestKind.EXTREME) TestOrigin.EXTREME else TestOrigin.MANUAL)
            _state.value = next // Reserve synchronously before a second tap can launch a second RPC.
            try {
                save(next)
                ContextCompat.startForegroundService(app, Intent(app, ConnectionTestService::class.java)
                    .setAction(ConnectionTestService.ACTION_START).putExtra(ConnectionTestService.EXTRA_ID, next.id))
            } catch (_: Exception) {
                _state.value = next.copy(phase = ConnectionTestPhase.FAILED, endedAt = now,
                    error = app.getString(R.string.test_start_failed))
                runCatching { save(_state.value) }
            }
        } finally { BackupGate.endExecution() }
    }

    fun permissionDenied(kind: ConnectionTestKind) {
        if (!state.value.active) _state.value = ConnectionTestState(kind = kind, phase = ConnectionTestPhase.FAILED,
            error = app.getString(R.string.test_notifications_required))
    }
    fun stop(id: Long) {
        if (ConnectionTestPresentation.matchesStop(state.value, id)) {
            synchronized(this) {
                automaticOwner?.takeIf { it.first == id }?.let { it.second.cancel(); return }
            }
            try { app.startService(Intent(app, ConnectionTestService::class.java)
                .setAction(ConnectionTestService.ACTION_STOP).putExtra(ConnectionTestService.EXTRA_ID, id)) }
            catch (_: RuntimeException) { /* Existing job remains visible and can be stopped again. */ }
        }
    }
    fun failedToStart(id: Long) {
        if (_state.value.id == id) _state.value = _state.value.copy(phase = ConnectionTestPhase.FAILED,
            endedAt = System.currentTimeMillis(), error = app.getString(R.string.test_start_failed))
        if (_state.value.id == id) runCatching { save(_state.value) }
    }

    suspend fun execute(id: Long, onStarted: () -> Unit = {}) {
        val initial = state.value
        if (!initial.active || initial.id != id) return
        val runtime = TelemetryRuntime.get(app)
        try {
            update(initial.copy(phase = ConnectionTestPhase.RUNNING))
            onStarted() // Automatic quota is spent only once execution actually reaches RUNNING.
            if (initial.kind == ConnectionTestKind.NORMAL) {
                val result = SpeedTestClient.router { publishActivity(id, it) }
                update(state.value.copy(result = result, completed = 1, phase = ConnectionTestPhase.COMPLETED,
                    endedAt = System.currentTimeMillis()))
            } else {
                repeat(initial.total) { index ->
                    update(state.value.copy(phase = ConnectionTestPhase.RUNNING))
                    val runStarted = System.currentTimeMillis()
                    var roundResult: SpeedTestResult? = null
                    val run = try {
                        val result = SpeedTestClient.router { publishActivity(id, it) }
                        roundResult = result
                        val elapsed = (System.currentTimeMillis() - runStarted).coerceAtLeast(1L)
                        val after = runtime.repository.telemetryFlow.value
                        // Unchanged arithmetic from the existing extreme test screen.
                        val averageMbps = (result.downloadMbps + result.uploadMbps).coerceAtLeast(0f)
                        val estimatedBytes = (averageMbps * 1_000_000.0 / 8.0 * elapsed / 1_000.0).toLong()
                        ExtremeRun(index + 1, runStarted, result.downloadMbps, result.uploadMbps,
                            result.latencyMs ?: after.latency.popPingLatencyMs.takeIf { it > 0f },
                            after.latency.popPingDropRate * 100f, after.obstruction.obstructionPercentage,
                            after.state.name, after.alerts.getActiveAlertsList(), elapsed, estimatedBytes,
                            downloadMeasured = result.downloadMeasured, uploadMeasured = result.uploadMeasured)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val after = runtime.repository.telemetryFlow.value
                        ExtremeRun(index + 1, runStarted, 0f, 0f,
                            after.latency.popPingLatencyMs.takeIf { it > 0f },
                            after.latency.popPingDropRate * 100f, after.obstruction.obstructionPercentage,
                            after.state.name, after.alerts.getActiveAlertsList(),
                            (System.currentTimeMillis() - runStarted).coerceAtLeast(1L), 0L,
                            failure = e.message ?: app.getString(R.string.test_no_result))
                    }
                    update(state.value.copy(runs = state.value.runs + run, completed = index + 1, result = roundResult))
                    withContext(Dispatchers.IO) {
                        HistoryStore(app).use { it.addReading(runtime.repository.telemetryFlow.value) }
                    }
                    if (index < initial.total - 1) {
                        update(state.value.copy(phase = ConnectionTestPhase.BETWEEN_ROUNDS))
                        delay(1500L)
                    }
                }
                val session = ExtremeSession(initial.id, initial.total, initial.startedAt, System.currentTimeMillis(), state.value.runs)
                withContext(Dispatchers.IO) {
                    ExtremeSessionStore(app).save(session)
                    HistoryStore(app).use { history ->
                        val failed = session.runs.count { it.failure != null }
                        val peakLoss = session.runs.maxOfOrNull { it.packetLossPercent } ?: 0f
                        history.addEvent("Teste Extremo concluído: ${session.runs.size} testes" +
                            (if (failed > 0) " · $failed sem resultado" else "") +
                            (if (peakLoss >= 1f) " · perda máxima ${String.format(java.util.Locale.getDefault(), "%.1f", peakLoss)}%" else ""), session.endedAt)
                    }
                }
                update(state.value.copy(extreme = session, phase = ConnectionTestPhase.COMPLETED, endedAt = session.endedAt))
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { terminal(ConnectionTestPhase.INTERRUPTED, null) }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { terminal(ConnectionTestPhase.FAILED, e.message) }
        }
    }
    private suspend fun terminal(phase: ConnectionTestPhase, error: String?) {
        val next = state.value.copy(phase = phase, endedAt = System.currentTimeMillis(), error = error)
        // Preserve completed extreme rounds without fabricating the interrupted round.
        if (next.kind == ConnectionTestKind.EXTREME && next.runs.isNotEmpty()) withContext(Dispatchers.IO) {
            runCatching { ExtremeSessionStore(app).save(ExtremeSession(next.id, next.total, next.startedAt, next.endedAt, next.runs)) }
        }
        try { update(next) } catch (_: Exception) { _state.value = next }
    }
    private suspend fun update(next: ConnectionTestState) {
        val value = if (next.phase == ConnectionTestPhase.RUNNING) next else next.copy(liveActivity = SpeedTestActivity.UNKNOWN)
        withContext(Dispatchers.IO) { save(value) }
        _state.value = value
    }

    private fun publishActivity(id: Long, activity: SpeedTestActivity) {
        val current = _state.value
        if (current.id == id && current.phase == ConnectionTestPhase.RUNNING && current.liveActivity != activity)
            _state.value = current.copy(liveActivity = activity)
    }
    private fun save(state: ConnectionTestState) {
        val json = JSONObject().apply {
            put("id", state.id); put("kind", state.kind.name); put("phase", state.phase.name)
            put("origin", state.origin.name)
            put("start", state.startedAt); put("end", state.endedAt); put("total", state.total); put("completed", state.completed)
            state.error?.let { put("error", it) }
            state.result?.let { result -> put("result", JSONObject().apply {
                put("down", result.downloadMbps.toDouble()); put("up", result.uploadMbps.toDouble())
                put("down_measured", result.downloadMeasured); put("up_measured", result.uploadMeasured)
                result.latencyMs?.let { put("latency", it.toDouble()) }
            }) }
            if (state.runs.isNotEmpty()) put("extreme", ExtremeSession(state.id, state.total, state.startedAt, state.endedAt, state.runs).executionJson())
        }
        val out = file.startWrite()
        try { out.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
        // The latest execution file is authoritative for recovery; archiving must not fail a completed RPC.
        runCatching { TestHistory.save(app, state) }
            .onFailure { if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                android.util.Log.w("TestHistory", "Result archive deferred; latest execution preserved") }
    }
    /** Only an empty controller adopts historical terminal details; current results and active jobs win. */
    @Synchronized fun refreshRestoredHistory() {
        if (_state.value.id == 0L && !_state.value.active) _state.value = restore()
    }
    private fun restore(): ConnectionTestState = try {
        val hasCurrent = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
        val json = if (hasCurrent) file.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        else {
            val archived = File(app.filesDir, "restored_tests").listFiles().orEmpty()
                .filter { it.name.matches(Regex("result_[0-9]+\\.json")) }
                .maxByOrNull { it.name.removePrefix("result_").removeSuffix(".json").toLongOrNull() ?: 0L }
                ?: throw java.io.FileNotFoundException()
            require(archived.length() <= BackupLimits.MAX_LINE)
            JSONObject(archived.readText(Charsets.UTF_8)).also {
                require(it.getString("phase") in setOf("COMPLETED", "FAILED", "INTERRUPTED"))
            }
        }
        val result = json.optJSONObject("result")?.let { SpeedTestResult(it.optDouble("down").toFloat(),
            it.optDouble("up").toFloat(), it.optDouble("latency").takeIf { n -> n.isFinite() }?.toFloat(),
            it.optBoolean("down_measured", true), it.optBoolean("up_measured", true)) }
        val extreme = json.optJSONObject("extreme")?.let(::executionSessionFromJson)
        val stored = ConnectionTestState(json.getLong("id"), ConnectionTestKind.valueOf(json.getString("kind")),
            ConnectionTestPhase.valueOf(json.getString("phase")), json.getLong("start"), json.getLong("end"),
            json.getInt("total"), json.getInt("completed"), result, extreme?.runs.orEmpty(),
            extreme?.takeIf { json.getString("phase") == ConnectionTestPhase.COMPLETED.name }, json.optString("error").takeIf { it.isNotBlank() },
            runCatching { TestOrigin.valueOf(json.getString("origin")) }.getOrDefault(
                if (json.getString("kind") == ConnectionTestKind.EXTREME.name) TestOrigin.EXTREME else TestOrigin.UNKNOWN))
        ConnectionTestPresentation.recovered(stored, System.currentTimeMillis()).also { if (hasCurrent) save(it) }
    } catch (_: Exception) { ConnectionTestState() }

    companion object {
        @Volatile private var instance: ConnectionTestController? = null
        fun get(context: Context): ConnectionTestController = instance ?: synchronized(this) {
            instance ?: ConnectionTestController(context.applicationContext).also { instance = it }
        }
    }
}
