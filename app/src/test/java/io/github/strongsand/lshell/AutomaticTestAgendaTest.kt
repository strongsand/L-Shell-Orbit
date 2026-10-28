package io.github.strongsand.lshell

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AutomaticTestAgendaTest {
    private val utc = ZoneId.of("UTC")
    private val date = LocalDate.of(2026, 10, 16)
    private val day = date.toEpochDay()
    private val start = date.atStartOfDay(utc).toInstant().toEpochMilli()
    private val hour = AutomaticTestPolicy.HOUR
    private val custom = AutomaticTestSettings(scheduled = true, custom = true, customTimes = listOf(480, 750, 1020, 1350))
    private val ledger = AutomaticTestLedger(enabledAt = start - hour)
    private fun next(s: AutomaticTestSettings = custom, l: AutomaticTestLedger = ledger, at: Long = start) =
        AutomaticTestPolicy.nextScheduled(s, l, at, utc)
    private fun prefs(data: MutableMap<String, Any> = mutableMapOf()): AutomaticTestPreferences {
        // Android interfaces, with no Android runtime or disk: production preference encoding is exercised directly.
        val changes = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "commit", "apply" -> { changes.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }; changes.clear(); true }
                "remove" -> { changes[args[0] as String] = null; proxy }
                else -> { if (method.name.startsWith("put")) changes[args[0] as String] = args[1]; proxy }
            }
        }
        val storage = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getAll" -> data.toMap()
                "contains" -> data.containsKey(args[0])
                else -> if (method.name.startsWith("get")) data[args[0]] ?: args[1] else null
            }
        } as SharedPreferences
        return AutomaticTestPreferences(storage, utc)
    }
    @Test fun customTimesGenerateLocalSlotsInSortedOrder() {
        val normalized = AutomaticTestPolicy.normalize(custom.copy(customTimes = listOf(1350, 480, 1020, 750)))
        assertEquals(custom.customTimes, normalized.customTimes)
        assertEquals(listOf(8 * hour, 12 * hour + hour / 2, 17 * hour, 22 * hour + hour / 2),
            AutomaticTestPolicy.plannedSlots(normalized, day, utc).map { it.plannedAt - start })
    }
    @Test fun addEditRemoveAlwaysSortWithoutDuplicates() {
        val added = AutomaticTestPolicy.editTimes(listOf(480), null, 60)
        assertEquals(listOf(60, 480), added)
        assertEquals(listOf(480, 750), AutomaticTestPolicy.editTimes(added, 0, 750))
        assertEquals(listOf(480), AutomaticTestPolicy.editTimes(added, 0, null))
    }
    @Test fun invalidDuplicateEmptyAndNinthTimeAreRejected() {
        for (times in listOf(emptyList(), listOf(480, 480), listOf(-1), listOf(1440), (0..8).toList()))
            assertFalse(AutomaticTestPolicy.validTimes(times))
        assertTrue(AutomaticTestPolicy.validTimes((0..7).toList()))
        assertThrows(IllegalArgumentException::class.java) { AutomaticTestPolicy.editTimes(listOf(480), null, 480) }
        assertThrows(IllegalArgumentException::class.java) { AutomaticTestPolicy.editTimes(listOf(480), 0, null) }
        assertThrows(IllegalArgumentException::class.java) { prefs().save(custom.copy(customTimes = listOf(480, 480))) }
    }
    @Test fun enablingMiddayDoesNotExecuteEarlierTimes() {
        val enabled = start + 13 * hour
        assertEquals(start + 17 * hour, next(l = ledger.copy(enabledAt = enabled), at = enabled).plannedAt)
    }
    @Test fun fortyMinuteDelayAllowsOnlyMostRecentValidSlot() {
        val s = custom.copy(customTimes = listOf(900, 1080))
        val now = start + 18 * hour + 40 * 60_000
        val due = next(s, at = now)
        assertEquals(start + 18 * hour, due.plannedAt)
        assertTrue(AutomaticTestPolicy.validSlot(s, ledger, due, now, utc))
        val started = AutomaticTestPolicy.started(ledger, AutomaticTestTrigger.SCHEDULED, now, day, now, due)
        assertEquals(start + 24 * hour + 15 * hour, next(s, started, now).plannedAt)
        assertFalse(AutomaticTestPolicy.validSlot(s, started, due, now, utc))
    }
    @Test fun latenessWindowExpiresAtOneHourOrNextSlotWhicheverComesFirst() {
        val s = custom.copy(customTimes = listOf(480, 510))
        val first = next(s)
        assertEquals(start + 8 * hour + hour / 2, AutomaticTestPolicy.deadline(s, first, utc))
        assertFalse(AutomaticTestPolicy.validSlot(s, ledger, first, first.plannedAt + hour / 2, utc))
        assertEquals(start + 8 * hour + hour / 2, next(s, at = first.plannedAt + hour / 2).plannedAt)
        assertEquals(start + 12 * hour + hour / 2, next(at = start + 9 * hour).plannedAt)
    }
    @Test fun queuedDuplicateCannotAdmitConsumedSlotEvenAfterCooldown() {
        val slot = next()
        val consumed = AutomaticTestPolicy.started(ledger, AutomaticTestTrigger.SCHEDULED, slot.plannedAt, day, 12, slot)
        assertFalse(AutomaticTestPolicy.validSlot(custom, consumed, slot, slot.plannedAt + hour, utc))
        assertThrows(IllegalArgumentException::class.java) {
            AutomaticTestPolicy.started(consumed, AutomaticTestTrigger.SCHEDULED, slot.plannedAt, day, 13, slot)
        }
    }
    @Test fun predicatesAndWakeupsNeverIncrementCounters() {
        val l = ledger.copy(day = day, attempts = 3)
        repeat(4) { AutomaticTestPolicy.candidate(custom, l, start + 8 * hour, day, true, 0, true, utc) }
        assertEquals(3, l.attempts)
        val storage = mutableMapOf<String, Any>()
        val p = prefs(storage)
        p.save(custom, start - hour)
        p.skipped(next(), p.revision())
        assertEquals(0, p.ledger().attempts)
        assertEquals(AutomaticSlotStatus.NOT_EXECUTED, p.ledger().slots.single().status)
    }
    @Test fun presetsCustomAndBackPreserveDailyUsageAndResults() {
        val storage = mutableMapOf<String, Any>()
        val p = prefs(storage)
        val eight = custom.copy(custom = false, intervalHours = 3)
        p.save(eight, start - hour)
        val at = start + 6 * hour
        val slot = next(eight, p.ledger(), at)
        assertTrue(p.attempt(AutomaticTestTrigger.SCHEDULED, at, day, 10, slot, p.revision()))
        p.reconcile(ConnectionTestState(id = 10, phase = ConnectionTestPhase.COMPLETED))
        var rev = p.revision()
        for (s in listOf(eight.copy(intervalHours = 6), custom, eight)) {
            assertTrue(p.save(s, start + 7 * hour))
            assertTrue(p.revision() > rev); rev = p.revision()
            assertEquals(1, p.ledger().attempts)
            assertEquals(AutomaticSlotStatus.COMPLETED, p.ledger().slots.single().status)
            assertTrue(next(s, p.ledger(), start + 7 * hour).plannedAt > start + 7 * hour)
        }
    }
    @Test fun recreationAndRebootKeepConfigConsumptionAndCustomTimes() {
        val storage = mutableMapOf<String, Any>()
        val first = prefs(storage)
        first.save(custom, start - hour)
        val slot = next()
        assertTrue(first.attempt(AutomaticTestTrigger.SCHEDULED, slot.plannedAt, day, 13, slot, first.revision()))
        val recreated = prefs(storage)
        assertEquals(custom, recreated.settings())
        assertEquals(first.ledger(), recreated.ledger())
        recreated.reconcile(ConnectionTestState(id = 13, phase = ConnectionTestPhase.INTERRUPTED))
        assertEquals(AutomaticSlotStatus.INTERRUPTED, recreated.ledger().slots.single().status)
        assertFalse(AutomaticTestPolicy.validSlot(custom, recreated.ledger(), slot, slot.plannedAt + 1, utc))
    }
    @Test fun disablingAndStaleWorkerDoNotCountOrMarkNewAgenda() {
        val storage = mutableMapOf<String, Any>()
        val p = prefs(storage)
        p.save(custom, start - hour)
        val rev = p.revision()
        p.save(custom.copy(scheduled = false), start)
        p.skipped(next(), rev)
        assertFalse(p.attempt(AutomaticTestTrigger.SCHEDULED, start + 8 * hour, day, 14, next(), rev))
        assertEquals(0, p.ledger().attempts); assertTrue(p.ledger().slots.isEmpty())
        assertNull(AutomaticTestPolicy.candidate(p.settings(), p.ledger(), start + 8 * hour, day, false, 0, true, utc))
    }
    @Test fun duplicateStartIsIdempotentAndFailureBeforeRunningDoesNotCount() {
        val p = prefs()
        p.save(custom, start - hour)
        val slot = next()
        p.reconcile(ConnectionTestState(id = 20, phase = ConnectionTestPhase.FAILED))
        assertEquals(0, p.ledger().attempts)
        assertTrue(p.attempt(AutomaticTestTrigger.SCHEDULED, slot.plannedAt, day, 20, slot, p.revision()))
        assertTrue(p.attempt(AutomaticTestTrigger.SCHEDULED, slot.plannedAt, day, 20, slot, p.revision()))
        assertEquals(1, p.ledger().attempts)
        p.reconcile(ConnectionTestState(id = 20, phase = ConnectionTestPhase.FAILED))
        assertEquals(AutomaticSlotStatus.FAILED, p.ledger().slots.single().status)
    }
    @Test fun activeRunIsNotCompletedByClockOrPreferenceReconciliation() {
        val p = prefs()
        p.save(custom, start - hour)
        val slot = next()
        assertTrue(p.attempt(AutomaticTestTrigger.SCHEDULED, slot.plannedAt, day, 23, slot, p.revision()))
        p.reconcile(ConnectionTestState(id = 23, phase = ConnectionTestPhase.RUNNING))
        assertEquals(AutomaticSlotStatus.RUNNING, p.ledger().slots.single().status)
        next(custom, p.ledger(), start + 23 * hour)
        assertEquals(AutomaticSlotStatus.RUNNING, p.ledger().slots.single().status)
    }
    @Test fun realSmartAndScheduledStartsCannotExceedSharedEight() {
        val p = prefs()
        p.save(custom.copy(smart = true, smartDailyLimit = 8), start - hour)
        for (id in 1L..8L) assertTrue(p.attempt(AutomaticTestTrigger.SMART, start + id * hour, day,
            id, null, p.revision()))
        val slot = next(at = start + 17 * hour)
        assertFalse(p.attempt(AutomaticTestTrigger.SCHEDULED, slot.plannedAt, day, 24, slot, p.revision()))
        assertEquals(8, p.ledger().attempts)
    }
    @Test fun midnightResetsOnlyDailyCountersAndDoesNotReplayPreviousRun() {
        val slot = next()
        val done = AutomaticTestPolicy.started(ledger, AutomaticTestTrigger.SCHEDULED, slot.plannedAt, day, 21, slot)
        assertEquals(0, AutomaticTestPolicy.usedToday(done, day + 1))
        val tomorrow = start + 24 * hour + 8 * hour
        val newSlot = next(l = done, at = tomorrow)
        val tomorrowLedger = AutomaticTestPolicy.started(done, AutomaticTestTrigger.SCHEDULED, tomorrow, day + 1, 22, newSlot)
        assertEquals(1, tomorrowLedger.attempts)
        assertEquals(2, tomorrowLedger.slots.size)
        assertNotEquals(slot.key, newSlot.key)
    }
    @Test fun scheduledManualAndSmartShareQuotaAndCooldown() {
        val s = custom.copy(smart = true)
        val slot = next()
        assertNull(AutomaticTestPolicy.candidate(s, ledger, slot.plannedAt, day, true, 0, true, utc))
        assertNull(AutomaticTestPolicy.candidate(s, ledger, slot.plannedAt, day, false, slot.plannedAt - 1, true, utc))
        assertNull(AutomaticTestPolicy.candidate(s, ledger.copy(day = day, attempts = 8), slot.plannedAt, day, false, 0, true, utc))
        assertEquals(AutomaticTestTrigger.SCHEDULED, AutomaticTestPolicy.candidate(s, ledger, slot.plannedAt, day, false, 0, true, utc))
    }
    @Test fun timezoneAndDstUseStableLocalMinuteKeys() {
        for (zone in listOf(ZoneId.of("America/Sao_Paulo"), ZoneId.of("America/New_York"))) {
            val slots = AutomaticTestPolicy.plannedSlots(custom, day, zone)
            assertEquals(custom.customTimes, slots.map { java.time.Instant.ofEpochMilli(it.plannedAt).atZone(zone).let { d -> d.hour * 60 + d.minute } })
            assertEquals(4, slots.map { it.key }.distinct().size)
        }
        val dst = LocalDate.of(2026, 11, 1).toEpochDay()
        val times = custom.copy(customTimes = listOf(90, 180))
        assertEquals(2, AutomaticTestPolicy.plannedSlots(times, dst, ZoneId.of("America/New_York")).size)
    }
    @Test fun backupKeepsCustomSettingsButNeverRuntimeLedger() {
        val values = listOf(BackupPreference("automatic_speed_tests", "custom", true),
            BackupPreference("automatic_speed_tests", "custom_times", "480,750,1020"),
            BackupPreference("automatic_speed_tests", "scheduled", true),
            BackupPreference("automatic_speed_tests", "slot_records", "[]"))
        val plan = BackupSettingsCodec.plan(BackupSettingsCodec.encode(values), false)
        assertEquals(listOf("custom", "custom_times"), plan.compatible.map { it.name })
        assertTrue(plan.automationRequested)
        assertFalse(BackupSettingsCodec.rules.first { it.name == "custom_times" }.accepts("480,480"))
    }
}
