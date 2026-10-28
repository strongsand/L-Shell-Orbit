package io.github.strongsand.lshell.core.network.grpc

import io.github.strongsand.lshell.proto.PoeState
import io.github.strongsand.lshell.proto.WifiGetStatusResponse

/** Volts at the router's input-sense sensor. Receipt time is monotonic, not hardware sample time. */
data class RouterPoeReading(
    val inputVolts: Float,
    val routerId: String,
    val dishId: String,
    val receivedAtElapsedMs: Long
)

internal fun WifiGetStatusResponse.poeInputReading(receivedAtElapsedMs: Long): RouterPoeReading? {
    if (!hasPoeStats() || !poeStats.hasVsnsVin() || poeStats.poeState != PoeState.POE_STATE_ON) return null
    val volts = poeStats.vsnsVin
    if (!volts.isFinite() || volts < 0f || deviceInfo.id.isBlank() || dishId.isBlank()) return null
    return RouterPoeReading(volts, deviceInfo.id, dishId, receivedAtElapsedMs)
}

/** No nominal fallback, model whitelist, power conversion or last-known value. */
internal fun RouterPoeReading?.visibleInputVolts(
    liveDishId: String, liveDishAvailable: Boolean, nowElapsedMs: Long
): Float? = this?.takeIf {
    liveDishAvailable && liveDishId.isNotBlank() && it.dishId == liveDishId &&
        it.routerId.isNotBlank() && nowElapsedMs - it.receivedAtElapsedMs in 0..30_000L &&
        it.inputVolts.isFinite() && it.inputVolts > 0f
}?.inputVolts
