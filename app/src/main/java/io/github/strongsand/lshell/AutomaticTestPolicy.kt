package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import io.github.strongsand.lshell.core.network.grpc.terminalHistoryValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class AutomaticTestTrigger { SCHEDULED, SMART }
internal data class AutomaticTestSettings(
    val scheduled: Boolean = false,
    val intervalHours: Int = 6,
    val smart: Boolean = false,
    val cooldownHours: Int = 12,
    val smartDailyLimit: Int = 2,
    val custom: Boolean = false,
    val customTimes: List<Int> = listOf(8 * 60)
)
internal data class AutomaticTestLedger(
    val enabledAt: Long = 0,
    val lastScheduled: Long = 0,
    val lastAttempt: Long = 0,
    val lastSmartAttempt: Long = 0,
    val lastProbe: Long = 0,
    val day: Long = -1,
    val attempts: Int = 0,
    val smartAttempts: Int = 0,
    val scheduledSlot: Long = -1,
    val scheduledAttempts: Int = 0,
    val slots: List<AutomaticSlotRecord> = emptyList()
)

internal data class AutomaticScheduledSlot(val key: Long, val plannedAt: Long)
internal enum class AutomaticSlotStatus { RUNNING, COMPLETED, FAILED, INTERRUPTED, NOT_EXECUTED }
internal data class AutomaticSlotRecord(val slot: AutomaticScheduledSlot, val status: AutomaticSlotStatus,
    val runId: Long = 0, val startedAt: Long = 0)
internal enum class AutomaticScreenStatus { DISABLED, WAITING, SCHEDULED, EVALUATING, RUNNING, COOLDOWN, DAILY_LIMIT_REACHED, TEMPORARILY_BLOCKED, ERROR, OVERDUE }

