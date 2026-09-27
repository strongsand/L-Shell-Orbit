package com.hurricane.lshell

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/** Compatibility stub that removes periodic widget work left by an older installed version. */
class WidgetRefreshWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // Widget providers are intentionally disabled. An older installed build may still have
        // this unique work persisted, so its final execution only removes both legacy jobs.
        cancelWidgetRefresh(applicationContext)
        return Result.success()
    }
}

internal fun cancelWidgetRefresh(context: Context) {
    WorkManager.getInstance(context.applicationContext).apply {
        cancelUniqueWork("dish_widget_refresh")
        cancelUniqueWork("dish_widget_refresh_now")
    }
}
