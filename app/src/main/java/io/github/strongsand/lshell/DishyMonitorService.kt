package io.github.strongsand.lshell

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.strongsand.lshell.core.model.DishState
import io.github.strongsand.lshell.core.model.DishySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class DishyMonitorService : Service() {
    companion object {
        const val ACTION_START = "io.github.strongsand.lshell.monitor.START"
        const val ACTION_STOP = "io.github.strongsand.lshell.monitor.STOP"
        const val ACTION_TEST = "io.github.strongsand.lshell.monitor.TEST"
        private const val CHANNEL_MONITOR = "dish_monitor"
        private const val CHANNEL_ALERT = "dish_alert"
        private const val CHANNEL_ALERT_SILENT = "dish_alert_silent"
        private const val MONITOR_NOTIFICATION_ID = 1001

        fun start(context: android.content.Context) {
            ContextCompat.startForegroundService(context, Intent(context, DishyMonitorService::class.java).setAction(ACTION_START))
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, DishyMonitorService::class.java).setAction(ACTION_STOP))
        }

        fun testNotification(context: android.content.Context) {
            ContextCompat.startForegroundService(context, Intent(context, DishyMonitorService::class.java).setAction(ACTION_TEST))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtime by lazy { TelemetryRuntime.get(applicationContext) }
    private lateinit var history: HistoryStore
    private var pollingJob: Job? = null
    private var notificationJob: Job? = null
    private var seenOnlineForNotification = false
    private var lastOnline: Boolean? = null
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        history = HistoryStore(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !MonitorPreferences.enabled(this)) {
            MonitorPreferences.setEnabled(this, false)
            pollingJob?.cancel()
            notificationJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = monitorNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(MONITOR_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(MONITOR_NOTIFICATION_ID, notification)
        }
        if (intent?.action == ACTION_TEST) {
            showAlerts(setOf(getString(R.string.ui_notification_test_ok)), test = true)
            if (pollingJob?.isActive == true) return START_STICKY
        }

        pollingJob?.cancel()
        notificationJob?.cancel()
        runtime.acquire(TelemetryLeases.MONITOR, MonitorPreferences.intervalSeconds(this) * 1000L)
        pollingJob = scope.launch {
            var nextCheckAt = 0L
            runtime.repository.telemetryFlow.collect { snapshot ->
                val now = System.currentTimeMillis()
                if (snapshot.timestamp == 0L || now < nextCheckAt) return@collect
                nextCheckAt = now + MonitorPreferences.intervalSeconds(this@DishyMonitorService) * 1000L
                try {
                    if (snapshot.isOnline && !snapshot.isFromCache) {
                        if (lastOnline == false) history.addEvent("Conexão com a antena restabelecida")
                        lastOnline = true
                        MonitorPreferences.reportSuccess(this@DishyMonitorService)
                        val active = activeConditions(snapshot)
                        val previousAlerts = MonitorPreferences.lastAlerts(this@DishyMonitorService)
                        val newAlerts = active - previousAlerts
                        val resolvedAlerts = previousAlerts - active
                        MonitorPreferences.setLastAlerts(this@DishyMonitorService, active)
                        if (newAlerts.isNotEmpty()) {
                            newAlerts.forEach { history.addEvent("Alerta: $it", snapshot.timestamp) }
                            showAlerts(newAlerts)
                        }
                        resolvedAlerts.forEach { history.addEvent("Alerta resolvido: $it", snapshot.timestamp) }
                    } else {
                        MonitorPreferences.reportFailure(this@DishyMonitorService,
                            snapshot.errorMessage ?: "Sem resposta da antena")
                        if (lastOnline == true) {
                            history.addEvent("Conexão com a antena perdida")
                            showAlerts(setOf("Conexão com a antena perdida"))
                        }
                        lastOnline = false
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    MonitorPreferences.reportFailure(this@DishyMonitorService, e.message ?: "Falha na coleta")
                }
            }
        }
        notificationJob = scope.launch {
            var previous: MonitorReading? = null
            while (isActive) {
                val snapshot = runtime.repository.telemetryFlow.value
                if (snapshot.isOnline && !snapshot.isFromCache) seenOnlineForNotification = true
                val reading = MonitorNotificationModel.reading(snapshot, System.currentTimeMillis(),
                    MonitorPreferences.intervalSeconds(this@DishyMonitorService), seenOnlineForNotification)
                if (reading != previous) {
                    try { notificationManager.notify(MONITOR_NOTIFICATION_ID, monitorNotification(reading)) }
                    catch (_: SecurityException) { }
                    previous = reading
                }
                delay(MonitorNotificationModel.UPDATE_MS)
            }
        }
        return START_STICKY
    }

    private fun activeConditions(snapshot: DishySnapshot): Set<String> = buildSet {
        addAll(snapshot.alerts.getActiveAlertsList())
        if (snapshot.obstruction.currentlyObstructed || snapshot.state == DishState.OBSTRUCTED)
            add("Antena obstruída agora")
        when (snapshot.state) {
            DishState.NO_DOWNLINK -> add("Sem enlace de descida")
            DishState.NO_PINGS -> add("Sem resposta de rede")
            else -> Unit
        }
        if (snapshot.latency.popPingDropRate >= 0.25f)
            add("Perda elevada de pacotes na conexão")
    }

    private fun createChannels() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, getString(R.string.editorial_monitor_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.editorial_monitor_channel_detail)
                setSound(null, null)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, getString(R.string.editorial_alert_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT_SILENT, getString(R.string.editorial_silent_alert_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun monitorNotification(reading: MonitorReading = MonitorReading(MonitorConnection.NO_DATA)): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, DishyMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val status = getString(when (reading.connection) {
            MonitorConnection.CONNECTED -> R.string.monitor_connected
            MonitorConnection.NO_DATA -> R.string.monitor_no_data
            MonitorConnection.RECONNECTING -> R.string.monitor_reconnecting
            MonitorConnection.UNAVAILABLE -> R.string.monitor_unavailable
        })
        val metrics = monitorMetricText(this, reading)
        val text = if (reading.connection == MonitorConnection.CONNECTED) metrics else status
        val public = NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_session_notification).setContentTitle(getString(R.string.editorial_monitor_title))
            .setContentText(status).build()
        val normal = NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_session_notification)
            .setContentTitle(getString(R.string.editorial_monitor_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(status, metrics).joinToString("\n")))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setLocalOnly(true).setSilent(true)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.editorial_stop_monitor), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return applyMonitorMetricStyle(this, normal, reading)
    }

    private fun showAlerts(alerts: Set<String>, test: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val silent = MonitorPreferences.silent(this)
        val notification = NotificationCompat.Builder(this, if (silent) CHANNEL_ALERT_SILENT else CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification_warning)
            .setContentTitle(if (test) getString(R.string.ui_notification_test) else resources.getQuantityString(R.plurals.ui_dish_alerts, alerts.size, alerts.size))
            .setContentText(alerts.joinToString(" · ") { telemetryText(it) }.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(alerts.joinToString("\n") { telemetryText(it) }))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setSilent(silent)
            .build()
        try {
            notificationManager.notify((System.currentTimeMillis() / 1000L).toInt(), notification)
        } catch (_: SecurityException) { }
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        pollingJob?.cancel()
        scope.cancel()
        runtime.release(TelemetryLeases.MONITOR)
        if (this::history.isInitialized) history.close()
        super.onDestroy()
    }
}
