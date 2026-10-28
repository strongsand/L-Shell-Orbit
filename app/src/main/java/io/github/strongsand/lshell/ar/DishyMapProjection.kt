package io.github.strongsand.lshell.ar

import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient.ObstructionMapReading
import kotlin.math.*

enum class DishySkyRegion { CLEAR, PARTIAL, OBSTRUCTED, UNMAPPED, OUTSIDE }
data class DishyOverlayCell(val azimuth: Float, val elevation: Float, val region: DishySkyRegion)

/** Approximate zenith-distance projection. No inferred frame, theta or attitude. */
class DishyMapProjection private constructor(private val map: ObstructionMapReading, val theta: Float) {
    private val cx = (map.columns - 1) / 2f
    private val cy = (map.rows - 1) / 2f
    private val radius = min(cx, cy)

    fun region(azimuth: Float, elevation: Float): DishySkyRegion {
        if (!azimuth.isFinite() || !elevation.isFinite() || elevation !in 0f..90f) return DishySkyRegion.OUTSIDE
        val radial = (90f - elevation) / theta
        if (radial > 1f) return DishySkyRegion.OUTSIDE
        val angle = Math.toRadians(azimuth.toDouble())
        val col = (cx + sin(angle) * radial * radius).roundToInt()
        val row = (cy - cos(angle) * radial * radius).roundToInt()
        if (row !in 0 until map.rows || col !in 0 until map.columns) return DishySkyRegion.OUTSIDE
        return classify(map.usableFractions[row * map.columns + col])
    }

    /** At most 24x24 cells, aggregated once off the drawing thread; no extrapolated holes. */
    fun cells(): List<DishyOverlayCell> = buildList {
        val stride = ceil(max(map.rows, map.columns) / 24.0).toInt().coerceAtLeast(1)
        for (row in 0 until map.rows step stride) for (col in 0 until map.columns step stride) {
            var worst = 1f; var valid = 0
            for (y in row until min(row + stride, map.rows)) for (x in col until min(col + stride, map.columns)) {
                val value = map.usableFractions[y * map.columns + x]
                if (value.isFinite() && value in 0f..1f) { worst = min(worst, value); valid++ }
            }
            // Do not paint a whole tile when most of it was not measured.
            val total = min(stride, map.rows - row) * min(stride, map.columns - col)
            if (valid * 2 < total || valid == 0) continue
            val east = (col + (min(stride, map.columns - col) - 1) / 2f - cx) / radius
            val north = (cy - row - (min(stride, map.rows - row) - 1) / 2f) / radius
            val radial = hypot(east, north)
            if (radial > 1f) continue
            add(DishyOverlayCell(((Math.toDegrees(atan2(east.toDouble(), north.toDouble())) + 360) % 360).toFloat(),
                90f - radial * theta, classify(worst)))
        }
    }

    companion object {
        fun from(map: ObstructionMapReading): DishyMapProjection? {
            // UT maps need a trustworthy full attitude transform, absent from our schema.
            // Unknown and missing angular extents cannot be safely placed in geographic AR.
            val theta = map.maxThetaDegrees?.takeIf { it.isFinite() && it > 0f && it <= 90f } ?: return null
            if (map.referenceFrame != ObstructionMapReading.ReferenceFrame.EARTH || map.rows < 3 || map.columns < 3 ||
                map.rows.toLong() * map.columns != map.usableFractions.size.toLong()) return null
            return DishyMapProjection(map, theta)
        }
        fun classify(usable: Float): DishySkyRegion = when {
            !usable.isFinite() || usable !in 0f..1f -> DishySkyRegion.UNMAPPED
            usable >= .995f -> DishySkyRegion.CLEAR
            usable >= .75f -> DishySkyRegion.PARTIAL
            else -> DishySkyRegion.OBSTRUCTED
        }
    }
}
