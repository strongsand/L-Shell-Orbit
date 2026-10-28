package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.network.grpc.*
import io.github.strongsand.lshell.proto.WifiAlerts
import io.github.strongsand.lshell.proto.WifiClient
import org.junit.Assert.*
import org.junit.Test

class RouterDomainTest {
    @Test fun protobufFlagsMapToExplicitAlertCodes() {
        val alerts = WifiAlerts.newBuilder().setThermalThrottle(true).setInstallPending(true)
            .setLanEthSlowLink10(true).setLanEthSlowLink100(true).setWanEthPoorConnection(true).build()
        assertEquals(RouterAlert.entries.toSet(), alerts.toRouterAlerts().toSet())
        assertEquals(4, alerts.toRouterAlerts().size)
        assertTrue(WifiAlerts.getDefaultInstance().toRouterAlerts().isEmpty())
    }
    @Test fun updatePendingDoesNotDependOnALabel() {
        val info = RouterInfo("", "", "", "", 0, 0, RouterBootCause(0), emptyList(), "", emptyList(),
            null, null, null, listOf(RouterAlert.INSTALL_PENDING), emptyList(), emptyList())
        assertTrue(info.hasPendingUpdate)
        assertFalse(info.copy(alerts = listOf(RouterAlert.THERMAL_THROTTLE)).hasPendingUpdate)
    }
    @Test fun bootReasonsDecodeKnownCodesAndPreserveFutureCodes() {
        listOf(RouterBootReason.UNKNOWN, RouterBootReason.FORGOTTEN, RouterBootReason.POWER_CYCLE,
            RouterBootReason.COMMAND, RouterBootReason.SOFTWARE_UPDATE, RouterBootReason.CONFIG_UPDATE)
            .forEachIndexed { index, reason -> assertEquals(reason, RouterBootCause(index).reason) }
        assertEquals(RouterBootReason.OTHER, RouterBootCause(99).reason)
        assertEquals(99, RouterBootCause(99).code)
        assertEquals(RouterBootReason.OTHER, RouterBootReason.fromWire(-1))
    }
    @Test fun roleAndInterfaceDecodeWireValuesWithoutInventingMesh() {
        RouterClientRole.entries.forEachIndexed { i, value -> assertEquals(value, RouterClientRole.fromWire(i)) }
        RouterClientInterface.entries.forEachIndexed { i, value -> assertEquals(value, RouterClientInterface.fromWire(i)) }
        val unknown = WifiClient.newBuilder().setRole(99).setIface(88).build().toRouterClientInfo()
        assertEquals(RouterClientRole.UNKNOWN, unknown.role); assertEquals(99, unknown.reportedRole)
        assertEquals(RouterClientInterface.UNKNOWN, unknown.interfaceKind); assertEquals(88, unknown.reportedInterface)
        assertTrue(RouterTopology.reportedRepeaters(listOf(unknown)).isEmpty())
    }
    @Test fun unnamedDeviceStaysEmptyUntilPresentationAndUserNameIsUntouched() {
        assertEquals("", WifiClient.getDefaultInstance().toRouterClientInfo().name)
        val device = WifiClient.newBuilder().setName("wire name").setGivenName("nome escolhido").build().toRouterClientInfo()
        assertEquals("nome escolhido", device.name)
    }
    @Test fun countersKeepRxTxAndMissingZeroSemantics() {
        val client = WifiClient.newBuilder().setRxStats(WifiClient.RxStats.newBuilder().setBytes(0))
            .setTxStats(WifiClient.TxStats.newBuilder().setBytes(17)).build().toRouterClientInfo()
        assertEquals(listOf(R.string.router_received to 0L, R.string.router_sent to 17L), RouterClientPresentation.counters(client))
        assertTrue(RouterClientPresentation.counters(WifiClient.getDefaultInstance().toRouterClientInfo()).isEmpty())
    }
}
