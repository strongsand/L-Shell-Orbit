package io.github.strongsand.lshell

import android.animation.ValueAnimator
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Stable
class DashHaptics(private val view: View, private val level: () -> HapticLevel) {
    private var lastFeedback = -100L

    fun perform(feedback: DashFeedback = DashFeedback.TAP) {
        val now = SystemClock.uptimeMillis()
        if (!view.isAttachedToWindow || !view.hasWindowFocus() || !view.isHapticFeedbackEnabled || now - lastFeedback < 70L) return
        lastFeedback = now
        val gesture = hapticGesture(feedback, level()) ?: return
        val effect = when (gesture) {
            HapticGesture.TICK -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            HapticGesture.SOFT_TICK -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            HapticGesture.CONTEXT -> HapticFeedbackConstants.CONTEXT_CLICK
            HapticGesture.CONFIRM -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
            HapticGesture.REJECT -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONTEXT_CLICK
            HapticGesture.START -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.CONTEXT_CLICK
            HapticGesture.END -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CONTEXT_CLICK
            HapticGesture.ON -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CLOCK_TICK
            HapticGesture.OFF -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
        }
        // No forced vibrator fallback: View respects system settings and hardware support.
        runCatching { view.performHapticFeedback(effect) }
    }
}

@Composable
fun rememberDashHaptics(): DashHaptics {
    val view = LocalView.current
    val level = rememberUpdatedState(LocalExperience.current.haptics)
    return remember(view) { DashHaptics(view) { level.value } }
}

@Composable
fun hapticClick(feedback: DashFeedback = DashFeedback.TAP, action: () -> Unit): () -> Unit {
    val haptics = rememberDashHaptics()
    return { haptics.perform(feedback); action() }
}

@Composable
fun hapticToggle(action: (Boolean) -> Unit): (Boolean) -> Unit {
    val haptics = rememberDashHaptics()
    return { enabled ->
        haptics.perform(if (enabled) DashFeedback.TOGGLE_ON else DashFeedback.TOGGLE_OFF)
        action(enabled)
    }
}

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
