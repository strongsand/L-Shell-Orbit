package io.github.strongsand.lshell

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Local aggregation only. WorkManager restores scheduling after reboot; force stop can defer it. */
internal object ConnectionHealthSchedule {
    private const val PERIODIC = "connection_health_reports_periodic"
    private const val REFRESH = "connection_health_reports_refresh"
    fun enabled(context: Context) = context.getSharedPreferences("connection_health_reports", Context.MODE_PRIVATE).getBoolean("enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("connection_health_reports", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        schedule(context)
    }
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (!enabled(context)) { manager.cancelUniqueWork(PERIODIC); manager.cancelUniqueWork(REFRESH); return }
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ConnectionHealthWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build())
        manager.enqueueUniqueWork(REFRESH, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ConnectionHealthWorker>().setConstraints(constraints).build())
    }
}

class ConnectionHealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        ConnectionHealthStore.refresh(applicationContext)
        Result.success()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
}
