package io.github.strongsand.lshell

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class ObservationDaysTest {
    @get:Rule val temp = TemporaryFolder()
    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun time(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun day(date: String, observed: Boolean = false): HealthDay {
        val start = time(date)
        val end = LocalDate.parse(date).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = if (observed) listOf(HistoryEntry(start + 1000, "terminal_history", "Synthetic",
            0f, 0f, 20f, 0f, dishState = "CONNECTED")) else emptyList()
        return ConnectionHealthAnalyzer.day(rows, emptyList(), start, end, zone)
    }
    private fun data(day: HealthDay) = JSONObject().put("key", "${day.start}:${zone.id}")
        .put("start", day.start).put("day", ConnectionHealthJson.day(day))

    @Test fun lessThanOneWeekOfHistoryDoesNotBecomeThirtyNineObservedDays() {
        // Real calendar APIs reproduce the previous refresh's empty month/week padding.
        val now = time("2026-10-09") + 12 * 3_600_000L
        val dates = (0L..30L).map { LocalDate.parse("2026-10-09").minusDays(it) }.toMutableSet()
        for (kind in HealthPeriodKind.entries) {
            val current = HealthPeriod.containing(kind, now, zone)
            var period = HealthPeriod.containing(kind, time("2026-09-09"), zone)
            while (period.end <= current.start) {
                dates += period.days().map { java.time.Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate() }
                period = HealthPeriod.containing(kind, period.end, zone)
            }
        }
        assertEquals(39, dates.size)
        val summaries = dates.sorted().map { day(it.toString(), it >= LocalDate.parse("2026-10-07")) }
        assertEquals(listOf(time("2026-10-07"), time("2026-10-08"), time("2026-10-09")),
            summaries.filter { it.hasPreservedData }.map { it.start })
        assertEquals(36, summaries.count { !it.hasPreservedData })
    }
    @Test fun emptyCalendarDayAndPreservedFlagAloneAreNotEvidence() {
        val empty = day("2026-10-01")
        assertFalse(empty.hasPreservedData)
        assertFalse(empty.copy(resolutionPreserved = true).hasPreservedData)
        assertEquals(empty.duration, empty.longestGapMs)
        assertEquals(4, empty.hours.size)
    }
    @Test fun trueZeroAndCompactedAggregatesRemainPreserved() {
        val real = day("2026-10-07", true)
        assertEquals(0.0, real.metric("download").mean!!, 0.0)
        assertTrue(real.hasPreservedData)
        assertTrue(real.copy(rawRows = 0, resolutionPreserved = true).hasPreservedData)
        assertTrue(day("2026-10-01").copy(stats = mapOf("loss" to HealthDistribution.of(listOf(0.0)))).hasPreservedData)
    }
    @Test fun eventOnlyAndFailedTestOnlyDaysAreNotLost() {
        val empty = day("2026-10-07")
        assertTrue(empty.copy(eventCounts = mapOf("outage" to 1)).hasPreservedData)
        val test = RecordedTest("synthetic", TestOrigin.MANUAL, empty.start + 1000, empty.start + 2000,
            "FAILED", emptyList(), emptyList(), emptyList())
        assertTrue(ConnectionHealthAnalyzer.day(emptyList(), listOf(test), empty.start, empty.end, zone).hasPreservedData)
    }
    @Test fun emptyInstallationExportsNoDailySummaries() {
        roundtrip(listOf(day("2026-10-01"), day("2026-10-02")), legacy = false).use {
            assertEquals(0L, it.manifest.counts[BackupCategory.DAYS])
        }
    }
    @Test fun newArchiveCountsOnlySummariesWithData() {
        roundtrip(listOf(day("2026-10-01"), day("2026-10-07", true)), legacy = false).use {
            assertEquals(1L, it.manifest.counts[BackupCategory.DAYS])
            it.records(BackupCategory.DAYS) { record ->
                assertEquals(time("2026-10-07"), record.getJSONObject("data").getLong("start"))
            }
        }
    }
    @Test fun legacyArchiveStillValidatesButEmptyRowsAreNotRestorable() {
        val days = (0L..38L).map { LocalDate.parse("2026-09-01").plusDays(it) }
            .map { day(it.toString(), it >= LocalDate.parse("2026-10-07")) }
        roundtrip(days, legacy = true).use { backup ->
            assertEquals(39L, backup.manifest.counts[BackupCategory.DAYS]) // Physical ZIP count/hash remain truthful.
            var restorable = 0L; var ignored = 0L
            backup.records(BackupCategory.DAYS) { r ->
                if (BackupRecordCodec.hasRestorableData(BackupCategory.DAYS, r.getJSONObject("data"))) restorable++ else ignored++
            }
            assertEquals(BackupMergeCount(3, 0, 36), BackupMergeCount(restorable, 0, ignored))
            backup.verifyStaged()
        }
    }
    @Test fun cacheEligibilityDoesNotChangeWeekOrMonthCoverageAndGaps() {
        for (kind in HealthPeriodKind.entries) {
            val period = HealthPeriod.containing(kind, time("2026-10-09"), zone)
            val days = period.days(time("2026-10-09") + 3_600_000).map { (a, b) ->
                ConnectionHealthAnalyzer.day(if (a == time("2026-10-07")) listOf(
                    HistoryEntry(a + 1000, "terminal_history", "Synthetic", 0f, 0f, 20f, 0f)) else emptyList(),
                    emptyList(), a, b, zone)
            }
            val cache = days.filter { it.hasPreservedData }.associateBy { it.start }
            val reloaded = days.map { cache[it.start] ?: ConnectionHealthAnalyzer.day(emptyList(), emptyList(), it.start, it.end, zone) }
            assertEquals(ConnectionHealthAnalyzer.combine(days, period.start, days.last().end),
                ConnectionHealthAnalyzer.combine(reloaded, period.start, days.last().end))
            assertEquals(days.size, reloaded.size) // Keep every gap in the denominator.
        }
    }
    @Test fun unrelatedBackupCategoriesAreNeverFiltered() {
        assertTrue(BackupRecordCodec.hasRestorableData(BackupCategory.READINGS, JSONObject()))
        assertTrue(BackupRecordCodec.hasRestorableData(BackupCategory.WEEKLY, JSONObject()))
    }
    private fun roundtrip(days: List<HealthDay>, legacy: Boolean): ValidatedBackup {
        val writer = BackupWriter(temp.newFolder())
        writer.records(BackupCategory.READINGS) { } // Required collection, even for an empty installation.
        writer.records(BackupCategory.DAYS) { emit -> days.forEach { day ->
            val data = data(day)
            if (legacy || BackupRecordCodec.hasRestorableData(BackupCategory.DAYS, data))
                emit(BackupRecordCodec.wrap(BackupCategory.DAYS, data))
        } }
        writer.settings(BackupSettingsCodec.encode(emptyList()))
        val zip = File(temp.newFolder(), "synthetic.zip")
        writer.finish(zip, "synthetic", 2, 8)
        return BackupValidator.validate(zip, temp.newFolder())
    }
}
