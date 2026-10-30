package io.github.strongsand.lshell

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Compatibility bridge for existing gesture callbacks. Custom vibration is retired.
 * Platform text controls and accessibility feedback retain Android's normal behavior. */
@Stable
class DashHaptics {
    fun perform(@Suppress("UNUSED_PARAMETER") feedback: DashFeedback = DashFeedback.TAP) = Unit
}

@Composable
fun rememberDashHaptics(): DashHaptics = remember { DashHaptics() }

@Composable
fun hapticClick(@Suppress("UNUSED_PARAMETER") feedback: DashFeedback = DashFeedback.TAP,
    action: () -> Unit): () -> Unit = action

@Composable
fun hapticToggle(action: (Boolean) -> Unit): (Boolean) -> Unit = action

/** Transform only the drawing; hit targets and gesture semantics stay unchanged. */
@Composable
fun Modifier.pressMotion(source: MutableInteractionSource, compression: Float = 0.97f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed && LocalOrbitMotion.current) compression else 1f,
        OrbitMotion.spec(OrbitMotionRole.TOUCH, LocalOrbitMotion.current), label = "press spring")
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/** A small shared entrance motion that also respects Android's reduced-motion setting. */
@Composable
fun Modifier.expressiveReveal(
    key: Any? = Unit,
    @Suppress("UNUSED_PARAMETER") delayMillis: Int = 0,
    distance: Dp = 12.dp
): Modifier {
    val animationsEnabled = LocalOrbitMotion.current
    val progress = remember(key) { Animatable(if (animationsEnabled) 0f else 1f) }
    val distancePx = with(LocalDensity.current) { distance.toPx() }

    LaunchedEffect(key, animationsEnabled) {
        if (!animationsEnabled) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            // Start immediately; legacy stagger arguments no longer hide controls while waiting.
            progress.animateTo(
                targetValue = 1f,
                animationSpec = OrbitMotion.spec(OrbitMotionRole.SPATIAL, true)
            )
        }
    }

    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * distancePx
        val scale = 0.985f + (0.015f * progress.value)
        scaleX = scale
        scaleY = scale
    }
}
