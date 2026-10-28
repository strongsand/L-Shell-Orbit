package io.github.strongsand.lshell

/** Latest direction evidenced by new router samples, not a timed or invented server phase. */
enum class SpeedTestActivity { UNKNOWN, DOWNLOAD, UPLOAD }

/** Attached to the existing one-second status responses; owns no RPC, timer or sample buffer. */
internal class SpeedTestActivityDetector {
    private data class Fingerprint(val size: Int, val validCount: Int, val last: Float?)
    private var execution: Int? = null
    private var down: Fingerprint? = null
    private var up: Fingerprint? = null

    private fun fingerprint(values: List<Float>): Fingerprint {
        var valid = 0
        var last: Float? = null
        values.forEach { if (it.isFinite() && it >= 0f) { valid++; last = it } }
        return Fingerprint(values.size, valid, last)
    }

    private fun changed(previous: Fingerprint?, next: Fingerprint): Boolean = next.validCount > 0 &&
        (previous == null || next.validCount > previous.validCount ||
            next.size == previous.size && next.validCount == previous.validCount && next.last != previous.last)

    fun observe(id: Int, running: Boolean, download: List<Float>, upload: List<Float>): SpeedTestActivity {
        if (id != execution) { execution = id; down = null; up = null }
        val nextDown = fingerprint(download)
        val nextUp = fingerprint(upload)
        val newDown = changed(down, nextDown)
        val newUp = changed(up, nextUp)
        down = nextDown; up = nextUp
        return when {
            !running || newDown == newUp -> SpeedTestActivity.UNKNOWN
            newDown -> SpeedTestActivity.DOWNLOAD
            else -> SpeedTestActivity.UPLOAD
        }
    }
}

internal enum class TestTransferMode { NONE, PREPARING, DOWNLOAD, UPLOAD }

internal object TestTransferPresentation {
    fun mode(state: ConnectionTestState): TestTransferMode = when {
        !state.active -> TestTransferMode.NONE
        state.phase != ConnectionTestPhase.RUNNING -> TestTransferMode.PREPARING
        state.liveActivity == SpeedTestActivity.DOWNLOAD -> TestTransferMode.DOWNLOAD
        state.liveActivity == SpeedTestActivity.UPLOAD -> TestTransferMode.UPLOAD
        else -> TestTransferMode.PREPARING
    }

    /** Symbolic traffic motion only: this fraction must never be exposed as test completion. */
    fun marker(mode: TestTransferMode, fraction: Float): Float =
        if (mode == TestTransferMode.UPLOAD) 1f - fraction.coerceIn(0f, 1f) else fraction.coerceIn(0f, 1f)

    fun animate(state: ConnectionTestState, systemMotion: Boolean, visible: Boolean): Boolean =
        state.active && systemMotion && visible

    fun nativeIndeterminate(state: ConnectionTestState): Boolean =
        state.active && state.phase != ConnectionTestPhase.BETWEEN_ROUNDS
}
