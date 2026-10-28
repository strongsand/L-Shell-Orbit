package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.data.LivePowerReading
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A read failure is not an empty day; cancellation still belongs to the caller. */
internal suspend fun <T> readUiList(read: suspend () -> List<T>): Result<List<T>> =
    try { Result.success(read()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }

/** Explicit saved-report requests must never silently fall back to another report. */
internal suspend fun readDisplayedHealthReport(key: String?, saved: suspend (String) -> HealthReport?,
    current: suspend () -> HealthReport?): HealthReport? =
    if (key != null) checkNotNull(saved(key)) else current()

internal fun automationTimeLabel(at: Long, now: Long, locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault()): String {
    val sameDay = Instant.ofEpochMilli(at).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return (if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT, locale)
        else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)).apply {
        timeZone = TimeZone.getTimeZone(zone)
    }.format(Date(at))
}

internal fun compactTimeEntry(widthDp: Int, heightDp: Int, fontScale: Float): Boolean =
    widthDp / fontScale < 320 || heightDp / fontScale < 480

/** Independent current power; retain the existing 15-second freshness window. */
internal fun recentAntennaPower(reading: LivePowerReading?, now: Long): Float? =
    reading?.takeIf { it.powerWatts.isFinite() && it.powerWatts >= 0f &&
        now - it.timestamp in 0L..15_000L }?.powerWatts

internal fun beaconImplementationLabel(implementation: String?): Int = when (implementation?.lowercase(Locale.ROOT)) {
    "android" -> R.string.general_beacon_android
    "esp32" -> R.string.general_beacon_esp32
    else -> R.string.beacon_title
}
