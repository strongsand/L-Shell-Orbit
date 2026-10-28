package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.network.grpc.RouterClientInfo
import io.github.strongsand.lshell.core.network.grpc.RouterInfo

internal fun trafficClientKey(client: RouterClientInfo) =
    client.mac.ifBlank { client.ipv4.ifBlank { client.name + "|" + client.reportedInterface } }

internal fun retainTrafficClients(previous: List<RouterClientInfo>, current: List<RouterClientInfo>,
    preserveByteHighWater: Boolean = false): List<RouterClientInfo> {
    val keys = current.mapTo(mutableSetOf()) { trafficClientKey(it) }
    // The Router screen historically keeps high-water counters; traffic accounting accepts resets.
    val refreshed = if (preserveByteHighWater) {
        val previousByKey = previous.associateBy(::trafficClientKey)
        current.map { client ->
            val old = previousByKey[trafficClientKey(client)]
            client.copy(receivedBytes = maxOf(client.receivedBytes, old?.receivedBytes ?: 0L),
                sentBytes = maxOf(client.sentBytes, old?.sentBytes ?: 0L))
        }
    } else current
    return refreshed + previous.filter { trafficClientKey(it) !in keys }.map { it.copy(active = false) }
}

/** Apply the same Router display policy to polls and manual update checks. */
internal fun retainRouterSnapshot(previous: RouterInfo?, fresh: RouterInfo): RouterInfo = fresh.copy(
    clients = retainTrafficClients(previous?.clients.orEmpty(), fresh.clients, preserveByteHighWater = true)
        .sortedWith(compareByDescending<RouterClientInfo> { it.active }.thenBy { it.name }))

internal fun trafficClientBytes(client: RouterClientInfo, metric: TrafficMetric): Long? = when (metric) {
    TrafficMetric.DOWNLOAD -> client.receivedBytes.takeIf { client.receivedBytesAvailable }
    TrafficMetric.UPLOAD -> client.sentBytes.takeIf { client.sentBytesAvailable }
    else -> null
}
