package com.hurricane.lshell

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

enum class DashFeedback { TAP, CONFIRM, TOGGLE_ON, TOGGLE_OFF, SPECIAL, WARNING }

@Stable
class DashHaptics(private val view: View) {
    private var lastFeedback = -100L

    fun perform(feedback: DashFeedback = DashFeedback.TAP) {
        val now = SystemClock.uptimeMillis()
        if (!view.isAttachedToWindow || !view.hasWindowFocus() || !view.isHapticFeedbackEnabled || now - lastFeedback < 70L) return
        lastFeedback = now
        val effect = when (feedback) {
            DashFeedback.TAP -> HapticFeedbackConstants.CLOCK_TICK
            DashFeedback.CONFIRM -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
            DashFeedback.SPECIAL -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.CONTEXT_CLICK
            DashFeedback.WARNING -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONTEXT_CLICK
            DashFeedback.TOGGLE_ON -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CLOCK_TICK
            DashFeedback.TOGGLE_OFF -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
        }
        // No forced vibrator fallback: View respects system settings and hardware support.
        runCatching { view.performHapticFeedback(effect) }
    }
}

@Composable
fun rememberDashHaptics(): DashHaptics {
    val view = LocalView.current
    return remember(view) { DashHaptics(view) }
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
    val scale by animateFloatAsState(if (pressed) compression else 1f,
        spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMedium), label = "press spring")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A small shared entrance motion that also respects Android's reduced-motion setting. */
@Composable
fun Modifier.expressiveReveal(
    key: Any? = Unit,
    delayMillis: Int = 0,
    distance: Dp = 12.dp
): Modifier {
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val progress = remember(key) { Animatable(if (animationsEnabled) 0f else 1f) }
    val distancePx = with(LocalDensity.current) { distance.toPx() }

    LaunchedEffect(key, animationsEnabled) {
        if (!animationsEnabled) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            if (delayMillis > 0) delay(delayMillis.toLong())
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = Spring.StiffnessLow
                )
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
