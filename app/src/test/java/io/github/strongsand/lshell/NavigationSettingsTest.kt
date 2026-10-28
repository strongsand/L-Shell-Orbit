package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.network.grpc.RouterClientInfo
import io.github.strongsand.lshell.core.network.grpc.RouterClientRole
import io.github.strongsand.lshell.core.network.grpc.RouterClientInterface
import io.github.strongsand.lshell.core.network.grpc.toRouterClientInfo
import io.github.strongsand.lshell.proto.WifiClient
import org.junit.Assert.*
import org.junit.Test

class NavigationSettingsTest {
    @Test fun directoryContainsOnlyTheRequestedRoutesInOrder() {
        val expectedRoutes = listOf(
            "TRAFFIC", "HISTORY", "REPORTS", "OUTAGES", "ENERGY", "ALIGNMENT",
            "HEALTH", "SPEED", "SCHEDULED_TESTS", "SMART_TESTS", "EXTREME_HISTORY",
            "BEACON", "PREFERENCES"
        )
        val actualRoutes = orbitFunctions.map { it.route }
        assertEquals(expectedRoutes, actualRoutes)
        assertEquals("Function routes must not be duplicated", actualRoutes, actualRoutes.distinct())
        assertEquals(setOf(R.string.functions_monitoring, R.string.functions_starlink,
            R.string.functions_tests, R.string.functions_beacon, R.string.settings_title),
            orbitFunctions.map { it.group }.toSet())
        // The two intentional automation entries have distinct labels and valid test destinations.
        val automationTitles = mapOf(
            "SCHEDULED_TESTS" to R.string.auto_scheduled_title,
            "SMART_TESTS" to R.string.auto_smart_title
        )
        automationTitles.forEach { (route, title) ->
            val entry = orbitFunctions.single { it.route == route }
            assertEquals(title, entry.title)
            assertEquals(R.string.functions_tests, entry.group)
            assertTrue(MainTab.entries.any { it.name == route })
        }
    }
    @Test fun removedShortcutsDoNotDeleteDestinationsAndSettingsHasItsOwnRoute() {
        val allRoutes = MainTab.entries.map { it.name }.toSet()
        assertTrue(orbitFunctions.all { it.route in allRoutes })
        assertTrue(allRoutes.contains("EXTREME"))
        assertFalse(orbitFunctions.any { it.route == "EXTREME" })
        listOf("HOME","DISH","NETWORK","SKY","TERMINAL","TECHNICAL").forEach {
            assertTrue(allRoutes.contains(it))
            assertFalse(orbitFunctions.any { entry -> entry.route == it })
        }
        assertNotEquals(MainTab.SETTINGS,MainTab.PREFERENCES)
    }
    @Test fun searchResultsComeOnlyFromTheReducedDirectory() {
        val removed = setOf(R.string.function_home,R.string.function_dish,R.string.function_network,
            R.string.function_ar,R.string.function_controls)
        assertTrue(orbitFunctions.none { it.title in removed })
        val settings = orbitFunctions.single { it.route == "PREFERENCES" }
        assertEquals(R.string.settings_title,settings.title)
        assertTrue(matchesFunctionSearch("configuracao","Configurações","Configurações"))
        assertTrue(matchesFunctionSearch("trafego","Monitor de tráfego","Monitoramento"))
    }
    @Test fun absentCountersAreNotRealZero() {
        val absent = WifiClient.getDefaultInstance().toRouterClientInfo()
        assertFalse(absent.receivedBytesAvailable)
        assertFalse(absent.sentBytesAvailable)
        assertNull(trafficClientBytes(absent,TrafficMetric.DOWNLOAD))
        assertNull(trafficClientBytes(absent,TrafficMetric.UPLOAD))
        val zero = WifiClient.newBuilder().setRxStats(WifiClient.RxStats.newBuilder().setBytes(0)).build().toRouterClientInfo()
        assertEquals(0L,trafficClientBytes(zero,TrafficMetric.DOWNLOAD))
        assertNull(trafficClientBytes(zero,TrafficMetric.UPLOAD))
    }
    @Test fun distinctRxTxAndUnsignedOverflowDoNotInventTotals() {
        val client = WifiClient.newBuilder().setRxStats(WifiClient.RxStats.newBuilder().setBytes(120))
            .setTxStats(WifiClient.TxStats.newBuilder().setBytes(7)).build().toRouterClientInfo()
        assertEquals(120L,trafficClientBytes(client,TrafficMetric.DOWNLOAD))
        assertEquals(7L,trafficClientBytes(client,TrafficMetric.UPLOAD))
        assertNull(trafficClientBytes(client,TrafficMetric.LOSS))
        val invalid = WifiClient.newBuilder().setRxStats(WifiClient.RxStats.newBuilder().setBytes(-1)).build().toRouterClientInfo()
        assertNull(trafficClientBytes(invalid,TrafficMetric.DOWNLOAD))
    }
    private fun client(name: String,received: Long = 10,sent: Long = 4) =
        RouterClientInfo(name,RouterClientRole.CLIENT,RouterClientInterface.ETHERNET,"",emptyList(),name,null,null,0,received,sent,true)
    @Test fun transientSubsetKeepsMissingClientsButDoesNotClaimTheyAreActive() {
        val first = client("synthetic-a")
        val second = client("synthetic-b")
        val merged = retainTrafficClients(listOf(first,second),listOf(first.copy(receivedBytes = 20)))
        assertEquals(2,merged.size)
        assertEquals(20L,merged.first().receivedBytes)
        assertTrue(merged.first().active)
        assertFalse(merged.last().active)
        assertEquals(2,merged.map(::trafficClientKey).distinct().size)
    }
    @Test fun freshResetAndMissingCountersReplacePreviousSnapshot() {
        val previous = client("synthetic-a",received = 100)
        val reset = previous.copy(receivedBytes = 0,sentBytes = 1,sentBytesAvailable = false)
        val result = retainTrafficClients(listOf(previous),listOf(reset)).single()
        assertEquals(0L,trafficClientBytes(result,TrafficMetric.DOWNLOAD))
        assertNull(trafficClientBytes(result,TrafficMetric.UPLOAD))
        assertEquals(listOf(result.copy(active = false)),retainTrafficClients(listOf(result),emptyList()))
    }
}
