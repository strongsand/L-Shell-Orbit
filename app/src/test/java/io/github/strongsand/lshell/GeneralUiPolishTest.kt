package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.data.LivePowerReading
import java.io.IOException
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GeneralUiPolishTest {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
    private fun power(value: Float?, timestamp: Long = now) = value?.let { LivePowerReading(timestamp, it) }

    @Test fun failedHistoryReadIsRecoverableAndNeverAnEmptySuccess() = runBlocking {
        val failed = readUiList<HistoryOccurrence> { throw IOException("read failed") }
        assertTrue(failed.isFailure)
        assertNull(failed.getOrNull())
        assertTrue(readUiList<HistoryOccurrence> { emptyList() }.isSuccess)
        assertEquals(emptyList<HistoryOccurrence>(), readUiList<HistoryOccurrence> { emptyList() }.getOrThrow())
    }
    @Test fun cancellingHistoryReadIsNotShownAsAReadFailure() = runBlocking {
        val cancellation = CancellationException("new day selected")
        try {
            readUiList<HistoryOccurrence> { throw cancellation }
            fail("Cancellation must reach the caller")
        } catch (caught: CancellationException) { assertSame(cancellation, caught) }
    }
    @Test fun failedReadCanRetryAndReturnRealEvents() = runBlocking {
        val event = HistoryOccurrence("id", now, "event", category = OccurrenceCategory.CONNECTION)
        var count = 0
        val read: suspend () -> List<HistoryOccurrence> = { if (count++ == 0) throw IOException() else listOf(event) }
        assertTrue(readUiList(read).isFailure)
        assertEquals(listOf(event), readUiList(read).getOrThrow())
    }
    @Test fun dailyReportReadDistinguishesStorageFailureFromTheFirstEmptyReportList() = runBlocking {
        assertTrue(readUiList<DailyReport> { throw IOException("report storage unavailable") }.isFailure)
        val retry = readUiList<DailyReport> { emptyList() }
        assertTrue(retry.isSuccess)
        assertTrue(retry.getOrThrow().isEmpty())
    }
    @Test fun changingHistoryDayNeverDisplaysAnOldEmptyOrErrorState() {
        val old = UiLoadStatus("yesterday" to 0).failed()
        assertEquals(UiLoadPhase.LOADING, old.phaseFor("today" to 0))
        assertEquals(UiLoadPhase.LOADING, old.phaseFor("yesterday" to 1))
    }
    @Test fun explicitMissingReportNeverOpensTheCurrentWeekInstead() = runBlocking {
        var currentRead = false
        var savedKey: String? = null
        try {
            readDisplayedHealthReport("deleted", saved = { savedKey = it; null }, current = { currentRead = true; null })
            fail("Missing saved report must become a recoverable UI error")
        } catch (_: IllegalStateException) { }
        assertEquals("deleted", savedKey)
        assertFalse(currentRead)
    }
    @Test fun explicitReportUsesItsOwnSavedDataAndNormalEntryUsesCurrentData() = runBlocking {
        val period = HealthPeriod.containing(HealthPeriodKind.MONTH, now, utc)
        val report = ConnectionHealthAnalyzer.report(period, emptyList(), null, now, emptyList())
        assertSame(report, readDisplayedHealthReport(period.key, saved = { report }, current = { error("Wrong destination") }))
        assertSame(report, readDisplayedHealthReport(null, saved = { error("Wrong destination") }, current = { report }))
    }
    @Test fun powerZeroIsARealReadingRatherThanMissing() {
        assertEquals(0f, recentAntennaPower(power(0f), now))
        assertNull(recentAntennaPower(power(null), now))
        assertNull(recentAntennaPower(null, now))
    }
    @Test fun powerRejectsFutureStaleNegativeAndNonFiniteValues() {
        for (sample in listOf(power(20f, now + 1), power(20f, now - 15_001),
                power(-1f), power(Float.NaN), power(Float.POSITIVE_INFINITY)))
            assertNull(recentAntennaPower(sample, now))
        assertEquals(20f, recentAntennaPower(power(20f, now - 15_000), now))
    }
    @Test fun mostRecentValidPowerUpdatesWithoutChangingTheSampleValue() {
        assertEquals(25.75f, recentAntennaPower(power(25.75f), now))
        assertEquals(0f, recentAntennaPower(power(0f), now))
    }
    @Test fun automationSameDayShowsTimeAndOtherDaysIncludeTheirDateInEveryLocale() {
        for (locale in listOf(Locale.forLanguageTag("pt-BR"), Locale.ENGLISH, Locale.forLanguageTag("es"))) {
            val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply { timeZone = TimeZone.getTimeZone(utc) }
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).apply { timeZone = TimeZone.getTimeZone(utc) }
            assertEquals(time.format(Date(now)), automationTimeLabel(now, now, locale, utc))
            for (at in listOf(now - 86_400_000, now + 86_400_000))
                assertEquals(date.format(Date(at)), automationTimeLabel(at, now, locale, utc))
        }
    }
    @Test fun dateLabelsRespectTheLocalDayInsteadOfTheUtcDay() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val before = Instant.parse("2026-10-17T02:30:00Z").toEpochMilli()
        val after = Instant.parse("2026-10-17T03:30:00Z").toEpochMilli()
        val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.ENGLISH)
            .apply { timeZone = TimeZone.getTimeZone(zone) }
        assertEquals(formatter.format(Date(after)), automationTimeLabel(after, before, Locale.ENGLISH, zone))
    }
    @Test fun smallLandscapeAndLargeFontPickersUseCompactEntry() {
        assertFalse(compactTimeEntry(393, 851, 1f))
        assertTrue(compactTimeEntry(851, 393, 1f))
        assertTrue(compactTimeEntry(320, 480, 1.5f))
        assertTrue(compactTimeEntry(393, 851, 1.5f))
    }
    @Test fun unknownBeaconImplementationIsNeverAssumedToBeEsp32() {
        assertEquals(R.string.general_beacon_android, beaconImplementationLabel("android"))
        assertEquals(R.string.general_beacon_esp32, beaconImplementationLabel("ESP32"))
        for (type in listOf(null, "", "other")) assertEquals(R.string.beacon_title, beaconImplementationLabel(type))
    }
}
