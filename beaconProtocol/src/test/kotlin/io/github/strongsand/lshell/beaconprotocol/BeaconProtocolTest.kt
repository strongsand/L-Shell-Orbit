package io.github.strongsand.lshell.beaconprotocol

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

class BeaconProtocolTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val key = "00".repeat(32) // Synthetic fixture.
    private var now = 10L
    private fun challenge(auth: BeaconAckAuthenticator): String {
        val client = "ab".repeat(16)
        return auth.challenge(id, client, BeaconProtocol.sign(key, BeaconProtocol.challengeRequest("ack", id, client)))
    }
    @Test fun pairingRoundTripAndExactIdentity() {
        val pairing = BeaconPairing.parse(BeaconPairing(id, key).encode())
        assertEquals(id, pairing.id); assertEquals(key, pairing.token); pairing.matches(id)
        assertThrows(IllegalArgumentException::class.java) { pairing.matches("a".repeat(32)) }
        assertFalse(pairing.toString().contains(key))
    }
    @Test fun malformedOrAmbiguousQrIsRejected() {
        for (text in listOf("", "https://example.org", "lshell-beacon://pair?v=2&id=$id&token=$key",
            "lshell-beacon://pair?v=1&id=$id&token=short", "lshell-beacon://pair?v=1&id=$id&token=$key&token=$key",
            "lshell-beacon://pair?v=1&id=$id&token=$key#extra"))
            assertThrows(Exception::class.java) { BeaconPairing.parse(text) }
    }
    @Test fun discoverySupportsBothHardwareAndAndroidAttributes() {
        assertEquals("a".repeat(32) to false, BeaconProtocol.discovery(mapOf("id" to "a".repeat(32), "proto" to "1")))
        assertEquals(id to true, BeaconProtocol.discovery(mapOf("beacon_id" to id, "kind" to "android", "protocol" to "1")))
        assertEquals("_lshell-beacon._tcp.", BeaconProtocol.SERVICE_TYPE)
    }
    @Test fun serviceRegistrationDescriptorContainsOnlyPublicMetadata() {
        val registration = BeaconProtocol.advertisement(id, 12345)
        assertEquals(12345, registration.port); assertEquals(BeaconProtocol.SERVICE_TYPE, registration.type)
        assertEquals(setOf("protocol", "proto", "kind", "beacon_id", "name"), registration.attributes.keys)
        assertEquals(id to true, BeaconProtocol.discovery(registration.attributes))
        assertThrows(IllegalArgumentException::class.java) { BeaconProtocol.advertisement(id, 0) }
    }
    @Test fun sharedAckMatchesExistingPhysicalBeaconVector() {
        assertEquals("356ddb15814d953e1ce416459282ba04c704d31a0be3c8d76a3e5ac7c092b3e8",
            BeaconProtocol.sign(key, BeaconProtocol.historyAck("a".repeat(32), "b".repeat(32), 42)))
    }
    @Test fun validAndDuplicateAckUseFreshChallengesAndReplayIsRejected() {
        val auth = BeaconAckAuthenticator(id, key) { now }
        var ack = 0L
        repeat(2) {
            val nonce = challenge(auth)
            val signature = BeaconProtocol.sign(key, BeaconProtocol.historyAck(id, nonce, 5))
            val response = auth.acknowledge(id, nonce, 5, signature) { ack = maxOf(ack, it) }
            BeaconProtocol.verify(key, BeaconProtocol.accepted("ack", id, nonce, 5), response)
            assertThrows(IllegalStateException::class.java) { auth.acknowledge(id, nonce, 5, signature) { fail() } }
        }
        assertEquals(5L, ack)
    }
    @Test fun invalidSignatureIdentityExpiryAndFutureCannotCommit() {
        val auth = BeaconAckAuthenticator(id, key) { now }
        val nonce = challenge(auth)
        assertThrows(IllegalArgumentException::class.java) { auth.acknowledge(id, nonce, 5, "ff".repeat(32)) { fail() } }
        val signature = BeaconProtocol.sign(key, BeaconProtocol.historyAck(id, nonce, 5))
        assertThrows(IllegalArgumentException::class.java) { auth.acknowledge("a".repeat(32), nonce, 5, signature) { fail() } }
        now += 60_001
        assertThrows(IllegalArgumentException::class.java) { auth.acknowledge(id, nonce, 5, signature) { fail() } }
        val fresh = challenge(auth)
        var ack = 0L
        assertThrows(IllegalArgumentException::class.java) { auth.acknowledge(id, fresh, 5000,
            BeaconProtocol.sign(key, BeaconProtocol.historyAck(id, fresh, 5000))) { require(it <= 1000); ack = it } }
        assertEquals(0L, ack)
    }
    @Test fun challengeRequiresAuthenticatedPurposeAndDoesNotExposeToken() {
        val auth = BeaconAckAuthenticator(id, key) { now }
        assertThrows(IllegalArgumentException::class.java) { auth.challenge(id, "b".repeat(32), "00".repeat(32)) }
        assertFalse(challenge(auth).contains(key))
    }
    @Test fun absoluteTimeBoundsDoNotAcceptEpochOrUptime() {
        assertTrue(BeaconProtocol.absoluteTime(1_800_000_000_000))
        listOf(0L, -1L, 3_600_000L, Long.MAX_VALUE).forEach { assertFalse(BeaconProtocol.absoluteTime(it)) }
    }
    @Test fun persistentTokenIsReusedAndFailedWriteCannotReturnNewCredential() {
        val file = kotlin.io.path.createTempFile("beacon-test", ".token").toFile()
        try {
            file.delete()
            fun open() = BeaconProtocol.managementToken({ file.takeIf { it.exists() }?.readText() }, { file.writeText(it); true })
            val first = open(); assertEquals(64, first.length); assertEquals(first, open())
            assertThrows(IllegalStateException::class.java) { BeaconProtocol.managementToken({ null }, { false }) }
        } finally { file.delete() }
    }
    @Test fun captureClockRejectsInvalidEpochAndWallClockJumps() {
        val clock = BeaconCaptureClock()
        val time = 1_800_000_000_000L
        assertTrue(clock.valid(time, 1000)); assertTrue(clock.valid(time + 2000, 3000))
        assertFalse(clock.valid(time - 300000, 5000))
        assertTrue(clock.valid(time - 298000, 7000))
        assertFalse(clock.valid(1000, 9000))
        assertFalse(clock.valid(time, 11000)); assertTrue(clock.valid(time + 2000,13000))
    }
    @Test fun realHttpServerRespondsAndRejectsUnboundedOrAmbiguousInput() {
        var requests = 0
        LanHttpServer(InetAddress.getLoopbackAddress()) {
            requests++; assertEquals("GET", it.method); assertEquals("/v1/info", it.target)
            LanResponse(json = "{\"protocol_version\":1}")
        }.use { server ->
            fun send(text: String): String = Socket(InetAddress.getLoopbackAddress(), server.port).use {
                it.soTimeout = 4000; it.getOutputStream().write(text.toByteArray())
                it.getInputStream().bufferedReader().readText()
            }
            assertTrue(send("GET /v1/info HTTP/1.1\r\nHost: localhost\r\n\r\n").contains("protocol_version"))
            assertTrue(send("POST /v1/history/ack HTTP/1.1\r\nContent-Length: 50000\r\n\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(send("GET / HTTP/1.1\r\nContent-Length: 0\r\nContent-Length: 1\r\n\r\n").startsWith("HTTP/1.1 400"))
            assertEquals(1, requests)
        }
    }
}
