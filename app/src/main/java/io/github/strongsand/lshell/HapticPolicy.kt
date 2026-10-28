package io.github.strongsand.lshell

enum class DashFeedback {
    TAP, NAVIGATION, CONFIRM, TOGGLE_ON, TOGGLE_OFF, SPECIAL, WARNING, ERROR,
    DRAG_SNAP, SATELLITE_SELECTED, SYNC_STARTED, SYNC_COMPLETED, DESTRUCTIVE,
    DRAG_STARTED, DRAG_COMPLETED
}
enum class HapticGesture { TICK, SOFT_TICK, CONTEXT, CONFIRM, REJECT, START, END, ON, OFF }

/** Semantic mapping without vibrator access; platform View still decides whether to play it. */
fun hapticGesture(feedback: DashFeedback, level: HapticLevel): HapticGesture? {
    if (level == HapticLevel.OFF) return null
    if (level == HapticLevel.SUBTLE) return HapticGesture.SOFT_TICK
    return when (feedback) {
        DashFeedback.TAP, DashFeedback.DRAG_SNAP -> HapticGesture.TICK
        DashFeedback.NAVIGATION -> HapticGesture.CONTEXT
        DashFeedback.CONFIRM, DashFeedback.SYNC_COMPLETED -> HapticGesture.CONFIRM
        DashFeedback.WARNING, DashFeedback.ERROR, DashFeedback.DESTRUCTIVE -> HapticGesture.REJECT
        DashFeedback.TOGGLE_ON -> HapticGesture.ON
        DashFeedback.TOGGLE_OFF -> HapticGesture.OFF
        DashFeedback.SYNC_STARTED, DashFeedback.DRAG_STARTED -> HapticGesture.START
        DashFeedback.DRAG_COMPLETED -> HapticGesture.END
        DashFeedback.SPECIAL, DashFeedback.SATELLITE_SELECTED ->
            if (level == HapticLevel.EXPRESSIVE) HapticGesture.END else HapticGesture.TICK
    }
}
