package io.github.strongsand.lshell

import kotlin.math.abs
import kotlin.math.max

internal enum class CardMetricProfile(val baselineFloor: Float, val adverse: Boolean = false) {
    THROUGHPUT(1f), LATENCY(10f, true), LOSS(1f, true), ENERGY(10f)
}

internal data class SmallChartStyle(val strokeDp: Float, val markerDp: Float,
    val fillAlpha: Float, val gridAlpha: Float, val insetDp: Float, val buckets: Int)

/** Drawing policy only. Never changes scales, measurements, support or inspection. */
internal object CardVisualPolicy {
    fun style(values: List<Float>, widthDp: Float, heightDp: Float, profile: CardMetricProfile): SmallChartStyle {
        val finite = values.filter { it.isFinite() }
        val range = (finite.maxOrNull() ?: 0f) - (finite.minOrNull() ?: 0f)
        var variation = 0.0; var pairs = 0
        for (index in 1 until values.size) {
            val a = values[index - 1]; val b = values[index]
            if (a.isFinite() && b.isFinite()) { variation += abs(b.toDouble() - a); pairs++ }
        }
        val roughness = if (range > 0 && pairs > 0) (variation / pairs / range).toFloat().coerceIn(0f, 1f) else 0f
        val width = widthDp.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        val density = (finite.size / width / 2f).coerceIn(0f, 1f)
        val nervous = (roughness * 3f * density).coerceIn(0f, 1f)
        val small = width < 210f || heightDp < 110f
        val stroke = ((if (small) 2.25f else 2.7f) - 1.05f * nervous - .35f * density +
            (if (profile == CardMetricProfile.LATENCY) .15f else 0f)).coerceIn(1.1f, 2.9f)
        return SmallChartStyle(stroke, if (small) 3.2f else 4f,
            if (profile == CardMetricProfile.LOSS) .035f else (.14f - .10f * density).coerceAtLeast(.035f),
            if (small) .14f else .20f, if (small) 5f else 7f,
            (width / 4f).toInt().coerceIn(24, 720))
    }

    /** Recent valid observations, excluding the current sample; missing never substitutes zero.
     * Floors are visual noise guards in each metric's units, not health thresholds. */
    fun emphasis(current: Float?, baseline: List<Float>, profile: CardMetricProfile): Float {
        if (current == null || !current.isFinite() || current < 0f) return 0f
        val valid = baseline.filter { it.isFinite() && it >= 0f }
        if (valid.size < 3) return 0f
        val mean = valid.sumOf { it.toDouble() } / valid.size
        val excess = ((current - mean) / max(mean, profile.baselineFloor.toDouble())).coerceAtLeast(0.0)
        return (excess / (1.0 + excess)).toFloat().coerceIn(0f, 1f)
    }
}
