package com.hurricane.lshell

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
import com.hurricane.lshell.core.network.grpc.DishyGrpcClient
import com.hurricane.lshell.core.model.DishState
import com.hurricane.lshell.core.model.DishySnapshot
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
        const val ACTION_START = "com.hurricane.lshell.monitor.START"
        const val ACTION_STOP = "com.hurricane.lshell.monitor.STOP"
        const val ACTION_TEST = "com.hurricane.lshell.monitor.TEST"
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
    private val client = DishyGrpcClient()
    private lateinit var history: HistoryStore
    private var pollingJob: Job? = null
    private var lastOnline: Boolean? = null
    private var lastHistoryFetchAt = 0L
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
            showAlerts(setOf("As notificações estão funcionando."), test = true)
            if (pollingJob?.isActive == true) return START_STICKY
        }

        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                try {
                client.fetchStatus().onSuccess { snapshot ->
                    history.addReading(snapshot)
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
                    if (System.currentTimeMillis() - lastHistoryFetchAt >= 60_000L) {
                        lastHistoryFetchAt = System.currentTimeMillis()
                        client.fetchHistory().onSuccess { data ->
                            val previousBoot = MonitorPreferences.historyBoot(this@DishyMonitorService)
                            val candidateBoot = if (snapshot.deviceInfo.uptimeSeconds > 0L)
                                System.currentTimeMillis() / 1_000L - snapshot.deviceInfo.uptimeSeconds
                                else previousBoot.coerceAtLeast(0L)
                            val previousCounter = MonitorPreferences.historyCounter(this@DishyMonitorService)
                            val sameBoot = previousCounter < data.current && kotlin.math.abs(candidateBoot - previousBoot) <= 60L
                            val boot = if (sameBoot) previousBoot else candidateBoot
                            val cursor = if (sameBoot)
                                previousCounter else -1L
                            history.importTerminalHistory(data, cursor, boot)
                            if (data.current > 0L && minOf(data.downloadMbps.size, data.uploadMbps.size,
                                    data.latencyMs.size, data.dropPercent.size) > 0) {
                                MonitorPreferences.setHistoryCursor(this@DishyMonitorService, data.current - 1, boot)
                            }
                        }
                    }
                }.onFailure { failure ->
                    MonitorPreferences.reportFailure(this@DishyMonitorService,
                        failure.message ?: "Sem resposta da antena")
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
                delay(MonitorPreferences.intervalSeconds(this@DishyMonitorService) * 1_000L)
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
            NotificationChannel(CHANNEL_MONITOR, "Monitoramento da antena", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mostra quando o monitoramento em segundo plano está ativo"
                setSound(null, null)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Alertas da antena", NotificationManager.IMPORTANCE_DEFAULT)
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT_SILENT, "Alertas silenciosos da antena", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun monitorNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, DishyMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("L-Shell Orbit · monitorando a antena")
            .setContentText("Consulta a cada ${MonitorPreferences.intervalSeconds(this)} s · toque para abrir")
            .setContentIntent(openAppIntent())
            .addAction(0, "Parar", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun showAlerts(alerts: Set<String>, test: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val silent = MonitorPreferences.silent(this)
        val notification = NotificationCompat.Builder(this, if (silent) CHANNEL_ALERT_SILENT else CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(if (test) "Teste de notificação" else if (alerts.size == 1) "Alerta da antena Starlink" else "${alerts.size} alertas da antena")
            .setContentText(alerts.joinToString(" · ").take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(alerts.joinToString("\n")))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setSilent(silent)
            .build()
        try {
            notificationManager.notify((System.currentTimeMillis() / 1000L).toInt(), notification)
        } catch (_: SecurityException) { }
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        client.close()
        if (this::history.isInitialized) history.close()
        super.onDestroy()
    }
}
