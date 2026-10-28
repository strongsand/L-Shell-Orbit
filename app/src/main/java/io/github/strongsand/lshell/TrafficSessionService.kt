package io.github.strongsand.lshell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.Locale

/** Only a user-started, persisted traffic session holds this service. */
class TrafficSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller by lazy { TrafficSessionController.get(this) }
    private val runtime by lazy { TelemetryRuntime.get(this) }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private var job: Job? = null
    private var session: TrafficSession? = null
    private var collecting = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.session_channel),
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must promote before disk/network/coroutine work, including sticky recreation.
        try {
            val initial = notification(session)
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(ID, initial)
        } catch (_: RuntimeException) {
            controller.failed(session); showFailure(); stopSelf(); return START_NOT_STICKY
        }
        val action = intent?.action
        if (action == ACTION_STOP) {
            scope.launch {
                try {
                    val active = controller.resolveForService(intent.getStringExtra(EXTRA_SESSION_ID))
                    if (active != null) controller.finish(active.id) else if (session == null) stopSelf()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { controller.failed(session); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
            debug("ACTION_FINISH"); return START_NOT_STICKY
        }
        if (action == ACTION_DEMOTE) {
            session?.let { controller.demote(it); manager.notify(ID, notification(it)) }
        }
        if (job?.isActive == true) return START_STICKY
        debug("SERVICE_START")
        job = scope.launch {
            try {
                val active = controller.resolveForService(intent?.getStringExtra(EXTRA_SESSION_ID))
                    ?: run { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@launch }
                session = active
                if (action == ACTION_DEMOTE) controller.demote(active)
                controller.activated(active)
                runtime.acquire(TelemetryLeases.SESSION, 1000L); collecting = true
                withContext(Dispatchers.IO) { controller.checkpoint(active) }
                var nextCheckpoint = System.currentTimeMillis() + 30000
                var failedSince = 0L
                while (isActive) {
                    manager.notify(ID, notification(active))
                    val now = System.currentTimeMillis()
                    if (runtime.storageFailure.value) {
                        if (failedSince == 0L) failedSince = now
                        if (now - failedSince >= 30000) {
                            showFailure()
                            controller.finish(active.id, interrupted = true)
                            break
                        }
                    } else failedSince = 0L
                    if (now >= nextCheckpoint) {
                        withContext(Dispatchers.IO) { controller.checkpoint(active) }
                        nextCheckpoint = now + 30000
                    }
                    delay(TrafficSessionNotificationModel.UPDATE_MS)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                controller.failed(session)
                stopForeground(STOP_FOREGROUND_REMOVE)
                showFailure()
                stopSelf()
            }
        }
        // Persisted one-active-session metadata + boot count make recreation safe.
        return START_STICKY
    }
    private fun openIntent(active: TrafficSession?): PendingIntent = PendingIntent.getActivity(this, 4100,
        Intent(this, MainActivity::class.java).setAction(ACTION_OPEN)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open_tab", "TRAFFIC").putExtra(EXTRA_SESSION_ID, active?.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun actionIntent(action: String, active: TrafficSession): PendingIntent = PendingIntent.getService(this,
        if (action == ACTION_STOP) 4101 else 4102, Intent(this, TrafficSessionService::class.java)
            .setAction(action).putExtra(EXTRA_SESSION_ID, active.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(active: TrafficSession?): Notification {
        val title = getString(R.string.traffic_session)
        val reading = active?.let { TrafficSessionNotificationModel.reading(runtime.repository.telemetryFlow.value,
            System.currentTimeMillis(), it.mode, runtime.repository.livePower.value) } ?: TrafficNotificationReading(null, null)
        fun metric(value: Float?) = value?.let { String.format(Locale.getDefault(), "%.1f Mbps", it) } ?: "—"
        fun watts(value: Float?) = value?.let { String.format(Locale.getDefault(), "%.1f W", it) } ?: "—"
        val metrics = listOfNotNull(
            if (active?.mode == TrafficSessionMode.DOWNLOAD || active?.mode == TrafficSessionMode.BOTH) "↓ ${metric(reading.download)}" else null,
            if (active?.mode == TrafficSessionMode.UPLOAD || active?.mode == TrafficSessionMode.BOTH) "↑ ${metric(reading.upload)}" else null,
            if (active?.mode == TrafficSessionMode.ENERGY || active?.mode == TrafficSessionMode.BOTH) "⚡ ${watts(reading.power)}" else null).joinToString(" • ")
        val text = when {
            runtime.storageFailure.value -> getString(R.string.session_storage_error)
            !reading.hasData -> getString(R.string.session_waiting)
            else -> metrics
        }
        val public = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_session_notification)
            .setContentTitle(title).setContentText(getString(R.string.traffic_session_active)).build()
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_session_notification)
            .setContentTitle(active?.name ?: title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent(active)).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH).setLocalOnly(true)
            .setWhen(active?.startedAt ?: System.currentTimeMillis()).setUsesChronometer(active != null)
            .setShowWhen(true).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (active != null) {
            builder.addAction(0, getString(R.string.traffic_finish), actionIntent(ACTION_STOP, active))
                .addAction(0, getString(R.string.session_open), openIntent(active))
            if (Build.VERSION.SDK_INT >= 36 && !controller.demoted(active)) {
                builder.addAction(0, getString(R.string.session_unpin), actionIntent(ACTION_DEMOTE, active))
                    .setDeleteIntent(actionIntent(ACTION_DEMOTE, active))
            }
        }
        val normal = builder.build()
        val variant = TrafficSessionNotificationModel.notificationVariant(Build.VERSION.SDK_INT)
        if (active == null || variant == "big_text" || Build.VERSION.SDK_INT < 36) return normal
        return try {
            val promoted = LiveNotification.apply(this, normal, active, reading, !controller.demoted(active))
            if (debugEnabled) debug("PROMOTION_ELIGIBLE=${promoted.hasPromotableCharacteristics()} ALLOWED=${manager.canPostPromotedNotifications()}")
            promoted
        } catch (_: LinkageError) { normal }
        catch (_: RuntimeException) { normal }
    }
    private fun showFailure() {
        runCatching { manager.notify(ID + 1, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_session_notification).setContentTitle(getString(R.string.traffic_session))
            .setContentText(getString(R.string.session_failure)).setContentIntent(openIntent(session))
            .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()) }
    }
    override fun onDestroy() {
        job?.cancel(); scope.cancel()
        if (collecting) runtime.release(TelemetryLeases.SESSION)
        controller.serviceDestroyed(session)
        stopForeground(STOP_FOREGROUND_REMOVE)
        debug("SERVICE_STOP")
        super.onDestroy()
    }
    private val debugEnabled get() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private fun debug(message: String) { if (debugEnabled) Log.d("TrafficSession", message) }
    companion object {
        const val ACTION_START = "io.github.strongsand.lshell.traffic.START"
        const val ACTION_STOP = "io.github.strongsand.lshell.traffic.STOP"
        const val ACTION_OPEN = "io.github.strongsand.lshell.traffic.OPEN"
        const val ACTION_DEMOTE = "io.github.strongsand.lshell.traffic.DEMOTE"
        const val EXTRA_SESSION_ID = "traffic_session_id"
        private const val CHANNEL = "traffic_sessions"
        private const val ID = 4100
    }
}

