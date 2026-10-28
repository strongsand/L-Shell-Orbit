package io.github.strongsand.lshell

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.strongsand.lshell.beacon.BeaconRepository
import kotlinx.coroutines.*

/** Lifetime/notification adapter for the existing manual sync, never an additional sync engine. */
class BeaconSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var repository: BeaconRepository? = null
    private var job: Job? = null
    private var updates: Job? = null
    private var requestId = 0L
    private var dismissed = false
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BeaconSyncNotification.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, 0L) ?: 0L
        if (intent?.action == ACTION_DEMOTE) {
            if (job?.isActive == true && requestId == id && !finished) {
                dismissed = true
                post(BeaconSyncNotification.active(this, repository?.state?.value, id, dismissed))
            }
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        if (intent?.action != ACTION_START || id <= 0L) { stopSelf(); return START_NOT_STICKY }
        requestId = id
        dismissed = false
        finished = false
        // Enter foreground before initializing/reading any repository or database.
        try {
            val initial = BeaconSyncNotification.active(this, null, id, dismissed)
            if (Build.VERSION.SDK_INT >= 29) startForeground(BeaconSyncNotification.ID, initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(BeaconSyncNotification.ID, initial)
        } catch (_: RuntimeException) {
            val repo = BeaconRepository.create(this)
            repo.manualSyncStartFailed(id)
            repo.close()
            postStartFailure(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val repo = repository ?: BeaconRepository.create(this).also { repository = it }
        if (!repo.hasManualSync(id)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        updates = scope.launch {
            var previous: Any? = null
            while (isActive) {
                val state = repo.state.value
                if (state != previous && !finished) {
                    post(BeaconSyncNotification.active(this@BeaconSyncService, state, id, dismissed))
                    previous = state
                }
                delay(BeaconSyncNotificationPolicy.UPDATE_INTERVAL_MS)
            }
        }
        job = scope.launch {
            try { repo.executeManualSync(id, ::finish) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RuntimeException) { finish(false) }
            finally {
                updates?.cancel()
                if (!finished) finish(false)
                stopSelf()
            }
        }
        // A manual transfer is not replayed after process death/reboot/force stop.
        // Already persisted pages/cursors remain recoverable by the existing sync manager.
        return START_NOT_STICKY
    }

    private fun finish(success: Boolean) {
        if (finished) return
        finished = true
        updates?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        post(BeaconSyncNotification.finished(this, success))
    }

    private fun post(notification: Notification) {
        try { getSystemService(NotificationManager::class.java).notify(BeaconSyncNotification.ID, notification) }
        catch (_: RuntimeException) { /* Blocked notifications never invalidate a durable sync. */ }
    }

    override fun onDestroy() {
        job?.cancel()
        updates?.cancel()
        if (!finished) repository?.manualSyncStartFailed(requestId)
        scope.cancel()
        if (!finished) stopForeground(STOP_FOREGROUND_REMOVE)
        // Cancellation can arrive during an IO transaction. Release the final owner
        // only once the shared engine has unwound, never close its DB underneath it.
        repository?.let { repo ->
            val running = job
            if (running != null) running.invokeOnCompletion { repo.close() } else repo.close()
        }
        repository = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "io.github.strongsand.lshell.beacon.MANUAL_SYNC"
        const val ACTION_DEMOTE = "io.github.strongsand.lshell.beacon.DEMOTE_SYNC"
        const val EXTRA_ID = "beacon_sync_request_id"

        internal fun postStartFailure(context: Context) {
            try {
                BeaconSyncNotification.ensureChannel(context)
                context.getSystemService(NotificationManager::class.java).notify(BeaconSyncNotification.ID,
                    BeaconSyncNotification.finished(context, success = false))
            } catch (_: RuntimeException) { /* Respect the user's notification/channel settings. */ }
        }
    }
}
