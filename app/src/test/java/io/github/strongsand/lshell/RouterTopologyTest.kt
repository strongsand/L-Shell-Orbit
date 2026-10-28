package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.network.grpc.RouterClientInfo
import io.github.strongsand.lshell.core.network.grpc.RouterClientRole
import io.github.strongsand.lshell.core.network.grpc.RouterClientInterface
import org.junit.Assert.*
import org.junit.Test

class RouterTopologyTest {
    private fun client(role: Int, active: Boolean = true) = RouterClientInfo("Synthetic", RouterClientRole.fromWire(role), RouterClientInterface.UNKNOWN, "", emptyList(), "", null, null, 0, 0, 0, active, reportedRole = role)
    @Test fun onlyActiveReportedRepeaterRoleIsGrouped() {
        val repeater = client(2)
        val clients = listOf(repeater, client(1), client(3), client(99), client(2, false))
        assertEquals(listOf(repeater), RouterTopology.reportedRepeaters(clients))
        assertEquals(4, RouterTopology.unassignedClients(clients).size)
    }
    @Test fun unknownRoleAndOrdinaryClientsAreNeverAssignedToMeshNode() {
        val clients = listOf(client(0), client(1), client(3))
        assertTrue(RouterTopology.reportedRepeaters(clients).isEmpty())
        assertEquals(clients, RouterTopology.unassignedClients(clients))
    }
}
