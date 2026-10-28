package io.github.strongsand.lshell

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import kotlin.math.hypot

/** Presentation only: never use these specs to interpolate telemetry numbers. */
internal enum class OrbitMotionRole { TOUCH, STATE, SPATIAL, SETTLE }

internal data class OrbitSpring(val damping: Float, val stiffness: Float)

internal object OrbitMotion {
    const val maximumChartTravelMs = 420L

    fun parameters(role: OrbitMotionRole) = when (role) {
        OrbitMotionRole.TOUCH -> OrbitSpring(.86f, 1200f)
        OrbitMotionRole.STATE -> OrbitSpring(.94f, 650f)
        OrbitMotionRole.SPATIAL -> OrbitSpring(.86f, 420f)
        OrbitMotionRole.SETTLE -> OrbitSpring(.90f, 850f)
    }

    fun <T> spec(role: OrbitMotionRole, enabled: Boolean): FiniteAnimationSpec<T> =
        if (enabled) parameters(role).let { spring(dampingRatio = it.damping, stiffness = it.stiffness) }
        else snap()

    /** Real visual distance in dp, not the number of samples or their time spacing. */
    fun travelDistance(dx: Float, dy: Float, widthDp: Float, heightDp: Float): Float =
        hypot(dx * widthDp, dy * heightDp)

    fun chartSpring(distanceDp: Float): SpringSpec<Float> = spring(
        dampingRatio = .90f,
        stiffness = when {
            distanceDp < 24f -> 1600f
            distanceDp < 120f -> 1000f
            else -> 700f
        }, visibilityThreshold = .0001f
    )
}
