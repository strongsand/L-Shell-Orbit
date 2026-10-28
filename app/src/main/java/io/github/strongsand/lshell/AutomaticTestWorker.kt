package io.github.strongsand.lshell

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

internal object AutomaticTestSchedule {
    private const val LEGACY = "automatic_speed_tests"
    private const val WAKE = "automatic_speed_test_agenda"
    private const val SMART = "automatic_speed_test_smart_probe"
    private const val RUN = "automatic_speed_test_run"
    const val REVISION = "revision"
    const val PLANNED = "planned"
    const val SLOT = "slot"
    const val TRIGGER = "trigger"
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true).build()
    fun configure(context: Context, replace: Boolean = false) = synchronized(AutomaticTestPreferences.lock) {
        val preferences = AutomaticTestPreferences(context)
        val s = preferences.settings()
        preferences.ensureScheduledAnchor(System.currentTimeMillis())
        val state = ConnectionTestController.get(context).state.value
        preferences.reconcile(state)
        val work = WorkManager.getInstance(context)
        if (replace) preferences.invalidateSchedule()
        work.cancelUniqueWork(LEGACY)
        if (!s.scheduled && !s.smart) {
            work.cancelUniqueWork(WAKE); work.cancelUniqueWork(SMART); work.cancelUniqueWork(RUN)
            work.cancelUniqueWork(RUN + "_scheduled")
            return@synchronized
        }
        // Replace only the agenda. The single queued execution reads the NEW agenda on wake;
        // cancelling it here could race with foreground admission and interrupt a started test.
        if (s.scheduled) arm(context, preferences, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
        else { work.cancelUniqueWork(WAKE); work.cancelUniqueWork(RUN + "_scheduled") }
        if (s.smart) work.enqueueUniquePeriodicWork(SMART, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutomaticTestSchedulerWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints).build())
        else { work.cancelUniqueWork(SMART); work.cancelUniqueWork(RUN) }
    }
    fun arm(context: Context, prefs: AutomaticTestPreferences, policy: ExistingWorkPolicy,
            afterDispatch: Boolean = false): Operation {
        val now = System.currentTimeMillis()
        val settings = prefs.settings()
        var ledger = prefs.ledger()
        val slot = AutomaticTestPolicy.nextScheduled(settings, ledger, now)
        // The successor is queued before the current dispatcher exits. Never enqueue the same due slot in a loop.
        if (afterDispatch && slot.plannedAt <= now)
            ledger = ledger.copy(slots = ledger.slots + AutomaticSlotRecord(slot, AutomaticSlotStatus.NOT_EXECUTED))
        val next = AutomaticTestPolicy.nextScheduled(settings, ledger, now)
        val midnight = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return WorkManager.getInstance(context).enqueueUniqueWork(WAKE, policy,
            OneTimeWorkRequestBuilder<AutomaticTestAgendaWorker>()
                .setInitialDelay((minOf(next.plannedAt, midnight) - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(REVISION to prefs.revision(), PLANNED to next.plannedAt, SLOT to next.key)).build())
    }
    fun requestExecution(context: Context, trigger: AutomaticTestTrigger, revision: Long,
                         slot: AutomaticScheduledSlot? = null): Operation =
        WorkManager.getInstance(context).enqueueUniqueWork(if (trigger == AutomaticTestTrigger.SCHEDULED) RUN + "_scheduled" else RUN,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutomaticTestWorker>().setConstraints(constraints)
                .setInputData(workDataOf(TRIGGER to trigger.name, REVISION to revision,
                    PLANNED to (slot?.plannedAt ?: 0), SLOT to (slot?.key ?: -1))).build())
    fun rearm(context: Context, expectedRevision: Long): Operation? = synchronized(AutomaticTestPreferences.lock) {
        val prefs = AutomaticTestPreferences(context)
        if (!prefs.settings().scheduled || prefs.revision() != expectedRevision) null
        else arm(context, prefs, ExistingWorkPolicy.APPEND_OR_REPLACE, afterDispatch = true)
    }
}

/** No constraints here: always persist the next agenda wake, even while offline or battery-low. */
class AutomaticTestAgendaWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
        val prefs = AutomaticTestPreferences(applicationContext)
        val revision = inputData.getLong(AutomaticTestSchedule.REVISION, -1)
        val operation = synchronized(AutomaticTestPreferences.lock) {
            if (!prefs.settings().scheduled || prefs.revision() != revision) return@synchronized null
            val now = System.currentTimeMillis()
            val original = AutomaticScheduledSlot(inputData.getLong(AutomaticTestSchedule.SLOT, -1),
                inputData.getLong(AutomaticTestSchedule.PLANNED, 0))
            if (original.plannedAt > 0 && now >= AutomaticTestPolicy.deadline(prefs.settings(), original))
                prefs.skipped(original, revision)
            val slot = AutomaticTestPolicy.nextScheduled(prefs.settings(), prefs.ledger(), now)
            if (slot.plannedAt <= now) AutomaticTestSchedule.requestExecution(applicationContext,
                AutomaticTestTrigger.SCHEDULED, revision, slot) else null
        }
        operation?.await()
        AutomaticTestSchedule.rearm(applicationContext, revision)?.await()
        Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry() // Retry planning/queueing only; a consumed slot cannot repeat its RPC.
        }
    }
}

