package io.github.strongsand.lshell

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

/** One contextual editor, independent of telemetry and the remembered route graph. */
@Stable
internal class CardEditorSession {
    var active by mutableStateOf<CardArrangement?>(null); private set
    private var bounds: () -> Rect? = { null }
    private var busy: () -> Boolean = { false }
    private var finishAction: (() -> Unit)? = null

    fun bind(owner: CardArrangement, selectedBounds: () -> Rect?, manipulating: () -> Boolean, onFinish: () -> Unit) {
        if (active != null && active !== owner) this.finish()
        active = owner; bounds = selectedBounds; busy = manipulating; finishAction = onFinish
    }
    fun selectedBounds() = bounds()
    fun manipulating() = busy()
    fun release(owner: CardArrangement) {
        if (active === owner) { active = null; finishAction = null; bounds = { null }; busy = { false } }
    }
    fun finish() {
        val action = finishAction
        active = null; finishAction = null; bounds = { null }; busy = { false }
        action?.invoke()
    }
}

internal val LocalCardEditorSession = staticCompositionLocalOf<CardEditorSession?> { null }

/** Observes a single short tap. Scroll consumption, movement, multitouch, long holds,
 * a new drag/resize, or cancellation invalidate it. No pointer is ever consumed here. */
internal class CardOutsideTap(private val start: Offset, private val downTime: Long,
    private val slop: Float, private val timeout: Long, private val epoch: Long,
    private val outside: Boolean) {
    private var valid = outside
    fun observe(position: Offset, pointerCount: Int, consumedMovement: Boolean) {
        if ((position - start).getDistance() > slop || pointerCount > 1 || consumedMovement) valid = false
    }
    fun complete(time: Long, currentEpoch: Long, busy: Boolean) =
        valid && time - downTime in 0..timeout && currentEpoch == epoch && !busy
}

internal fun Modifier.observeCardEditorTouches(session: CardEditorSession): Modifier = composed {
    val owner = session.active
    if (owner == null) return@composed this
    pointerInput(session, owner) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val tap = CardOutsideTap(down.position, down.uptimeMillis, viewConfiguration.touchSlop,
                viewConfiguration.longPressTimeoutMillis, owner.interactionEpoch,
                !session.manipulating() && session.selectedBounds()?.contains(down.position) != true)
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tap.observe(change.position, event.changes.size,
                    change.isConsumed && change.pressed && change.previousPressed && change.position != change.previousPosition)
                if (change.changedToUpIgnoreConsumed()) {
                    if (session.active === owner && tap.complete(change.uptimeMillis, owner.interactionEpoch, session.manipulating()))
                        session.finish()
                    break
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
