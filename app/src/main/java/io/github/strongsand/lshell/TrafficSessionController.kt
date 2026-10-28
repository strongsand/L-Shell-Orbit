package io.github.strongsand.lshell

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Shared by UI and notification actions. Atomic metadata, never analytical results. */
internal class TrafficSessionController private constructor(private val context: Context) {
    private val store = TrafficSessionStore(context)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<TrafficSessionActivity>(TrafficSessionActivity.Inactive)
    val state = _state.asStateFlow()
    private val checkpoints = context.getSharedPreferences("traffic_session_runtime", Context.MODE_PRIVATE)
    private fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    suspend fun start(name: String, mode: TrafficSessionMode, terminal: String?, beacon: Boolean): TrafficSession = mutex.withLock {
        if (!BackupGate.beginExecution()) throw BackupException(BackupException.Reason.BUSY)
        try {
            // Once metadata is written, leaving Compose must not cancel the service handoff.
            // Only short local IO and the start request are protected, never network work.
            withContext(NonCancellable) {
                TrafficSessionLifecycle.start(withContext(Dispatchers.IO) { store.read() }, create = {
                    withContext(Dispatchers.IO) { store.start(name, System.currentTimeMillis(),
                        ZoneId.systemDefault().id, terminal, beacon, mode, boot()) }
                }, startService = ::launch)
            }
        } finally { BackupGate.endExecution() }
    }
    suspend fun restoreFromVisibleApp() = mutex.withLock {
        val active = withContext(Dispatchers.IO) { store.read().firstOrNull { it.active } } ?: return@withLock
        if (TrafficSessionNotificationModel.requiresInterruption(active, boot())) {
            finishLocked(active, interrupted = true, now = checkpointEnd(active))
        } else if (_state.value !is TrafficSessionActivity.Active && _state.value !is TrafficSessionActivity.Starting) launch(active)
    }
    /** Also used by START_STICKY recovery; no background start attempt or boot receiver here. */
    suspend fun resolveForService(id: String?): TrafficSession? = mutex.withLock {
        val session = withContext(Dispatchers.IO) {
            TrafficSessionNotificationModel.restoreCandidate(store.read(), id)
        } ?: return@withLock null
        if (TrafficSessionNotificationModel.requiresInterruption(session, boot())) {
            finishLocked(session, interrupted = true, now = checkpointEnd(session)); null
        } else session
    }
    private fun checkpointEnd(session: TrafficSession): Long {
        val checkpoint = if (checkpoints.getString("id", null) == session.id)
            checkpoints.getLong("last_running_ms", session.startedAt) else session.startedAt
        return checkpoint.coerceIn(session.startedAt, maxOf(session.startedAt, System.currentTimeMillis()))
    }
    private fun launch(session: TrafficSession) {
        _state.value = TrafficSessionActivity.Starting(session)
        try {
            ContextCompat.startForegroundService(context, Intent(context, TrafficSessionService::class.java)
                .setAction(TrafficSessionService.ACTION_START).putExtra(TrafficSessionService.EXTRA_SESSION_ID, session.id))
        } catch (e: RuntimeException) {
            _state.value = TrafficSessionActivity.ErrorRecoverable(session)
            throw e
        }
    }
    suspend fun finish(id: String, interrupted: Boolean = false): TrafficSession? = mutex.withLock {
        val session = withContext(Dispatchers.IO) { store.read().firstOrNull { it.id == id } } ?: return@withLock null
        if (!session.active) return@withLock session
        finishLocked(session, interrupted, System.currentTimeMillis())
    }
    private suspend fun finishLocked(session: TrafficSession, interrupted: Boolean, now: Long): TrafficSession {
        _state.value = TrafficSessionActivity.Stopping(session)
        return try {
            // Save and stop form one bounded handoff even if the originating screen closes.
            withContext(NonCancellable) {
                TrafficSessionLifecycle.finish(session, now, interrupted, save = { ended ->
                    withContext(Dispatchers.IO) { store.finish(ended.id, ended.endedAt!!, ended.interrupted) }
                }, stopService = {
                    _state.value = TrafficSessionActivity.Completed(session.copy(endedAt = now, interrupted = interrupted))
                    context.stopService(Intent(context, TrafficSessionService::class.java))
                })
            }
        } catch (e: Exception) {
            _state.value = TrafficSessionActivity.ErrorRecoverable(session)
            throw e
        }
    }
    fun activated(session: TrafficSession) { _state.value = TrafficSessionActivity.Active(session) }
    fun serviceDestroyed(session: TrafficSession?) {
        if (_state.value is TrafficSessionActivity.Active && _state.value.session?.id == session?.id)
            failed(session)
    }
    fun failed(session: TrafficSession?) { _state.value = TrafficSessionActivity.ErrorRecoverable(session ?: _state.value.session) }
    fun checkpoint(session: TrafficSession) {
        checkpoints.edit().putString("id", session.id).putLong("last_running_ms", System.currentTimeMillis()).apply()
    }
    fun demoted(session: TrafficSession) = checkpoints.getString("demoted_id", null) == session.id
    fun demote(session: TrafficSession) { checkpoints.edit().putString("demoted_id", session.id).apply() }
    companion object {
        @Volatile private var instance: TrafficSessionController? = null
        fun get(context: Context): TrafficSessionController = instance ?: synchronized(this) {
            instance ?: TrafficSessionController(context.applicationContext).also { instance = it }
        }
    }
}
