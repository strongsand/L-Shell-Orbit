package io.github.strongsand.lshell

import kotlin.math.*

internal enum class SkyCellClass { UNREAD, CLEAR, PARTIAL, BLOCKED }
internal data class RevealSkyPoint(val east: Float, val north: Float, val radius: Float, val kind: SkyCellClass)

/** Geometry/classification matches the map renderer; wave progress never edits these cells. */
internal object ObstructionRevealPolicy {
    fun cells(columns: Int, rows: Int, fractions: List<Float>, terminalFrame: Boolean, azimuthDegrees: Float): List<RevealSkyPoint> {
        val cx = (columns - 1) / 2f; val cy = (rows - 1) / 2f
        val radius = min(cx, cy).coerceAtLeast(1f)
        val azimuth = (azimuthDegrees.takeIf { it.isFinite() } ?: 0f) * PI.toFloat() / 180f
        val sine = sin(azimuth); val cosine = cos(azimuth)
        return fractions.mapIndexedNotNull { index, usable ->
            val e = (index % columns - cx) / radius
            val n = (cy - index / columns) / radius
            val squared = e * e + n * n
            if (squared > 1.02f) return@mapIndexedNotNull null
            val east = if (terminalFrame) e * cosine - n * sine else e
            val north = if (terminalFrame) -e * sine - n * cosine else n
            val obstruction = 1f - usable
            RevealSkyPoint(east, north, sqrt(squared), when {
                usable < 0f -> SkyCellClass.UNREAD
                obstruction <= .005f -> SkyCellClass.CLEAR
                obstruction <= .25f -> SkyCellClass.PARTIAL
                else -> SkyCellClass.BLOCKED
            })
        }
    }

    fun alpha(progress: Float, radius: Float): Float {
        val fraction = ((progress * 1.2f - radius) / .2f).coerceIn(0f, 1f)
        return fraction * fraction * (3f - 2f * fraction)
    }
}
