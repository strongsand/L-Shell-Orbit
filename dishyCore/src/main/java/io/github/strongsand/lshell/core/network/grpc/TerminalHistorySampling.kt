package io.github.strongsand.lshell.core.network.grpc

/** Independent 1 Hz ring arrays. `current` is the position after the newest sample. */
fun terminalHistorySize(data: DishyGrpcClient.HistoryReading): Int = maxOf(
    data.downloadMbps.size, data.uploadMbps.size, data.latencyMs.size,
    data.dropPercent.size, data.powerWatts.size)

fun terminalHistoryValue(values: List<Float>, current: Long, counter: Long): Float? {
    if (values.isEmpty() || current <= 0 || counter < maxOf(0L, current - values.size) || counter >= current) return null
    return values[(counter % values.size).toInt()].takeIf { it.isFinite() }
}
