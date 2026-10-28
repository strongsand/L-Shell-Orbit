package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.data.TelemetrySample

internal data class SparklineBand(val left: Float, val right: Float) { val width get() = right - left }

internal object HomeSparklinePolicy {
    /** Strong across the free corridor; fade only at boundaries adjacent to protected text. */
    fun opacityStops(band: SparklineBand, occupied: List<SparklineBand>): List<Pair<Float, Float>> {
        if (band.width <= 0f) return listOf(0f to 0f, 1f to 0f)
        val leftText = occupied.any { it.right <= band.left && band.left - it.right <= 8.1f }
        val rightText = occupied.any { it.left >= band.right && it.left - band.right <= 8.1f }
        val fade = (24f / band.width).coerceAtMost(.22f)
        return listOf(0f to if (leftText) 0f else .95f,
            fade to .95f, (1f - fade) to .95f, 1f to if (rightText) 0f else .95f)
    }

    /** Only already-delivered observations. No query, interpolation, or guessed support. */
    fun series(samples: List<TelemetrySample>, kind: MetricDetailKind): MetricHistorySeries? {
        if (kind == MetricDetailKind.SIGNAL) return null
        val recent = samples.takeLast(240).distinctBy { it.timestamp }.sortedBy { it.timestamp }
        val last = recent.lastOrNull()?.timestamp ?: return null
        if (last == Long.MAX_VALUE) return null
        val points = recent.filter { it.timestamp >= last - 120_000L }.map { sample ->
            val raw = when (kind) {
                MetricDetailKind.DOWNLOAD -> sample.downloadMbps
                MetricDetailKind.UPLOAD -> sample.uploadMbps
                MetricDetailKind.LATENCY -> sample.latencyMs
                MetricDetailKind.POWER -> sample.powerWatts
                else -> null
            }
            MetricHistoryPoint(sample.timestamp, raw?.takeIf { it.isFinite() && it >= 0f }?.let {
                TrafficValue(it.toDouble(), TrafficOrigin.LOCAL_PHONE, if (sample.isHistory) 1_000L else 2_000L)
            })
        }
        if (!points.zipWithNext().any { (a, b) -> chartConnected(a, b) }) return null
        return MetricHistorySeries(last - 120_000L, last + 1, points, 0, emptyList())
    }

    /** Largest empty horizontal corridor; text/icon bounds include an extra 8dp guard.
     * A tall card, narrow corridor or insufficient data simply has no decoration. */
    fun band(width: Float, height: Float, occupied: List<SparklineBand>): SparklineBand? {
        if (width < 180f || height <= 0f || height > 150f || width / height < 2.6f || occupied.isEmpty()) return null
        var left = 12f
        val end = width - 12f
        val free = mutableListOf<SparklineBand>()
        occupied.sortedBy { it.left }.forEach { block ->
            val start = (block.left - 8f).coerceIn(12f, end)
            if (start > left) free += SparklineBand(left, start)
            left = maxOf(left, block.right + 8f).coerceAtMost(end)
        }
        if (left < end) free += SparklineBand(left, end)
        return free.maxByOrNull { it.width }?.takeIf { it.width >= 48f }
    }
}
