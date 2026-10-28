package io.github.strongsand.lshell

/** Destination intent is separate from the controller's retained last result. */
internal object ExtremeTestNavigation {
    const val REQUESTED_ID = "extreme_requested_result_id"

    fun followedId(state: ConnectionTestState, requestedId: Long?, followedId: Long?): Long? = when {
        requestedId != null -> requestedId
        state.kind == ConnectionTestKind.EXTREME && state.active -> state.id
        else -> followedId
    }

    fun showsResult(state: ConnectionTestState, followedId: Long?): Boolean =
        state.kind == ConnectionTestKind.EXTREME && state.phase == ConnectionTestPhase.COMPLETED &&
            state.id == followedId
}
