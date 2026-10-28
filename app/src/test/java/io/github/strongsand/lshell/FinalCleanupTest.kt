package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.network.grpc.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class FinalCleanupTest {
    private fun client(name: String, bytes: Long = 100) = RouterClientInfo(name,
        RouterClientRole.CLIENT, RouterClientInterface.ETHERNET, "", emptyList(), name,
        null, null, 0, bytes, bytes / 2, true)
    private fun snapshot(clients: List<RouterClientInfo>) = RouterInfo("synthetic", "", "old", "",
        1, 1, RouterBootCause(0), emptyList(), "", emptyList(), null, null, null,
        emptyList(), emptyList(), clients)

    @Test fun manualRefreshRetainsMissingClientAndReplacesUpdatedClientWithoutDuplicates() {
        val a = client("a")
        val b = client("b")
        val previous = snapshot(listOf(a, b))
        val updated = a.copy(name = "renamed", receivedBytes = 120, connectedSeconds = 42)
        val fresh = snapshot(listOf(updated)).copy(software = "new", alerts = listOf(RouterAlert.INSTALL_PENDING))
        val result = retainRouterSnapshot(previous, fresh)
        assertEquals(listOf(updated, b.copy(active = false)), result.clients)
        assertEquals(2, result.clients.map(::trafficClientKey).distinct().size)
        assertEquals(fresh.copy(clients = result.clients), result)
        assertTrue(result.hasPendingUpdate)
        assertEquals(listOf(a, b), previous.clients)
        assertTrue(previous.clients.all { it.active })
    }

    @Test fun sharedRetentionPreservesBothExistingCounterPoliciesAndOrders() {
        val a = client("a")
        val z = client("z")
        val reset = z.copy(receivedBytes = 0, sentBytes = 1, sentBytesAvailable = false)
        val previous = listOf(a, z)
        assertEquals(listOf(reset, a.copy(active = false)), retainTrafficClients(previous, listOf(reset)))
        val retained = retainRouterSnapshot(snapshot(previous), snapshot(listOf(reset))).clients
        assertEquals(listOf(reset.copy(receivedBytes = 100, sentBytes = 50), a.copy(active = false)), retained)
        assertNull(trafficClientBytes(retained.first(), TrafficMetric.UPLOAD))
    }

    @Test fun identityFallbacksAndFirstSnapshotRemainCompatible() {
        val mac = client("name").copy(mac = "mac", ipv4 = "ip", reportedInterface = 5)
        assertEquals("mac", trafficClientKey(mac))
        assertEquals("ip", trafficClientKey(mac.copy(mac = "")))
        assertEquals("name|5", trafficClientKey(mac.copy(mac = "", ipv4 = "")))
        val fresh = snapshot(listOf(client("z"), client("a")))
        assertEquals(listOf("a", "z"), retainRouterSnapshot(null, fresh).clients.map { it.name })
        assertEquals(emptyList<RouterClientInfo>(), retainRouterSnapshot(null, snapshot(emptyList())).clients)
    }

    @Test fun routerPolicyMatchesThePreviousAlgorithmForRepeatedSubsetsResetsAndMissingCounters() {
        // Independent reference of the previous Router algorithm, including tie order and zero floors.
        fun reference(previous: List<RouterClientInfo>, current: List<RouterClientInfo>): List<RouterClientInfo> {
            fun key(c: RouterClientInfo) = c.mac.ifBlank { c.ipv4.ifBlank { "${c.name}|${c.reportedInterface}" } }
            val old = previous.associateBy(::key)
            val keys = current.map(::key).toSet()
            return (current.map { c -> c.copy(receivedBytes = maxOf(c.receivedBytes, old[key(c)]?.receivedBytes ?: 0L),
                sentBytes = maxOf(c.sentBytes, old[key(c)]?.sentBytes ?: 0L)) } +
                previous.filter { key(it) !in keys }.map { it.copy(active = false) })
                .sortedWith(compareByDescending<RouterClientInfo> { it.active }.thenBy { it.name })
        }
        val random = Random(42)
        var previous = emptyList<RouterClientInfo>()
        repeat(500) {
            val current = (0..9).filter { random.nextBoolean() }.shuffled(random).map { id ->
                client("client-$id", random.nextLong(-1, 1000)).copy(active = random.nextBoolean(),
                    receivedBytesAvailable = random.nextBoolean(), sentBytesAvailable = random.nextBoolean())
            }
            val expected = reference(previous, current)
            assertEquals(expected, retainRouterSnapshot(snapshot(previous), snapshot(current)).clients)
            previous = expected
        }
    }
}
