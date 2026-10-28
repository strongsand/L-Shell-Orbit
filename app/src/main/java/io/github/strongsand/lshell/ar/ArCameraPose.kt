package io.github.strongsand.lshell.ar

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot

internal data class ArCameraPose(val azimuth: Float, val elevation: Float, val roll: Float)

/** Rotation-vector matrix: columns are the device axes in East/North/Up coordinates.
 * The back camera looks along -Z; screen-down gravity is (-screenRight.z, screenUp.z).
 * Display rotation uses the same X/Y remapping as SensorManager.remapCoordinateSystem.
 */
internal fun arCameraPose(matrix: FloatArray, displayRotation: Int, previousRoll: Float): ArCameraPose {
    require(matrix.size == 9)
    val (rightUp, topUp) = when (displayRotation) {
        1 -> matrix[7] to -matrix[6] // ROTATION_90: Y, -X
        2 -> -matrix[6] to -matrix[7] // ROTATION_180: -X, -Y
        3 -> -matrix[7] to matrix[6] // ROTATION_270: -Y, X
        else -> matrix[6] to matrix[7]
    }
    val azimuth = Math.toDegrees(atan2(-matrix[2].toDouble(), -matrix[5].toDouble())).toFloat()
    val elevation = Math.toDegrees(asin((-matrix[8]).coerceIn(-1f, 1f).toDouble())).toFloat()
    // At zenith/nadir the projected gravity has no direction: preserve the last valid roll.
    val roll = if (hypot(rightUp, topUp) > 0.01f)
        Math.toDegrees(atan2(-rightUp.toDouble(), topUp.toDouble())).toFloat() else previousRoll
    return ArCameraPose((azimuth + 360f) % 360f, elevation, roll)
}
