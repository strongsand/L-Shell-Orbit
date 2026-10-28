package io.github.strongsand.lshell

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TtsSpan
import java.util.Locale

/** Presentation only: reuse the result screen's analysis, never infer live metrics. */
internal object ExtremeTestNotificationModel {
    fun activeMetrics(state: ConnectionTestState): TestMetrics {
        if (state.completed <= 0) return TestMetrics()
        val metrics = ConnectionTestPresentation.metrics(state)
        val last = state.runs.lastOrNull()?.takeIf { it.index == state.completed && it.failure == null }
        return metrics.copy(latency = metrics.latency ?: ConnectionTestPresentation.valid(last?.latencyMs))
    }

    /** Active line has one shared rate unit; latency stays in the expanded native text. */
    fun compactActiveRates(metrics: TestMetrics, locale: Locale): String {
        fun number(value: Float) = String.format(locale, "%.1f", value)
        return listOfNotNull(
            ConnectionTestPresentation.valid(metrics.download)?.let { "↓${number(it)}" },
            ConnectionTestPresentation.valid(metrics.upload)?.let { "↑${number(it)}" }
        ).joinToString(" · ")
    }

    /** Native styles use identical units for the maximum and completed work. */
    fun progress(state: ConnectionTestState): Pair<Int, Int>? =
        if (state.kind == ConnectionTestKind.EXTREME && state.total > 0 &&
            state.completed in 0..state.total) state.total to state.completed else null

    fun compactMetrics(metrics: TestMetrics, locale: Locale, includeRateUnit: Boolean = true): String {
        val rateUnit = if (includeRateUnit) " Mbps" else ""
        fun number(value: Float) = String.format(locale, "%.1f", value)
        return listOfNotNull(
            ConnectionTestPresentation.valid(metrics.download)?.let { "↓ ${number(it)}$rateUnit" },
            ConnectionTestPresentation.valid(metrics.upload)?.let { "↑ ${number(it)}$rateUnit" },
            ConnectionTestPresentation.valid(metrics.latency)?.let { "${number(it)} ms" }
        ).joinToString(" · ")
    }

    fun resultStats(state: ConnectionTestState): ExtremeStats? = state.extreme
        ?.takeIf { state.kind == ConnectionTestKind.EXTREME &&
            state.phase == ConnectionTestPhase.COMPLETED && it.id == state.id && it.runs.isNotEmpty() }
        ?.let { ExtremeStats.from(it) }

    fun qualityLabel(stats: ExtremeStats): Int {
        if (ConnectionTestPresentation.valid(stats.downloadAverage) == null ||
            ConnectionTestPresentation.valid(stats.uploadAverage) == null ||
            ConnectionTestPresentation.valid(stats.downloadStdDev) == null ||
            !stats.lossAverage.isFinite() || stats.lossAverage !in 0f..100f) {
            return R.string.test_done
        }
        return stats.quality.label()
    }

    fun estimatedBytes(state: ConnectionTestState): Long? = state.runs
        .filter { it.failure == null && it.estimatedBytes >= 0L }
        .takeIf { it.isNotEmpty() }?.sumOf { it.estimatedBytes }

    fun lastLoss(state: ConnectionTestState): Float? = state.runs.lastOrNull()
        ?.takeIf { it.failure == null }?.packetLossPercent?.let(ConnectionTestPresentation::valid)
        ?.takeIf { it <= 100f }
}

internal data class ExtremeTestNotificationContent(
    val title: String,
    val compact: CharSequence,
    val expanded: CharSequence,
    val spoken: String
)

internal fun extremeTestNotificationContent(
    context: Context, state: ConnectionTestState
): ExtremeTestNotificationContent {
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val metrics = ExtremeTestNotificationModel.activeMetrics(state)
    val round = context.getString(R.string.test_round, state.currentRound, state.total)
    val detail = listOfNotNull(round, when (state.phase) {
        ConnectionTestPhase.STARTING -> context.getString(R.string.test_preparing)
        ConnectionTestPhase.BETWEEN_ROUNDS -> context.getString(R.string.test_round_interval)
        else -> null
    }).joinToString(" · ")
    val summary = ExtremeTestNotificationModel.compactActiveRates(metrics, locale)
        .takeIf { it.isNotEmpty() }?.let {
            context.getString(R.string.test_last_round_compact_rates, it)
        }
    fun metric(label: Int, value: Float?, unit: Int): String? = value?.let {
        "${context.getString(label)}  ${context.getString(unit, String.format(locale, "%.1f", it))}"
    }
    val expanded = listOfNotNull(detail,
        context.getString(R.string.test_recent_round_label).takeIf { metrics != TestMetrics() },
        metric(R.string.traffic_download, metrics.download, R.string.test_rate_value),
        metric(R.string.traffic_upload, metrics.upload, R.string.test_rate_value),
        metric(R.string.test_latency, metrics.latency, R.string.test_latency_value)
    ).joinToString("\n")
    // Framework templates support multiline text; OEMs still own wrapping/truncation.
    val compact = listOfNotNull(detail, summary).joinToString("\n")
    val spokenRates = listOfNotNull(
        metric(R.string.traffic_download, metrics.download, R.string.test_rate_accessible),
        metric(R.string.traffic_upload, metrics.upload, R.string.test_rate_accessible)
    ).joinToString(", ")
    val spoken = listOfNotNull(detail, spokenRates.takeIf { it.isNotEmpty() }?.let {
        context.getString(R.string.test_recent_round_metrics, it)
    }).joinToString(". ")
    // Keep native content and provide words instead of relying on spoken arrow glyphs.
    val accessible = SpannableString(compact).apply {
        setSpan(TtsSpan.TextBuilder(spoken).build(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val title = context.getString(R.string.function_extreme)
    return ExtremeTestNotificationContent(title, accessible, expanded, "$title. $spoken")
}
