package com.hurricane.lshell

import android.content.Context

object MonitorPreferences {
    private const val FILE = "monitor_preferences"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SILENT = "silent_alerts"
    private const val KEY_INTERVAL = "interval_seconds"
    private const val KEY_LAST_ALERTS = "last_alerts"
    private const val KEY_LAST_SUCCESS = "last_success"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_HISTORY_COUNTER = "history_counter"
    private const val KEY_HISTORY_BOOT = "history_boot"
    private const val KEY_AR_MAX_DISTANCE_KM = "ar_max_distance_km"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) { prefs(context).edit().putBoolean(KEY_ENABLED, value).apply() }

    fun silent(context: Context): Boolean = prefs(context).getBoolean(KEY_SILENT, false)
    fun setSilent(context: Context, value: Boolean) { prefs(context).edit().putBoolean(KEY_SILENT, value).apply() }

    fun intervalSeconds(context: Context): Int = prefs(context).getInt(KEY_INTERVAL, 60).coerceIn(1, 300)
    fun setIntervalSeconds(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL, value.coerceIn(1, 300)).apply()
    }

    fun lastAlerts(context: Context): Set<String> = prefs(context).getStringSet(KEY_LAST_ALERTS, emptySet())?.toSet() ?: emptySet()
    fun setLastAlerts(context: Context, value: Set<String>) {
        prefs(context).edit().putStringSet(KEY_LAST_ALERTS, value).apply()
    }

    fun lastSuccess(context: Context): Long = prefs(context).getLong(KEY_LAST_SUCCESS, 0L)
    fun lastError(context: Context): String? = prefs(context).getString(KEY_LAST_ERROR, null)
    fun reportSuccess(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).remove(KEY_LAST_ERROR).apply()
    }
    fun reportFailure(context: Context, message: String) {
        prefs(context).edit().putString(KEY_LAST_ERROR, message.take(160)).apply()
    }

    fun historyCounter(context: Context): Long = prefs(context).getLong(KEY_HISTORY_COUNTER, -1L)
    fun historyBoot(context: Context): Long = prefs(context).getLong(KEY_HISTORY_BOOT, -1L)
    fun setHistoryCursor(context: Context, counter: Long, boot: Long) {
        prefs(context).edit().putLong(KEY_HISTORY_COUNTER, counter).putLong(KEY_HISTORY_BOOT, boot).apply()
    }

    fun arMaxDistanceKm(context: Context): Int {
        val stored = prefs(context).getInt(KEY_AR_MAX_DISTANCE_KM, 1_000)
        return stored.takeIf { it in setOf(0, 500, 750, 1_000, 1_500, 2_000) } ?: 1_000
    }

    fun setArMaxDistanceKm(context: Context, value: Int) {
        val valid = value.takeIf { it in setOf(0, 500, 750, 1_000, 1_500, 2_000) } ?: 1_000
        prefs(context).edit().putInt(KEY_AR_MAX_DISTANCE_KM, valid).apply()
    }
}