/** Smart probes remain periodic; the legacy persisted worker also repairs the new agenda on upgrade. */
class AutomaticTestSchedulerWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = AutomaticTestPreferences(applicationContext)
        AutomaticTestSchedule.configure(applicationContext)
        val state = ConnectionTestController.get(applicationContext).state.value
        val now = System.currentTimeMillis()
        val candidate = AutomaticTestPolicy.candidate(prefs.settings(), prefs.ledger(), now,
            AutomaticTestPolicy.localDay(now), state.active, state.startedAt,
            !applicationContext.getSystemService(PowerManager::class.java).isInteractive)
        if (candidate == AutomaticTestTrigger.SMART)
            AutomaticTestSchedule.requestExecution(applicationContext, candidate, prefs.revision()).await()
        Result.success()
    }
}

/** One bounded check, no polling loop or background start of ConnectionTestService. */
class AutomaticTestWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    internal companion object {
        const val NOTIFICATION_ID = 5500
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val prefs = AutomaticTestPreferences(app)
        val controller = ConnectionTestController.get(app)
        val queuedRevision = inputData.getLong(AutomaticTestSchedule.REVISION, -1)
        val requested = runCatching { AutomaticTestTrigger.valueOf(inputData.getString(AutomaticTestSchedule.TRIGGER).orEmpty()) }.getOrNull()
            ?: return@withContext Result.success() // Old queued requests never replay after upgrade.
        val revision = if (requested == AutomaticTestTrigger.SCHEDULED) prefs.revision() else queuedRevision
        val slot = if (requested == AutomaticTestTrigger.SCHEDULED) {
            val original = AutomaticScheduledSlot(inputData.getLong(AutomaticTestSchedule.SLOT, -1),
                inputData.getLong(AutomaticTestSchedule.PLANNED, 0))
            val at = System.currentTimeMillis()
            if (queuedRevision == revision && original.plannedAt > 0 &&
                at >= AutomaticTestPolicy.deadline(prefs.settings(), original)) prefs.skipped(original, revision)
            // One pending worker may have waited through several windows. It may run only the
            // latest valid current slot, never the old queued slot or an old configuration.
            AutomaticTestPolicy.nextScheduled(prefs.settings(), prefs.ledger(), at)
        } else null
        fun valid(): Boolean = prefs.revision() == revision && if (slot != null)
            AutomaticTestPolicy.validSlot(prefs.settings(), prefs.ledger(), slot, System.currentTimeMillis())
            else prefs.settings().smart
        fun skipped() { slot?.let { prefs.skipped(it, revision) } }
        if (!valid()) {
            if (slot != null && System.currentTimeMillis() >= AutomaticTestPolicy.deadline(prefs.settings(), slot)) skipped()
            return@withContext Result.success()
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        val network = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = network.getNetworkCapabilities(network.activeNetwork)
        if (capabilities == null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
            skipped(); return@withContext Result.success()
        }
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled() ||
            manager.getNotificationChannel(ConnectionTestService.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            skipped(); return@withContext Result.success()
        }
        val now = System.currentTimeMillis()
        val day = AutomaticTestPolicy.localDay(now)
        val settings = prefs.settings()
        val trigger = AutomaticTestPolicy.candidate(settings,
            prefs.ledger(), now, day, controller.state.value.active,
            controller.state.value.startedAt, !app.getSystemService(PowerManager::class.java).isInteractive)
        if (trigger != requested) { skipped(); return@withContext Result.success() }
        var sessionId: Long? = null
        try {
            // Validate actual LAN equipment before creating a test or transferring test traffic.
            DishyGrpcClient(timeoutMs = 4_000).use { client ->
                val status = client.fetchStatus().getOrNull()
                if (status?.isOnline != true || status.isFromCache) {
                    skipped(); prefs.outcome(app, trigger, R.string.auto_unavailable); return@withContext Result.success()
                }
                if (trigger == AutomaticTestTrigger.SMART) {
                    if (!prefs.probe(now)) return@withContext Result.failure()
                    prefs.evaluating(now)
                    val first = client.fetchHistory().getOrNull()
                    delay(2_000)
                    val history = client.fetchHistory().getOrNull()
                    if (first == null || history == null || history.current <= first.current ||
                        !AutomaticTestPolicy.lowUsage(history)) {
                        prefs.outcome(app, trigger, R.string.auto_not_idle); return@withContext Result.success()
                    }
                }
            }
            // Re-read opt-in immediately before reservation: disabling while probing cancels admission.
            val current = prefs.settings()
            if (!valid() || (trigger == AutomaticTestTrigger.SCHEDULED && !current.scheduled) ||
                (trigger == AutomaticTestTrigger.SMART && (!current.smart || app.getSystemService(PowerManager::class.java).isInteractive)))
                return@withContext if (slot != null && current.scheduled && prefs.revision() != revision) Result.retry() else Result.success()
            val admittedAt = System.currentTimeMillis()
            val admittedDay = AutomaticTestPolicy.localDay(admittedAt)
            // Ignore only this run's own probe timestamp; revalidate updated limits and clock/day.
            if (AutomaticTestPolicy.candidate(current,
                    prefs.ledger().copy(lastProbe = 0), admittedAt,
                    admittedDay, controller.state.value.active, controller.state.value.startedAt,
                    !app.getSystemService(PowerManager::class.java).isInteractive) != trigger)
                { skipped(); return@withContext Result.success() }
            manager.createNotificationChannel(NotificationChannel(ConnectionTestService.CHANNEL,
                app.getString(R.string.test_channel), NotificationManager.IMPORTANCE_LOW))
            // Foreground is provided by WorkManager, with the existing connected-device prerequisite.
            val owner = currentCoroutineContext().job
            val reserved = controller.reserveAutomatic(owner,
                if (trigger == AutomaticTestTrigger.SMART) TestOrigin.SMART else TestOrigin.SCHEDULED)
                ?: run { skipped(); return@withContext Result.success() }
            sessionId = reserved
            val open = PendingIntent.getActivity(app, NOTIFICATION_ID, Intent(app, MainActivity::class.java)
                .setAction(ConnectionTestService.ACTION_OPEN)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_tab", "SPEED").putExtra(ConnectionTestService.EXTRA_ID, reserved),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            fun activeNotification(state: ConnectionTestState): android.app.Notification {
                val normal = NotificationCompat.Builder(app, ConnectionTestService.CHANNEL)
                .setSmallIcon(R.drawable.ic_speed_test_notification)
                .setContentTitle(app.getString(if (trigger == AutomaticTestTrigger.SMART) R.string.auto_smart_title else R.string.auto_scheduled_title))
                .setContentText(app.getString(ConnectionTestExperience.phaseLabel(state))).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setWhen(now).setUsesChronometer(true)
                .setProgress(0, 0, true)
                .addAction(0, app.getString(R.string.test_stop), WorkManager.getInstance(app).createCancelPendingIntent(id)).build()
                return ConnectionTestLiveUpdate.apply(app, normal, state, true)
            }
            val notification = activeNotification(controller.state.value)
            setForeground(if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                else ForegroundInfo(NOTIFICATION_ID, notification))
            manager.cancel(ConnectionTestService.RESULT_ID)
            coroutineScope {
                val updates = launch {
                    var previous = controller.state.value.let { it.phase to it.liveActivity }
                    while (isActive) {
                        delay(ConnectionTestPresentation.NOTIFICATION_INTERVAL_MS)
                        val state = controller.state.value
                        if (state.id != reserved || !state.active) break
                        val next = state.phase to state.liveActivity
                        if (next != previous) {
                            runCatching { manager.notify(NOTIFICATION_ID, activeNotification(state)) }
                            previous = next
                        }
                    }
                }
                try {
                    controller.execute(reserved) {
                        val started = System.currentTimeMillis()
                        check(prefs.attempt(trigger, started, AutomaticTestPolicy.localDay(started), reserved, slot, revision))
                    } // Same RPC, result, cancellation and persistence as a manual test.
                } finally { updates.cancel() }
            }
            val result = controller.state.value
            prefs.outcome(app, trigger, if (result.phase == ConnectionTestPhase.COMPLETED) R.string.auto_completed else R.string.auto_failed, result)
            if (!isStopped && result.id == reserved && !result.active)
                runCatching { manager.notify(ConnectionTestService.RESULT_ID, ConnectionTestResultNotification.build(app, ConnectionTestService.CHANNEL, result, open)) }
        } catch (cancelled: CancellationException) {
            // execute() persists INTERRUPTED. Cancellation before execute still closes the reservation.
            sessionId?.let { if (controller.state.value.active) controller.failedToStart(it) }
            prefs.outcome(app, trigger, R.string.auto_interrupted)
            throw cancelled
        } catch (_: Exception) {
            sessionId?.let { if (controller.state.value.active) controller.failedToStart(it) }
            prefs.outcome(app, trigger, R.string.auto_failed)
        } finally {
            if (trigger == AutomaticTestTrigger.SMART) prefs.clearEvaluating()
            try { prefs.reconcile(controller.state.value) }
            finally { sessionId?.let(controller::releaseAutomatic) }
            if (slot != null && prefs.ledger().slots.none { it.slot.key == slot.key }) skipped()
        }
        Result.success() // No immediate retry storm and no replay of a remote speed test.
    }
}

/** Wall-clock changes rebuild only pending work; WorkManager itself persists/reloads work after reboot. */
class AutomaticTestRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AutomaticTestSchedule.configure(context, replace = true)
    }
}
