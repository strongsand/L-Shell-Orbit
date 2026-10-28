package io.github.strongsand.lshell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** Clamp tint against actual foregrounds, including dynamic palettes and AMOLED. */
internal fun safeIntensityAlpha(base: Color, tint: Color, foregrounds: List<Color>): Float {
    fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
    var alpha = .10f
    repeat(11) {
        if (foregrounds.all { contrast(it, tint.copy(alpha = alpha).compositeOver(base)) >= 4.5f }) return alpha
        alpha = (alpha - .01f).coerceAtLeast(0f)
    }
    return 0f
}

/** Only this draw layer reads animated state. Children/numbers/paths don't recompose per frame. */
@Composable
internal fun MetricIntensityBody(emphasis: Float, base: Color, tint: Color,
    foregrounds: List<Color>, content: @Composable BoxScope.() -> Unit) {
    val motion = LocalOrbitMotion.current
    val alpha = remember(base, tint, foregrounds) { safeIntensityAlpha(base, tint, foregrounds) }
    val animated = animateFloatAsState(emphasis.coerceIn(0f, 1f) * alpha,
        OrbitMotion.spec(OrbitMotionRole.STATE, motion), label = "metric background")
    val above = stringResource(R.string.cards_above_recent)
    Box(Modifier.fillMaxWidth().semantics {
        if (emphasis >= .2f) stateDescription = above
    }) {
        Canvas(Modifier.matchParentSize()) { drawRect(tint.copy(alpha = animated.value.coerceIn(0f, alpha))) }
        content()
    }
}
