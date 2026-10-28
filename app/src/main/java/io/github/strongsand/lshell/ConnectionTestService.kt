package io.github.strongsand.lshell

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/** User-initiated local equipment test; never a permanent test/polling engine. */
class ConnectionTestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller by lazy { ConnectionTestController.get(this) }
    private val runtime by lazy { TelemetryRuntime.get(this) }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private var job: Job? = null
    private var updates: Job? = null
    private var ownsTelemetry = false
    private var demoted = false
    private var executionId = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.test_channel),
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = controller.state.value
        val id = intent?.getLongExtra(EXTRA_ID, 0L) ?: 0L
        if (intent?.action == ACTION_STOP) {
            if (ConnectionTestPresentation.matchesStop(state, id) && executionId == id) job?.cancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DEMOTE) {
            if (executionId == id && state.active) { demoted = true; post(notification(state)) }
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        if (!state.active || state.id != id || intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        executionId = id
        demoted = false
        manager.cancel(RESULT_ID) // Old results must not deep-link to a different execution.
        try {
            val initial = notification(state)
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(ID, initial)
        } catch (_: RuntimeException) { controller.failedToStart(id); stopSelf(); return START_NOT_STICKY }
        // Extreme rounds need the same snapshots as the visible Activity. A normal
        // router test needs no additional Dishy polling after the UI leaves.
        try {
            if (state.kind == ConnectionTestKind.EXTREME) { runtime.acquire(OWNER, 2000L); ownsTelemetry = true }
        }
        catch (_: RuntimeException) {
            runtime.release(OWNER); controller.failedToStart(id)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        debug("TEST_SERVICE_START")
        updates = scope.launch {
            var previous = notificationKey(controller.state.value)
            while (isActive) {
                delay(ConnectionTestPresentation.NOTIFICATION_INTERVAL_MS)
                val next = controller.state.value
                val key = notificationKey(next)
                if (next.active && key != previous) { post(notification(next)); previous = key }
            }
        }
        job = scope.launch {
            try { controller.execute(id) }
            finally {
                updates?.cancel()
                releaseTelemetry()
                stopForeground(STOP_FOREGROUND_REMOVE)
                val final = controller.state.value
                if (final.id == id && !final.active) post(notification(final, finished = true), RESULT_ID)
                debug("TEST_SERVICE_STOP")
                // Stop/demote notification actions also receive a new startId.
                stopSelf()
            }
        }
        // Never replay a remote speed test after process death. Restore marks it interrupted.
        return START_NOT_STICKY
    }
    private fun notificationKey(s: ConnectionTestState) = listOf(s.phase, s.completed, s.total, s.result, s.liveActivity)
    private fun open(state: ConnectionTestState): PendingIntent {
        val target = ConnectionTestNotificationTarget.from(state)
        return PendingIntent.getActivity(this, 5200,
        Intent(this, MainActivity::class.java).setAction(ACTION_OPEN)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(android.net.Uri.parse(target.uri))
            .putExtra("open_tab", target.tab)
            .putExtra(EXTRA_ID, target.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun action(state: ConnectionTestState, action: String) = PendingIntent.getService(this,
        if (action == ACTION_STOP) 5202 else 5203, Intent(this, ConnectionTestService::class.java)
            .setAction(action).setData(android.net.Uri.parse(
                ConnectionTestNotificationTarget.from(state).uri + "/" + action.substringAfterLast('.')))
            .putExtra(EXTRA_ID, state.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification(state: ConnectionTestState, finished: Boolean = false): Notification {
        if (finished) return ConnectionTestResultNotification.build(this, CHANNEL, state, open(state))
        val title = getString(if (state.kind == ConnectionTestKind.NORMAL)
            R.string.function_speed else R.string.function_extreme)
        val detail = getString(ConnectionTestExperience.phaseLabel(state))
        val extreme = if (state.kind == ConnectionTestKind.EXTREME)
            extremeTestNotificationContent(this, state) else null
        val public = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_speed_test_notification)
            .setContentTitle(getString(R.string.test_channel)).build()
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_speed_test_notification)
            .setContentTitle(extreme?.title ?: title).setContentText(extreme?.compact ?: detail)
            // Native accessibility announcement: some system templates strip TTS spans.
            .setTicker(extreme?.spoken ?: "$title. $detail")
            .setContentIntent(open(state)).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setLocalOnly(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setWhen(state.startedAt).setShowWhen(true).setUsesChronometer(true)
            .setAutoCancel(false).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (extreme != null) builder.setStyle(NotificationCompat.BigTextStyle().bigText(extreme.expanded))
        builder.addAction(0, getString(R.string.test_stop), action(state, ACTION_STOP))
        builder.addAction(0, getString(R.string.test_open), open(state))
        if (Build.VERSION.SDK_INT >= 36) builder.setDeleteIntent(action(state, ACTION_DEMOTE))
        val progress = ExtremeTestNotificationModel.progress(state)
        if (TestTransferPresentation.nativeIndeterminate(state)) builder.setProgress(0, 0, true)
        else if (progress != null) builder.setProgress(progress.first, progress.second, false)
        else if (state.kind == ConnectionTestKind.EXTREME) builder.setProgress(0, 0, true)
        val normal = builder.build()
        if (Build.VERSION.SDK_INT < 36) return normal
        return ConnectionTestLiveUpdate.apply(this, normal, state, !demoted)
    }
    private fun post(notification: Notification, id: Int = ID) {
        try { manager.notify(id, notification) } catch (_: SecurityException) { }
    }
    private fun releaseTelemetry() { if (ownsTelemetry) { runtime.release(OWNER); ownsTelemetry = false } }
    override fun onDestroy() { job?.cancel(); updates?.cancel(); scope.cancel(); releaseTelemetry(); super.onDestroy() }
    private fun debug(value: String) { if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        Log.d("ConnectionTest", value) }
    companion object {
        const val CHANNEL = "connection_tests"
        const val ACTION_START = "io.github.strongsand.lshell.test.START"
        const val ACTION_STOP = "io.github.strongsand.lshell.test.STOP"
        const val ACTION_DEMOTE = "io.github.strongsand.lshell.test.DEMOTE"
        const val ACTION_OPEN = "io.github.strongsand.lshell.test.OPEN"
        const val EXTRA_ID = "connection_test_id"
        private const val OWNER = "ui:connection_test"
        private const val ID = 5200
        internal const val RESULT_ID = 5201
    }
}

