package io.github.strongsand.lshell

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.util.Calendar

enum class ReportRetention(val label: String, val days: Int?) {
    SEVEN("7 dias", 7), THIRTY("30 dias", 30), NINETY("90 dias", 90), ALWAYS("Sempre", null)
}

object DailyReportPreferences {
    private const val FILE = "daily_report_preferences"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, value: Boolean) {
        val preferences = prefs(context)
        val edit = preferences.edit().putBoolean("enabled", value)
        if (value && !preferences.getBoolean("enabled", false))
            edit.putLong("enabled_at", System.currentTimeMillis())
        edit.apply()
    }
    fun enabledAt(context: Context) = prefs(context).getLong("enabled_at", System.currentTimeMillis())
    fun hour(context: Context) = prefs(context).getInt("hour", 21).coerceIn(0, 23)
    fun minute(context: Context) = prefs(context).getInt("minute", 0).coerceIn(0, 59)
    fun setTime(context: Context, hour: Int, minute: Int) = prefs(context).edit()
        .putInt("hour", hour.coerceIn(0, 23)).putInt("minute", minute.coerceIn(0, 59)).apply()
    fun notify(context: Context) = prefs(context).getBoolean("notify", true)
    fun setNotify(context: Context, value: Boolean) = prefs(context).edit().putBoolean("notify", value).apply()
    fun keepHistory(context: Context) = prefs(context).getBoolean("history", true)
    fun setKeepHistory(context: Context, value: Boolean) = prefs(context).edit().putBoolean("history", value).apply()
    fun retention(context: Context): ReportRetention = runCatching {
        ReportRetention.valueOf(prefs(context).getString("retention", ReportRetention.THIRTY.name)!!)
    }.getOrDefault(ReportRetention.THIRTY)
    fun setRetention(context: Context, value: ReportRetention) = prefs(context).edit()
        .putString("retention", value.name).apply()
    fun lastEnd(context: Context) = prefs(context).getLong("last_end", 0L)
    fun setLastEnd(context: Context, value: Long) = prefs(context).edit().putLong("last_end", value).apply()
}

object DailyReportScheduler {
    private const val ACTION = "io.github.strongsand.lshell.CREATE_DAILY_REPORT"
    private const val REQUEST = 7312

    fun schedule(context: Context) {
        if (!DailyReportPreferences.enabled(context)) {
            cancel(context)
            return
        }
        val alarm = context.getSystemService(AlarmManager::class.java)
        val trigger = nextBoundary(context, System.currentTimeMillis())
        val pending = pendingIntent(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
            runCatching {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            }.getOrElse {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            }
        } else {
            // Exact-alarm access is optional. Android may defer this fallback slightly, while the
            // report still uses the intended boundary and never needs a polling loop.
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    fun catchUpOrSchedule(context: Context) {
        if (!DailyReportPreferences.enabled(context)) return
        val boundary = boundaryAtOrBefore(context, System.currentTimeMillis())
        val coveredUntil = maxOf(DailyReportPreferences.lastEnd(context),
            DailyReportPreferences.enabledAt(context))
        if (boundary > coveredUntil) {
            context.sendBroadcast(Intent(context, DailyReportReceiver::class.java).setAction(ACTION))
        } else schedule(context)
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    fun boundaryAtOrBefore(context: Context, now: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, DailyReportPreferences.hour(context))
            set(Calendar.MINUTE, DailyReportPreferences.minute(context))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis > now) add(Calendar.DAY_OF_YEAR, -1)
        }
        return calendar.timeInMillis
    }

    fun previousBoundary(boundary: Long): Long = Calendar.getInstance().apply {
        timeInMillis = boundary
        add(Calendar.DAY_OF_YEAR, -1)
    }.timeInMillis

    private fun nextBoundary(context: Context, now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, DailyReportPreferences.hour(context))
        set(Calendar.MINUTE, DailyReportPreferences.minute(context))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, DailyReportReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class DailyReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!DailyReportPreferences.enabled(context)) return
        val end = DailyReportScheduler.boundaryAtOrBefore(context, System.currentTimeMillis())
        val request = OneTimeWorkRequestBuilder<DailyReportWorker>()
            .setInputData(workDataOf("report_end" to end)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("daily_report_$end",
            ExistingWorkPolicy.KEEP, request)
        DailyReportScheduler.schedule(context)
    }
}

class DailyReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!DailyReportPreferences.enabled(applicationContext)) return Result.success()
        val end = inputData.getLong("report_end", 0L)
        val previousEnd = DailyReportPreferences.lastEnd(applicationContext)
        if (end <= 0L || end <= previousEnd) return Result.success()
        return try {
            val start = previousEnd.takeIf { it > 0L }
                ?: maxOf(DailyReportScheduler.previousBoundary(end), DailyReportPreferences.enabledAt(applicationContext))
            val report = DailyReportGenerator.generate(applicationContext, start, end)
            val store = DailyReportStore(applicationContext)
            store.save(report)
            DailyReportPreferences.setLastEnd(applicationContext, end)
            store.prune(DailyReportPreferences.keepHistory(applicationContext),
                DailyReportPreferences.retention(applicationContext).days)
            refreshReportWidget(applicationContext)
            if (DailyReportPreferences.notify(applicationContext))
                DailyReportNotifier.show(applicationContext, report)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

class ReportRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DailyReportScheduler.catchUpOrSchedule(context)
    }
}

object DailyReportNotifier {
    private const val CHANNEL = "daily_reports"

    fun show(context: Context, report: DailyReport) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Relatórios diários",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Avisa quando o relatório diário da conexão está pronto"
        })
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra("open_tab", "REPORTS")
            putExtra("report_end", report.end)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(context, (report.end / 1_000L).toInt(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val detail = when {
            report.interruptions.isNotEmpty() ->
                "${report.interruptions.size} interrupção(ões). Maior: ${duration(report.interruptions.maxOf { it.durationMs })}."
            report.availabilityPercent != null ->
                "${String.format(java.util.Locale.getDefault(), "%.2f", report.availabilityPercent)}% online · latência média ${report.latency?.average?.toInt()?.let { "$it ms" } ?: "indisponível"}"
            else -> "O período não teve dados suficientes para calcular disponibilidade."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Seu relatório diário está pronto")
            .setContentText(detail)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify((report.end / 1_000L).toInt(), notification) }
    }

    private fun duration(ms: Long): String = if (ms < 60_000L) "${ms / 1_000L} s"
        else "${ms / 60_000L} min ${ms / 1_000L % 60L} s"
}
