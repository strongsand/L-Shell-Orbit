package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.network.grpc.RouterClientInfo

/** Reported role only. This schema has no parent/node association or mesh backhaul. */
internal object RouterTopology {
    fun reportedRepeaters(clients: List<RouterClientInfo>) = clients.filter { it.active && it.reportedRole == 2 }
    fun unassignedClients(clients: List<RouterClientInfo>) = clients.filterNot { it.active && it.reportedRole == 2 }
}
