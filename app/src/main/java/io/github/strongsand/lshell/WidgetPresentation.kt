package io.github.strongsand.lshell

import kotlin.math.min
import kotlin.math.roundToInt
import java.util.Locale

/** Layout contracts in usable dp, never launcher cell counts. */
internal enum class OrbitWidgetLayout(val width: Int, val height: Int) {
    GLANCE(144, 96), COMPACT(160, 208), HORIZONTAL(300, 132), PRIMARY(260, 208),
    HERO(240, 288), EXPANDED(320, 352)
}

internal fun orbitWidgetLayout(width: Float, height: Float, fontScale: Float = 1f): OrbitWidgetLayout {
    // Keep body text accessible: reveal less context instead of shrinking every label.
    val textHeight = height / fontScale.coerceAtLeast(1f)
    return when {
        width >= 320 && textHeight >= 352 -> OrbitWidgetLayout.EXPANDED
        width >= 260 && textHeight >= 208 -> OrbitWidgetLayout.PRIMARY
        width >= 240 && textHeight >= 288 -> OrbitWidgetLayout.HERO
        width >= 300 && textHeight >= 132 -> OrbitWidgetLayout.HORIZONTAL
        width >= 160 && textHeight >= 208 -> OrbitWidgetLayout.COMPACT
        else -> OrbitWidgetLayout.GLANCE
    }
}

/** Preserve a square map and reserve the shared header and right-hand reading column. */
internal fun widgetPrimaryMapSide(width: Float, height: Float): Int =
    min(176f, min(height - 72f, (width - 32f) * .55f)).coerceAtLeast(0f).toInt()

internal enum class ObstructionWidgetState { FREE, LIGHT, RELEVANT, PARTIAL, NO_DATA, OFFLINE, LOADING }
internal data class ObstructionPresentation(
    val percent: Float?, val coverage: Float?, val state: ObstructionWidgetState, val cachedReading: Boolean
)

internal fun obstructionPresentation(
    value: Float?, coverage: Float?, offline: Boolean, cached: Boolean = false, loading: Boolean = false
): ObstructionPresentation {
    val percent = value?.takeIf { it.isFinite() && it in 0f..100f }
    val surveyed = coverage?.takeIf { it.isFinite() && it in 0f..100f }
    val state = when {
        loading -> ObstructionWidgetState.LOADING
        offline -> ObstructionWidgetState.OFFLINE
        percent == null -> ObstructionWidgetState.NO_DATA
        surveyed != null && surveyed < 99.5f -> ObstructionWidgetState.PARTIAL
        percent >= 5f -> ObstructionWidgetState.RELEVANT
        percent >= 0.1f -> ObstructionWidgetState.LIGHT
        else -> ObstructionWidgetState.FREE
    }
    return ObstructionPresentation(percent, surveyed, state, cached)
}

internal enum class SkyCell { UNKNOWN, FREE, PARTIAL, BLOCKED }
internal fun skyCell(value: Float): SkyCell = when {
    !value.isFinite() || value !in 0f..1f -> SkyCell.UNKNOWN
    value >= 0.995f -> SkyCell.FREE
    value >= 0.75f -> SkyCell.PARTIAL
    else -> SkyCell.BLOCKED
}

internal fun validSkyGrid(rows: Int, columns: Int, values: List<Float>): Boolean =
    rows > 1 && columns > 1 && rows.toLong() * columns == values.size.toLong()

/** Same circular sky footprint as the map, excluding outside cells and missing/non-finite data. */
internal fun skyCoverage(rows: Int, columns: Int, values: List<Float>): Float? {
    if (!validSkyGrid(rows, columns, values)) return null
    val cx = (columns - 1) / 2f
    val cy = (rows - 1) / 2f
    val radius = min(cx, cy)
    var sky = 0
    var surveyed = 0
    values.forEachIndexed { index, value ->
        val x = (index % columns - cx) / radius
        val y = (index / columns - cy) / radius
        if (x * x + y * y <= 1f) {
            sky++
            if (skyCell(value) != SkyCell.UNKNOWN) surveyed++
        }
    }
    return if (sky == 0) null else surveyed * 100f / sky
}

internal fun widgetBitmapSide(displayDp: Float, density: Float): Int =
    (displayDp * density).roundToInt().coerceIn(96, 384)

/** Only the headline adjusts to a narrow slot; body labels retain system font scaling. */
internal fun widgetHeadlineSize(value: String, availableDp: Float, fontScale: Float, preferred: Int): Int =
    (availableDp / (value.length.coerceAtLeast(1) * 0.62f * fontScale.coerceAtLeast(1f)))
        .toInt().coerceIn(12, preferred)

internal fun widgetPendingCount(count: Long, locale: Locale): String = when {
    count < 10_000L -> java.text.NumberFormat.getIntegerInstance(locale).format(count)
    count < 1_000_000L -> String.format(locale, "≈%.0fk", count / 1_000.0)
    count < 1_000_000_000L -> String.format(locale, "≈%.0fM", count / 1_000_000.0)
    else -> String.format(locale, "≈%.0fG", count / 1_000_000_000.0)
}

internal fun widgetReportCoverage(start: Long, end: Long, monitoredMs: Long): Float? {
    if (end <= start) return null
    return (monitoredMs.coerceAtLeast(0L).toDouble() / (end - start) * 100).coerceIn(0.0, 100.0).toFloat()
}

internal enum class BeaconWidgetState { SETUP, OFFLINE, STORAGE_WARNING, DISH_UNAVAILABLE, RECORDING, ONLINE }
internal fun beaconWidgetState(configured: Boolean, online: Boolean, recording: Boolean,
    storageHealthy: Boolean = true, dishAvailable: Boolean? = null): BeaconWidgetState = when {
    !configured -> BeaconWidgetState.SETUP
    !online -> BeaconWidgetState.OFFLINE
    !storageHealthy -> BeaconWidgetState.STORAGE_WARNING
    dishAvailable == false -> BeaconWidgetState.DISH_UNAVAILABLE
    recording -> BeaconWidgetState.RECORDING
    else -> BeaconWidgetState.ONLINE
}

internal enum class OrbitWidgetDestination(val tab: String) { OBSTRUCTION("DISH"), REPORT("REPORTS"), BEACON("BEACON") }