/** Framework APIs verified against installed SDK 37; legacy Core 1.15 needs no update. */
@RequiresApi(36)
private object LiveNotification {
    fun apply(service: Service, normal: Notification, session: TrafficSession,
        reading: TrafficNotificationReading, promote: Boolean): Notification {
        val builder = Notification.Builder.recoverBuilder(service, normal)
        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1)
            builder.setRequestPromotedOngoing(promote)
        TrafficSessionNotificationModel.chip(reading)?.let { builder.setShortCriticalText(it) }
        if (Build.VERSION.SDK_INT >= 37 && reading.hasData) MetricsNotification.apply(builder, session, reading)
        return builder.build()
    }
}
@RequiresApi(37)
private object MetricsNotification {
    fun apply(builder: Notification.Builder, session: TrafficSession, reading: TrafficNotificationReading) {
        val style = Notification.MetricStyle()
        reading.download?.let { style.addMetric(Notification.Metric(Notification.Metric.FixedFloat(it, "Mbps"),
            "↓")) }
        reading.upload?.let { style.addMetric(Notification.Metric(Notification.Metric.FixedFloat(it, "Mbps"),
            "↑")) }
        // The long overload uses elapsedRealtime; persisted startedAt is Unix time.
        style.addMetric(Notification.Metric(Notification.Metric.TimeDifference.forStopwatch(java.time.Instant.ofEpochMilli(session.startedAt),
            Notification.Metric.TimeDifference.FORMAT_CHRONOMETER), "⏱"))
        style.setCriticalMetric(TrafficSessionNotificationModel.criticalMetricIndex(reading))
        builder.setStyle(style)
    }
}
