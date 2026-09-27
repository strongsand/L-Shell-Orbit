package io.github.strongsand.lshell.ar

import kotlin.math.cos
import kotlin.math.sin

/** Uses the same field of view and roll convention as the existing AR markers. */
internal data class HorizonProjection(val x: Float, val y: Float, val normalX: Float, val normalY: Float) {
    fun groundDistance(screenX: Float, screenY: Float): Float =
        (screenX - x) * normalX + (screenY - y) * normalY
}

internal fun projectHorizon(width: Float, height: Float, elevation: Float, roll: Float): HorizonProjection {
    val radians = Math.toRadians(roll.toDouble())
    val nx = sin(radians).toFloat()
    val ny = cos(radians).toFloat()
    val offset = elevation / 60f * width
    return HorizonProjection(width / 2f + nx * offset, height / 2f + ny * offset, nx, ny)
}
