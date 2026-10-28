package io.github.strongsand.lshell

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** One local preference namespace for both automation modes, never a second results DB. */
internal class AutomaticTestPreferences(private val prefs: SharedPreferences,
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
    internal companion object { val lock = Any() }
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("automatic_speed_tests", Context.MODE_PRIVATE))
    fun settings() = AutomaticTestPolicy.normalize(AutomaticTestSettings(
        prefs.getBoolean("scheduled", false), prefs.getInt("interval", 6),
        prefs.getBoolean("smart", false), prefs.getInt("cooldown", 12), prefs.getInt("smart_limit", 2),
        prefs.getBoolean("custom", false), prefs.getString("custom_times", "480").orEmpty()
            .split(',').mapNotNull { it.toIntOrNull() }))
    fun revision() = prefs.getLong("schedule_revision", 0)
    fun invalidateSchedule() { prefs.edit().putLong("schedule_revision", revision() + 1).commit() }
    fun save(s: AutomaticTestSettings, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        require(!s.custom || AutomaticTestPolicy.validTimes(s.customTimes))
        val normalized = AutomaticTestPolicy.normalize(s)
        val previous = settings()
        prefs.edit().apply {
            putBoolean("scheduled", normalized.scheduled); putInt("interval", normalized.intervalHours)
            putBoolean("smart", normalized.smart); putInt("cooldown", normalized.cooldownHours)
            putInt("smart_limit", normalized.smartDailyLimit)
            putBoolean("custom", normalized.custom)
            putString("custom_times", normalized.customTimes.joinToString(","))
            if (previous.scheduled != normalized.scheduled || previous.intervalHours != normalized.intervalHours ||
                previous.custom != normalized.custom || previous.customTimes != normalized.customTimes) {
                putLong("schedule_revision", revision() + 1)
                putLong("enabled_at", now)
                putLong("scheduled_slot", -1)
            }
        }.commit()
    }
    fun ledger() = AutomaticTestLedger(prefs.getLong("enabled_at", 0), prefs.getLong("last_scheduled", 0),
        prefs.getLong("last_attempt", 0), prefs.getLong("last_smart", 0), prefs.getLong("last_probe", 0),
        prefs.getLong("day", -1), prefs.getInt("attempts", 0), prefs.getInt("smart_attempts", 0),
        prefs.getLong("scheduled_slot", -1), prefs.getInt("scheduled_attempts",
            (prefs.getInt("attempts", 0) - prefs.getInt("smart_attempts", 0)).coerceAtLeast(0)), records())
    private fun records(): List<AutomaticSlotRecord> = runCatching {
        val array = JSONArray(prefs.getString("slot_records", "[]"))
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            AutomaticSlotRecord(AutomaticScheduledSlot(it.getLong("key"), it.getLong("planned")),
                AutomaticSlotStatus.valueOf(it.getString("status")), it.optLong("run"), it.optLong("start"))
        } }
    }.getOrDefault(emptyList())
    private fun SharedPreferences.Editor.records(records: List<AutomaticSlotRecord>) = putString("slot_records",
        JSONArray().apply { records.takeLast(56).forEach { record -> put(JSONObject().apply {
            put("key", record.slot.key); put("planned", record.slot.plannedAt); put("status", record.status.name)
            put("run", record.runId); put("start", record.startedAt)
        }) } }.toString())
    fun skipped(slot: AutomaticScheduledSlot, expectedRevision: Long) = synchronized(lock) {
        if (revision() == expectedRevision && records().none { it.slot.key == slot.key })
            prefs.edit().records(records() + AutomaticSlotRecord(slot, AutomaticSlotStatus.NOT_EXECUTED)).commit()
    }
    fun reconcile(state: ConnectionTestState) = synchronized(lock) {
        val updated = records().map { record ->
            if (record.status != AutomaticSlotStatus.RUNNING || state.active && state.id == record.runId) record
            else record.copy(status = if (state.id == record.runId) when (state.phase) {
                ConnectionTestPhase.COMPLETED -> AutomaticSlotStatus.COMPLETED
                ConnectionTestPhase.FAILED -> AutomaticSlotStatus.FAILED
                else -> AutomaticSlotStatus.INTERRUPTED
            } else AutomaticSlotStatus.INTERRUPTED)
        }
        if (updated != records()) prefs.edit().records(updated).commit()
    }
    fun ensureScheduledAnchor(now: Long) {
        if (settings().scheduled && !prefs.contains("enabled_at"))
            prefs.edit().putLong("enabled_at", now).commit()
    }
    fun probe(now: Long): Boolean = prefs.edit().putLong("last_probe", now).commit()
    fun evaluating(now: Long) { prefs.edit().putLong("evaluating_at_smart", now).apply() }
    fun clearEvaluating() { prefs.edit().remove("evaluating_at_smart").apply() }
    fun evaluatingAt() = prefs.getLong("evaluating_at_smart", 0)
    fun errorAt(trigger: AutomaticTestTrigger): Long {
        val key = trigger.name.lowercase()
        return if (prefs.getBoolean("outcome_error_$key", false)) prefs.getLong("outcome_at_$key", 0) else 0
    }
    fun attempt(trigger: AutomaticTestTrigger, now: Long, day: Long, runId: Long,
                slot: AutomaticScheduledSlot?, expectedRevision: Long): Boolean = synchronized(lock) {
        val old = ledger()
        if (prefs.getLong("last_counted_run", 0) == runId) return@synchronized true
        val current = settings()
        if (AutomaticTestPolicy.usedToday(old, day) >= AutomaticTestPolicy.DAILY_LIMIT ||
            (trigger == AutomaticTestTrigger.SMART && !current.smart) ||
            (trigger == AutomaticTestTrigger.SCHEDULED && (revision() != expectedRevision || slot == null ||
                !AutomaticTestPolicy.validSlot(current, old, slot, now, zone)))) return@synchronized false
        val next = AutomaticTestPolicy.started(old, trigger, now, day, runId, slot)
        prefs.edit().apply {
            putLong("last_counted_run", runId)
            putLong("last_attempt", next.lastAttempt); putLong("day", next.day)
            putInt("attempts", next.attempts); putInt("smart_attempts", next.smartAttempts)
            putInt("scheduled_attempts", next.scheduledAttempts)
            putLong("last_scheduled", next.lastScheduled); putLong("last_smart", next.lastSmartAttempt)
            records(next.slots)
        }.commit()
    }
    // Resource IDs are not stable across builds. Keep each mode's activity separate.
    fun outcome(context: Context, trigger: AutomaticTestTrigger, resource: Int, result: ConnectionTestState? = null) {
        val key = trigger.name.lowercase()
        prefs.edit().apply {
            putString("outcome_name_$key", context.resources.getResourceEntryName(resource))
            putLong("outcome_at_$key", System.currentTimeMillis())
            putBoolean("outcome_error_$key", resource == R.string.auto_failed || resource == R.string.auto_unavailable)
            if (result != null) {
                putLong("last_test_at_$key", result.startedAt)
                val download = ConnectionTestPresentation.metrics(result).download
                if (download != null) putFloat("last_download_$key", download)
                else remove("last_download_$key")
            }
        }.apply()
    }
    fun lastOutcome(context: Context, trigger: AutomaticTestTrigger): Pair<Long, String>? {
        val key = trigger.name.lowercase()
        val name = prefs.getString("outcome_name_$key", null) ?: return null
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        return if (id != 0) prefs.getLong("outcome_at_$key", 0) to context.getString(id) else null
    }
    fun lastTest(trigger: AutomaticTestTrigger): Pair<Long, Float?>? {
        val key = trigger.name.lowercase()
        val at = prefs.getLong("last_test_at_$key", 0)
        return if (at > 0) at to (if (prefs.contains("last_download_$key")) prefs.getFloat("last_download_$key", 0f) else null) else null
    }
    fun observeOutcome(onChanged: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) onChanged()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
