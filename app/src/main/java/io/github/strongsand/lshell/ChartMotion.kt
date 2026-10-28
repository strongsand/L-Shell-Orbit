package io.github.strongsand.lshell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A visual focus cue, separate from the exact sample pin/crosshair/value. */
internal data class ChartMotionTarget(val time: Long, val position: Offset, val segment: Long)

internal fun chartPositionValid(position: Offset) = position.x in 0f..1f && position.y in 0f..1f

internal fun chartCanTravel(from: ChartMotionTarget?, to: ChartMotionTarget?, direct: Boolean) =
    !direct && from != null && to != null && from.time != to.time && from.segment == to.segment &&
        chartPositionValid(from.position) && chartPositionValid(to.position)

internal data class ChartMotionRequest(
    val target: ChartMotionTarget?, val direct: Boolean, val enabled: Boolean, val domain: Any, val sizeDp: Size
)

internal fun chartShouldTravel(from: ChartMotionRequest?, to: ChartMotionRequest) =
    to.enabled && from != null && from.domain == to.domain && from.sizeDp == to.sizeDp &&
        chartCanTravel(from.target, to.target, to.direct)

/**
 * Conflated targets, one running job, no animation queue. Capture velocity before
 * cancellation; retarget from the displayed position. Frame reads stay in Canvas.
 * Direct touch paints the exact target immediately, without waiting for this job.
 */
@Composable
internal fun rememberChartTravel(target: ChartMotionTarget?, direct: Boolean, domain: Any,
    sizeDp: Size): State<Offset?> {
    val enabled = LocalOrbitMotion.current
    val animation = remember { Animatable(Offset.Zero, Offset.VectorConverter,
        visibilityThreshold = Offset(.0001f, .0001f)) }
    val requests = remember { Channel<ChartMotionRequest>(Channel.CONFLATED) }
    val flight = remember { mutableStateOf<ChartMotionRequest?>(null) }
    val input = ChartMotionRequest(target?.takeIf { chartPositionValid(it.position) }, direct, enabled, domain, sizeDp)
    val current = rememberUpdatedState(input)
    SideEffect { requests.trySend(input) }
    LaunchedEffect(requests) {
        var previous: ChartMotionRequest? = null
        var running: Job? = null
        for (request in requests) {
            if (request == previous) continue
            val old = previous
            previous = request
            val velocity = animation.velocity
            running?.cancel()
            running = launch {
                val next = request.target ?: return@launch
                if (old?.direct == true) old.target?.let { animation.snapTo(it.position) }
                val travel = chartShouldTravel(old, request)
                if (!travel) { flight.value = null; animation.snapTo(next.position) } else {
                    flight.value = request
                    val distance = OrbitMotion.travelDistance(next.position.x - animation.value.x,
                        next.position.y - animation.value.y, request.sizeDp.width, request.sizeDp.height)
                    val spec = OrbitMotion.chartSpring(distance)
                    withTimeoutOrNull(OrbitMotion.maximumChartTravelMs) {
                        animation.animateTo(next.position, spring(dampingRatio = spec.dampingRatio,
                            stiffness = spec.stiffness, visibilityThreshold = Offset(.0001f, .0001f)),
                            initialVelocity = velocity)
                    }
                    // Bound the lifetime and settle exactly, including a timeout.
                    animation.snapTo(next.position)
                }
            }
        }
    }
    return remember {
        derivedStateOf {
            val request = current.value
            request.target?.let {
                if (!request.enabled || request.direct || flight.value != request) it.position else
                    Offset(animation.value.x.coerceIn(0f, 1f), animation.value.y.coerceIn(0f, 1f))
            }
        }
    }
}

/** Hide the travelling cue at rest. The exact sample core is never moved. */
internal fun DrawScope.drawChartTravel(position: Offset?, target: Offset, color: Color,
    left: Float, top: Float, width: Float, height: Float) {
    position ?: return
    if ((position - target).getDistance() < .001f) return
    val center = Offset(left + position.x.coerceIn(0f, 1f) * width,
        top + position.y.coerceIn(0f, 1f) * height)
    drawCircle(color.copy(alpha = .10f), 9.dp.toPx(), center)
    drawCircle(color.copy(alpha = .65f), 6.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
}
