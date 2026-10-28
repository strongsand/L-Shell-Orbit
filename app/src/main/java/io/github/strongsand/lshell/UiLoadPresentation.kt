package io.github.strongsand.lshell

internal enum class UiLoadPhase { LOADING, LOADED, EMPTY, ERROR }

/** A completed previous request cannot masquerade as an empty new period. */
internal data class UiLoadStatus<K>(val request: K, val phase: UiLoadPhase = UiLoadPhase.LOADING) {
    fun phaseFor(request: K): UiLoadPhase =
        if (this.request == request) phase else UiLoadPhase.LOADING

    fun completed(empty: Boolean): UiLoadStatus<K> =
        copy(phase = if (empty) UiLoadPhase.EMPTY else UiLoadPhase.LOADED)

    fun failed(): UiLoadStatus<K> = copy(phase = UiLoadPhase.ERROR)
}
