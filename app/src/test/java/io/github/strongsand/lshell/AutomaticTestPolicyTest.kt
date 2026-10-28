package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AutomaticTestPolicyTest {
    @Test fun automaticProgressCannotReplaceBeaconSyncOrTestResultNotification() {
        assertNotEquals(BeaconSyncNotification.ID, AutomaticTestWorker.NOTIFICATION_ID)
        assertNotEquals(ConnectionTestService.RESULT_ID, AutomaticTestWorker.NOTIFICATION_ID)
    }

    private val hour = AutomaticTestPolicy.HOUR
    private val now = 36 * hour
    private val day = 42L
    private val utc = ZoneId.of("UTC")
    private fun candidate(s: AutomaticTestSettings = AutomaticTestSettings(scheduled = true),
                          l: AutomaticTestLedger = AutomaticTestLedger(enabledAt = now - 6 * hour),
                          active: Boolean = false, manual: Long = 0, off: Boolean = true) =
        AutomaticTestPolicy.candidate(s, l, now, day, active, manual, off, utc)
    private fun history(down: List<Float> = List(60) { .3f }, up: List<Float> = List(60) { .05f }, current: Long = 120) =
        DishyGrpcClient.HistoryReading(current, down, up, emptyList(), emptyList(), emptyList())

    @Test fun defaultsNeverRun() { assertNull(candidate(AutomaticTestSettings())) }
    @Test fun firstScheduledRunWaitsForNextWallClockSlot() {
        assertNull(candidate(l = AutomaticTestLedger(enabledAt = now)))
        assertEquals(AutomaticTestTrigger.SCHEDULED,
            candidate(l = AutomaticTestLedger(enabledAt = now - 6 * hour + 1)))
        assertEquals(AutomaticTestTrigger.SCHEDULED, candidate())
    }
    @Test fun failedOrCancelledAttemptsDelayWithoutDisablingFutureRuns() {
        assertNull(candidate(l = AutomaticTestLedger(enabledAt = 1, lastScheduled = now)))
        assertEquals(AutomaticTestTrigger.SCHEDULED,
            candidate(l = AutomaticTestLedger(enabledAt = 1, lastScheduled = now - 6 * hour, lastAttempt = now - 6 * hour)))
    }
    @Test fun activeManualOrExtremeExecutionBlocksBothModes() {
        assertNull(candidate(active = true))
        assertNull(candidate(AutomaticTestSettings(smart = true), active = true))
    }
    @Test fun recentManualExecutionBlocksAutomation() { assertNull(candidate(manual = now - hour + 1)) }
    @Test fun globalDailyBudgetCannotBeBypassedByOtherMode() {
        val l = AutomaticTestLedger(enabledAt = 1, day = day, attempts = 8)
        assertNull(candidate(l = l)); assertNull(candidate(AutomaticTestSettings(smart = true), l))
    }
    @Test fun newDayResetsBudgetButKeepsCooldown() {
        assertEquals(AutomaticTestTrigger.SCHEDULED, candidate(l = AutomaticTestLedger(enabledAt = 1, day = day - 1, attempts = 8)))
        assertNull(candidate(l = AutomaticTestLedger(enabledAt = 1, day = day - 1, attempts = 8, lastAttempt = now - 1)))
    }
    @Test fun smartRequiresScreenOffAndCooldownAndItsOwnDailyBudget() {
        val s = AutomaticTestSettings(smart = true)
        assertNull(candidate(s, off = false))
        assertNull(candidate(s, AutomaticTestLedger(lastSmartAttempt = now - 11 * hour)))
        assertNull(candidate(s, AutomaticTestLedger(day = day, smartAttempts = 2)))
        assertEquals(AutomaticTestTrigger.SMART, candidate(s))
    }
    @Test fun idleProbeCannotRunContinuously() {
        assertNull(candidate(AutomaticTestSettings(smart = true), AutomaticTestLedger(lastProbe = now - hour + 1)))
    }
    @Test fun clockRollbackDoesNotTriggerTestStorm() {
        assertNull(candidate(l = AutomaticTestLedger(lastAttempt = now + hour)))
    }
    @Test fun scheduledWinsWhenBothEligible() {
        assertEquals(AutomaticTestTrigger.SCHEDULED, candidate(AutomaticTestSettings(scheduled = true, smart = true)))
    }
    @Test fun malformedSettingsGetSafeDefaults() {
        val s = AutomaticTestPolicy.normalize(AutomaticTestSettings(intervalHours = 0, cooldownHours = -1, smartDailyLimit = 100))
        assertEquals(6, s.intervalHours); assertEquals(12, s.cooldownHours); assertEquals(8, s.smartDailyLimit)
    }
    @Test fun dailyFrequenciesAreSpacedAndSupportedByNormalization() {
        AutomaticTestPolicy.frequencies.zip(listOf(12, 6, 4, 3)).forEach { (frequency, hours) ->
            assertEquals(hours, AutomaticTestPolicy.intervalFor(frequency))
            val s = AutomaticTestPolicy.normalize(AutomaticTestSettings(intervalHours = hours, cooldownHours = hours, smartDailyLimit = frequency))
            assertEquals(hours, s.intervalHours); assertEquals(hours, AutomaticTestPolicy.smartCooldownHours(s))
        }
    }
    @Test fun detailedSmartCannotSpendEightOpportunitiesBackToBack() {
        val s = AutomaticTestSettings(smart = true, cooldownHours = 3, smartDailyLimit = 8)
        assertNull(candidate(s, AutomaticTestLedger(lastSmartAttempt = now - 2 * hour)))
        assertEquals(AutomaticTestTrigger.SMART, candidate(s, AutomaticTestLedger(lastSmartAttempt = now - 3 * hour)))
        assertNull(candidate(s, AutomaticTestLedger(day = day, attempts = 8, smartAttempts = 4)))
    }
    @Test fun mixedAutomationSharesBudgetAndManualCooldown() {
        val s = AutomaticTestSettings(scheduled = true, intervalHours = 3, smart = true, cooldownHours = 3, smartDailyLimit = 8)
        val l = AutomaticTestLedger(enabledAt = 1, day = day, attempts = 7, smartAttempts = 3)
        assertNotNull(candidate(s, l))
        assertNull(candidate(s, l.copy(attempts = 8)))
        assertNull(candidate(s, l, manual = now - hour + 1))
        assertEquals(7, AutomaticTestPolicy.usedToday(l, day)); assertEquals(0, AutomaticTestPolicy.usedToday(l, day + 1))
    }
    @Test fun nextOpportunityRespectsSharedCooldownAndProbeWithoutInventingExactSchedule() {
        val s = AutomaticTestSettings(scheduled = true, intervalHours = 4)
        val l = AutomaticTestLedger(enabledAt = now, lastAttempt = now)
        assertEquals(now + 4 * hour, AutomaticTestPolicy.nextEligible(s, l, false, now, now, utc))
        assertEquals(now + hour, AutomaticTestPolicy.nextEligible(s.copy(smart = true), l.copy(lastProbe = now), true, now, now, utc))
    }
    @Test fun scheduledFrequenciesUseEvenLocalSlotsAndMiddayEnableSkipsPastSlots() {
        val date = LocalDate.of(2026, 10, 14)
        val start = date.atStartOfDay(utc).toInstant().toEpochMilli()
        for (frequency in AutomaticTestPolicy.frequencies) {
            val s = AutomaticTestSettings(scheduled = true, intervalHours = AutomaticTestPolicy.intervalFor(frequency))
            val slots = AutomaticTestPolicy.plannedSlots(s, date.toEpochDay(), utc)
            assertEquals(frequency, slots.size)
            assertEquals((0 until frequency).map { start + it * s.intervalHours * hour }, slots.map { it.plannedAt })
            val enabledAt = start + 10 * hour + hour / 2
            val next = AutomaticTestPolicy.nextScheduled(s, AutomaticTestLedger(enabledAt = enabledAt), enabledAt, utc)
            assertTrue(next.plannedAt > enabledAt)
            assertEquals(next.plannedAt, AutomaticTestPolicy.nextScheduled(s,
                AutomaticTestLedger(enabledAt = enabledAt), enabledAt + 1, utc).plannedAt)
        }
    }
    @Test fun delayedWorkerUsesCurrentSlotWithoutCatchingUpMissedSlots() {
        val date = LocalDate.of(2026, 10, 14)
        val start = date.atStartOfDay(utc).toInstant().toEpochMilli()
        val s = AutomaticTestSettings(scheduled = true, intervalHours = 6)
        val ledger = AutomaticTestLedger(enabledAt = start - hour)
        val delayed = start + 18 * hour + 45 * 60_000
        val current = AutomaticTestPolicy.nextScheduled(s, ledger, delayed, utc)
        assertEquals(start + 18 * hour, current.plannedAt)
        assertEquals(AutomaticTestTrigger.SCHEDULED,
            AutomaticTestPolicy.candidate(s, ledger, delayed, date.toEpochDay(), false, 0, true, utc))
        assertNull(AutomaticTestPolicy.candidate(s, ledger.copy(scheduledSlot = current.key,
            lastScheduled = delayed, lastAttempt = delayed), delayed + hour, date.toEpochDay(), false, 0, true, utc))
    }
    @Test fun midnightAndTimezoneRollbackDoNotReuseConsumedSlotsOrBudget() {
        val date = LocalDate.of(2026, 10, 14)
        val start = date.atStartOfDay(utc).toInstant().toEpochMilli()
        val s = AutomaticTestSettings(scheduled = true, intervalHours = 6)
        val previous = AutomaticTestPolicy.scheduledSlot(s, start - hour, utc)
        val today = AutomaticTestPolicy.scheduledSlot(s, start + 1, utc)
        val ledger = AutomaticTestLedger(enabledAt = start - 2 * hour, scheduledSlot = previous.key,
            day = date.toEpochDay() - 1, attempts = 8, lastAttempt = start - 2 * hour)
        assertEquals(today.key, AutomaticTestPolicy.nextScheduled(s, ledger, start + 1, utc).key)
        assertEquals(0, AutomaticTestPolicy.usedToday(ledger, date.toEpochDay()))
        val movedBack = ledger.copy(scheduledSlot = today.key, day = date.toEpochDay(), attempts = 8)
        assertEquals(8, AutomaticTestPolicy.usedToday(movedBack, date.toEpochDay() - 1))
        assertNull(AutomaticTestPolicy.candidate(s, movedBack, start - hour,
            date.toEpochDay() - 1, false, 0, true, utc))
        assertEquals(date.toEpochDay() - 1, AutomaticTestPolicy.localDay(start - 1, utc))
        assertEquals(date.toEpochDay(), AutomaticTestPolicy.localDay(start, utc))
        assertEquals(date.toEpochDay() - 1,
            AutomaticTestPolicy.localDay(start + hour, ZoneId.of("America/Sao_Paulo")))
    }
    @Test fun daylightSavingKeepsDistinctLocalDailySlots() {
        val zone = ZoneId.of("America/New_York")
        val s = AutomaticTestSettings(scheduled = true, intervalHours = 3)
        for (date in listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1))) {
            val slots = AutomaticTestPolicy.plannedSlots(s, date.toEpochDay(), zone)
            assertEquals(8, slots.size)
            assertEquals(8, slots.map { it.key }.distinct().size)
            assertEquals(listOf(0, 3, 6, 9, 12, 15, 18, 21),
                slots.map { Instant.ofEpochMilli(it.plannedAt).atZone(zone).hour })
        }
    }
    @Test fun typedStatusSeparatesOwnRunManualBlockCooldownAndDailyLimit() {
        val s = AutomaticTestSettings(scheduled = true)
        val l = AutomaticTestLedger(enabledAt = now - 6 * hour)
        fun status(active: Boolean = false, origin: TestOrigin = TestOrigin.UNKNOWN,
                   ledger: AutomaticTestLedger = l) = AutomaticTestPolicy.screenStatus(s, ledger, false,
            now, day, active, origin, 0, true, zone = utc)
        assertEquals(AutomaticScreenStatus.OVERDUE, status())
        assertEquals(AutomaticScreenStatus.RUNNING, status(true, TestOrigin.SCHEDULED))
        assertEquals(AutomaticScreenStatus.TEMPORARILY_BLOCKED, status(true, TestOrigin.MANUAL))
        assertEquals(AutomaticScreenStatus.COOLDOWN, status(ledger = l.copy(lastAttempt = now - 1)))
        assertEquals(AutomaticScreenStatus.DAILY_LIMIT_REACHED, status(ledger = l.copy(day = day, attempts = 8)))
    }
    @Test fun frequencyChangeAndRecreatedLedgerDoNotRepeatScheduledSlot() {
        val date = LocalDate.of(2026, 10, 14)
        val start = date.atStartOfDay(utc).toInstant().toEpochMilli()
        val four = AutomaticTestSettings(scheduled = true, intervalHours = 6)
        val runAt = start + 12 * hour + hour / 4
        val usedSlot = AutomaticTestPolicy.scheduledSlot(four, runAt, utc)
        val restored = AutomaticTestLedger(enabledAt = start - hour, scheduledSlot = usedSlot.key,
            lastScheduled = runAt, lastAttempt = runAt)
        assertNull(AutomaticTestPolicy.candidate(four, restored, start + 13 * hour,
            date.toEpochDay(), false, 0, true, utc))
        val eight = four.copy(intervalHours = 3)
        val changed = restored.copy(enabledAt = start + 13 * hour, scheduledSlot = -1)
        assertNull(AutomaticTestPolicy.candidate(eight, changed, start + 14 * hour,
            date.toEpochDay(), false, 0, true, utc))
        assertEquals(start + 15 * hour, AutomaticTestPolicy.nextScheduled(eight, changed,
            start + 14 * hour, utc).plannedAt)
    }
    @Test fun smartStatusIsMaximumNotPromiseAndProbeIsExplicit() {
        val s = AutomaticTestSettings(smart = true, cooldownHours = 3, smartDailyLimit = 8)
        fun status(ledger: AutomaticTestLedger = AutomaticTestLedger(), evaluation: Long = 0,
                   error: Long = 0, notifications: Boolean = true) =
            AutomaticTestPolicy.screenStatus(s, ledger, true, now, day, false,
                TestOrigin.UNKNOWN, 0, notifications, evaluation, error, utc)
        assertEquals(AutomaticScreenStatus.WAITING, status())
        assertEquals(AutomaticScreenStatus.EVALUATING, status(evaluation = now - 1_000))
        assertEquals(AutomaticScreenStatus.ERROR, status(error = now - 1_000))
        assertEquals(AutomaticScreenStatus.TEMPORARILY_BLOCKED, status(notifications = false))
        assertEquals(AutomaticScreenStatus.COOLDOWN, status(ledger = AutomaticTestLedger(lastProbe = now - 1)))
        assertEquals(AutomaticScreenStatus.DAILY_LIMIT_REACHED,
            status(ledger = AutomaticTestLedger(day = day, smartAttempts = 8)))
        assertEquals(AutomaticScreenStatus.DISABLED,
            AutomaticTestPolicy.screenStatus(s.copy(smart = false), AutomaticTestLedger(), true,
                now, day, false, TestOrigin.UNKNOWN, 0, true, zone = utc))
    }
    @Test fun legacySettingsRemainValidAndSafe() {
        val legacy = AutomaticTestSettings(smart = true, smartDailyLimit = 1, cooldownHours = 12, intervalHours = 24)
        assertEquals(legacy, AutomaticTestPolicy.normalize(legacy))
        assertEquals(12, AutomaticTestPolicy.smartCooldownHours(legacy))
    }
    @Test fun validZeroMeansIdleButAbsentIsNotZero() {
        assertTrue(AutomaticTestPolicy.lowUsage(history(List(60) { 0f }, List(60) { 0f })))
        assertFalse(AutomaticTestPolicy.lowUsage(history(emptyList())))
        assertFalse(AutomaticTestPolicy.lowUsage(history(up = emptyList())))
    }
    @Test fun incompleteOrInvalidWindowCannotTriggerSmartTest() {
        assertFalse(AutomaticTestPolicy.lowUsage(history(List(59) { 0f })))
        assertFalse(AutomaticTestPolicy.lowUsage(history(current = 59)))
        assertFalse(AutomaticTestPolicy.lowUsage(history(List(60) { if (it == 14) Float.NaN else 0f })))
        assertFalse(AutomaticTestPolicy.lowUsage(history(up = List(60) { -1f })))
    }
    @Test fun sustainedUseOrShortBurstPreventsSmartTest() {
        assertFalse(AutomaticTestPolicy.lowUsage(history(List(60) { 1.1f })))
        assertFalse(AutomaticTestPolicy.lowUsage(history(List(60) { if (it == 0) 3f else 0f })))
        assertFalse(AutomaticTestPolicy.lowUsage(history(up = List(60) { .3f })))
        assertTrue(AutomaticTestPolicy.lowUsage(history()))
    }
    @Test fun independentRingLengthsUseNewestMinuteNotFirstValues() {
        val data = List(120) { if (it < 60) 0f else 10f }
        // current 180: counters 120..179 map to indices 0..59 of the 120-slot ring.
        assertTrue(AutomaticTestPolicy.lowUsage(history(data, List(90) { 0f }, 180)))
        assertFalse(AutomaticTestPolicy.lowUsage(history(data, List(90) { 0f }, 120)))
    }
}
