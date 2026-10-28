package io.github.strongsand.lshell

/** A menu entry is not an instruction to display the controller's previous result. */
internal object SpeedTestNavigation {
    const val REQUESTED_ID = "normal_requested_result_id"
    fun followedId(state: ConnectionTestState, requestedId: Long?, followedId: Long?): Long? = when {
        requestedId != null -> requestedId
        state.kind == ConnectionTestKind.NORMAL && state.active -> state.id
        else -> followedId
    }
}
