package io.github.strongsand.lshell

import kotlin.math.abs
import kotlin.math.hypot

/** One pointer stream for the whole metric chart, independent of sample/marker positions.
 * Direction is resolved only after slop. Inspection then owns both axes until up/cancel. */
internal class MetricGestureOwnership(private val touchSlop: Float) {
    private enum class State { PENDING, INSPECTING, YIELDED, ENDED }
    private var state = State.PENDING
    val inspecting get() = state == State.INSPECTING
    val yielded get() = state == State.YIELDED

    fun move(dx: Float, dy: Float, consumed: Boolean) {
        if (state != State.PENDING) return
        state = when {
            consumed -> State.YIELDED
            hypot(dx, dy) <= touchSlop -> State.PENDING
            abs(dy) > abs(dx) -> State.YIELDED
            else -> State.INSPECTING
        }
    }

    /** Also called by the pointer handler's finally block on cancellation. */
    fun end() { state = State.ENDED }
}
