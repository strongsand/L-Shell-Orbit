package io.github.strongsand.lshell.core.data

import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import io.github.strongsand.lshell.core.network.grpc.terminalHistoryValue

/** Latest native power sample, separate from the legacy complete multi-metric buffer. */
data class LivePowerReading(val timestamp: Long, val powerWatts: Float)

internal fun DishyGrpcClient.HistoryReading.livePower(now: Long): LivePowerReading? =
    terminalHistoryValue(powerWatts, current, current - 1L)
        ?.takeIf { it >= 0f }
        // The newest counter has the same receipt/projection time as asSamples().
        ?.let { LivePowerReading(now, it) }