internal object AutomaticTestPolicy {
    const val HOUR = 3_600_000L
    const val DAILY_LIMIT = 8
    const val MAX_LATENESS = HOUR
    fun validTimes(times: List<Int>) = times.size in 1..DAILY_LIMIT &&
        times.all { it in 0..1439 } && times.distinct().size == times.size
    fun editTimes(times: List<Int>, index: Int?, minute: Int?): List<Int> {
        val next = times.toMutableList()
        if (index != null) { require(index in next.indices); next.removeAt(index) }
        if (minute != null) next.add(minute)
        require(validTimes(next))
        return next.sorted()
    }
    fun localDay(at: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toEpochDay()
    val intervals = listOf(1, 3, 4, 6, 12, 24)
    val cooldowns = listOf(3, 4, 6, 12, 24)
    fun normalize(s: AutomaticTestSettings) = s.copy(
        intervalHours = s.intervalHours.takeIf { it in intervals } ?: 6,
        cooldownHours = s.cooldownHours.takeIf { it in cooldowns } ?: 12,
        smartDailyLimit = s.smartDailyLimit.coerceIn(1, DAILY_LIMIT),
        customTimes = s.customTimes.filter { it in 0..1439 }.distinct().sorted().take(DAILY_LIMIT)
            .ifEmpty { listOf(8 * 60) })

    val frequencies = listOf(2, 4, 6, 8)
    fun intervalFor(frequency: Int): Int {
        require(frequency in frequencies)
        return 24 / frequency
    }
    // Legacy 1/day retains its previous cooldown; new intensities have spaced opportunities.
    fun smartCooldownHours(s: AutomaticTestSettings) = maxOf(s.cooldownHours,
        if (s.smartDailyLimit >= 2) 24 / s.smartDailyLimit else s.cooldownHours)
    fun usedToday(l: AutomaticTestLedger, day: Long) = if (l.day >= day) l.attempts else 0
    /** Called only at RUNNING, after foreground admission. Checks/probes never spend quota. */
    fun started(l: AutomaticTestLedger, trigger: AutomaticTestTrigger, now: Long, day: Long,
                runId: Long, slot: AutomaticScheduledSlot?): AutomaticTestLedger {
        require(runId > 0 && usedToday(l, day) < DAILY_LIMIT)
        require(slot == null || l.slots.none { it.slot.key == slot.key })
        return l.copy(lastAttempt = now, day = maxOf(l.day, day), attempts = usedToday(l, day) + 1,
            smartAttempts = (if (l.day >= day) l.smartAttempts else 0) + if (trigger == AutomaticTestTrigger.SMART) 1 else 0,
            scheduledAttempts = (if (l.day >= day) l.scheduledAttempts else 0) + if (trigger == AutomaticTestTrigger.SCHEDULED) 1 else 0,
            lastSmartAttempt = if (trigger == AutomaticTestTrigger.SMART) now else l.lastSmartAttempt,
            lastScheduled = if (trigger == AutomaticTestTrigger.SCHEDULED) now else l.lastScheduled,
            slots = if (slot != null) (l.slots + AutomaticSlotRecord(slot, AutomaticSlotStatus.RUNNING, runId, now)).takeLast(56) else l.slots)
    }
    fun minutes(s: AutomaticTestSettings): List<Int> = if (s.custom) normalize(s).customTimes
        else (0 until 24 / s.intervalHours).map { it * s.intervalHours * 60 }
    private fun slot(day: Long, index: Int, s: AutomaticTestSettings, zone: ZoneId): AutomaticScheduledSlot {
        val date = LocalDate.ofEpochDay(day)
        val minute = minutes(s)[index]
        val planned = date.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
        return AutomaticScheduledSlot(day * 1440 + minute, planned)
    }
    /** Wall-clock slots, not intervals measured from the previous actual execution. */
    fun scheduledSlot(s: AutomaticTestSettings, now: Long, zone: ZoneId = ZoneId.systemDefault()): AutomaticScheduledSlot {
        val day = localDay(now, zone)
        return (plannedSlots(s, day - 1, zone) + plannedSlots(s, day, zone)).last { it.plannedAt <= now }
    }
    fun plannedSlots(s: AutomaticTestSettings, day: Long, zone: ZoneId = ZoneId.systemDefault()): List<AutomaticScheduledSlot> =
        minutes(s).indices.map { slot(day, it, s, zone) }.distinctBy { it.plannedAt }
    fun deadline(s: AutomaticTestSettings, slot: AutomaticScheduledSlot, zone: ZoneId = ZoneId.systemDefault()): Long {
        val day = localDay(slot.plannedAt, zone)
        val following = (plannedSlots(s, day, zone) + plannedSlots(s, day + 1, zone))
            .first { it.plannedAt > slot.plannedAt }.plannedAt
        return minOf(slot.plannedAt + MAX_LATENESS, following)
    }
    fun validSlot(s: AutomaticTestSettings, l: AutomaticTestLedger, slot: AutomaticScheduledSlot,
                  now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        s.scheduled && slot.plannedAt > l.enabledAt && slot.plannedAt > l.lastScheduled &&
            l.slots.none { it.slot.key == slot.key } && now >= slot.plannedAt && now < deadline(s, slot, zone) &&
            plannedSlots(s, localDay(slot.plannedAt, zone), zone).any { it == slot }
    fun nextScheduled(s: AutomaticTestSettings, l: AutomaticTestLedger, now: Long,
                      zone: ZoneId = ZoneId.systemDefault()): AutomaticScheduledSlot {
        val day = localDay(maxOf(now, l.enabledAt, l.lastScheduled), zone)
        return ((day - 1)..(day + 2)).flatMap { plannedSlots(s, it, zone) }
            .first { it.plannedAt > l.enabledAt && it.plannedAt > l.lastScheduled &&
                l.slots.none { record -> record.slot.key == it.key } &&
                (it.plannedAt > now || now < deadline(s, it, zone)) }
    }
    fun nextEligible(s: AutomaticTestSettings, l: AutomaticTestLedger, smart: Boolean, lastManualStart: Long,
                     now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long =
        maxOf(if (smart) l.lastSmartAttempt.takeIf { it > 0 }?.plus(smartCooldownHours(s) * HOUR) ?: 0
            else nextScheduled(s, l, now, zone).plannedAt,
            maxOf(l.lastAttempt, lastManualStart).takeIf { it > 0 }?.plus(HOUR) ?: 0,
            if (smart) l.lastProbe.takeIf { it > 0 }?.plus(HOUR) ?: 0 else 0)

    fun candidate(s: AutomaticTestSettings, l: AutomaticTestLedger, now: Long, day: Long,
                  active: Boolean, lastManualStart: Long, screenOff: Boolean,
                  zone: ZoneId = ZoneId.systemDefault()): AutomaticTestTrigger? {
        if (active || usedToday(l, day) >= DAILY_LIMIT) return null
        val last = maxOf(l.lastAttempt, lastManualStart)
        if (last > 0 && now - last < HOUR) return null
        if (s.scheduled && nextScheduled(s, l, now, zone).plannedAt <= now)
            return AutomaticTestTrigger.SCHEDULED
        if (s.smart && screenOff && (l.day < day || l.smartAttempts < s.smartDailyLimit) &&
            (l.lastSmartAttempt == 0L || now - l.lastSmartAttempt >= smartCooldownHours(s) * HOUR) &&
            (l.lastProbe == 0L || now - l.lastProbe >= HOUR)) return AutomaticTestTrigger.SMART
        return null
    }

    fun screenStatus(s: AutomaticTestSettings, l: AutomaticTestLedger, smart: Boolean, now: Long, day: Long,
                     active: Boolean, activeOrigin: TestOrigin, lastTestStart: Long,
                     notificationsEnabled: Boolean, evaluatingAt: Long = 0, errorAt: Long = 0,
                     zone: ZoneId = ZoneId.systemDefault()): AutomaticScreenStatus {
        if (!(if (smart) s.smart else s.scheduled)) return AutomaticScreenStatus.DISABLED
        if (active) return if (activeOrigin == (if (smart) TestOrigin.SMART else TestOrigin.SCHEDULED))
            AutomaticScreenStatus.RUNNING else AutomaticScreenStatus.TEMPORARILY_BLOCKED
        if (!notificationsEnabled) return AutomaticScreenStatus.TEMPORARILY_BLOCKED
        if (usedToday(l, day) >= DAILY_LIMIT || smart && l.day >= day && l.smartAttempts >= s.smartDailyLimit)
            return AutomaticScreenStatus.DAILY_LIMIT_REACHED
        if (smart && evaluatingAt > 0 && now - evaluatingAt in 0..120_000L)
            return AutomaticScreenStatus.EVALUATING
        if (errorAt > 0 && now - errorAt in 0..300_000L)
            return AutomaticScreenStatus.ERROR
        val globalAt = maxOf(l.lastAttempt, lastTestStart).takeIf { it > 0 }?.plus(HOUR) ?: 0
        if (globalAt > now) return AutomaticScreenStatus.COOLDOWN
        if (smart) {
            if (nextEligible(s, l, true, lastTestStart, now, zone) > now) return AutomaticScreenStatus.COOLDOWN
            return AutomaticScreenStatus.WAITING
        }
        return if (nextScheduled(s, l, now, zone).plannedAt <= now) AutomaticScreenStatus.OVERDUE
            else AutomaticScreenStatus.SCHEDULED
    }

    /** Complete paired one-second samples only; absent/NaN/negative values are not idle. */
    fun lowUsage(history: DishyGrpcClient.HistoryReading): Boolean {
        if (history.current < 60L) return false
        val down = ArrayList<Float>(60)
        val up = ArrayList<Float>(60)
        for (counter in history.current - 60 until history.current) {
            down += terminalHistoryValue(history.downloadMbps, history.current, counter)?.takeIf { it >= 0 } ?: return false
            up += terminalHistoryValue(history.uploadMbps, history.current, counter)?.takeIf { it >= 0 } ?: return false
        }
        return down.average() <= 1.0 && up.average() <= 0.2 && down.max() <= 2f && up.max() <= .5f
    }
}
