package com.hurricane.lshell.ar

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

internal const val DISH_VIEW_HALF_ANGLE_DEG = 52f

/** Estimated angular field around the boresight reported by the dish. */
internal fun isWithinDishView(
    satelliteAzimuth: Float,
    satelliteElevation: Float,
    dishAzimuth: Float,
    dishElevation: Float,
    halfAngle: Float = DISH_VIEW_HALF_ANGLE_DEG
): Boolean {
    if (!satelliteAzimuth.isFinite() || !satelliteElevation.isFinite() ||
        !dishAzimuth.isFinite() || !dishElevation.isFinite()) return false
    val satAz = Math.toRadians(satelliteAzimuth.toDouble())
    val satEl = Math.toRadians(satelliteElevation.toDouble())
    val dishAz = Math.toRadians(dishAzimuth.toDouble())
    val dishEl = Math.toRadians(dishElevation.toDouble())
    val dot = cos(satEl) * cos(dishEl) * cos(satAz - dishAz) + sin(satEl) * sin(dishEl)
    return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0))) <= halfAngle
}
