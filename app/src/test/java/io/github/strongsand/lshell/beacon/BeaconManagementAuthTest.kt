package io.github.strongsand.lshell.beacon

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BeaconManagementAuthTest {
    private val key = "00".repeat(32) // Explicit synthetic key, never a captured credential.
    private val id = "a".repeat(32)
    private val nonce = "b".repeat(32)

    @Test fun hmacMatchesPublishedSha256VectorWithZeroPaddedKey() {
        // RFC 4231 case 2: trailing zeroes do not change the HMAC key block padding.
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            BeaconManagementAuth.sign("4a656665" + "00".repeat(28), "what do ya want for nothing?"))
    }
    @Test fun commandUsesFixedFieldOrderAndBindsPurposeAndIdentity() {
        val fields = mapOf("confirmation" to "FACTORY_RESET")
        val request = BeaconManagementAuth.command("factory_reset", id, nonce, fields)
        assertEquals("POST\nfactory_reset\n$id\n$nonce\nFACTORY_RESET\n\n", request)
        val signature = BeaconManagementAuth.sign(key, request)
        BeaconManagementAuth.verify(key, request, signature)
        assertThrows(IllegalArgumentException::class.java) {
            BeaconManagementAuth.verify(key, BeaconManagementAuth.command("discard", id, nonce, fields), signature)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BeaconManagementAuth.verify(key, BeaconManagementAuth.command("factory_reset", "c".repeat(32), nonce, fields), signature)
        }
    }
    @Test fun anchorTimeCannotBeChangedInTransit() {
        val fields = mapOf("boot_id" to "7", "epoch_ms" to "1800000000000")
        val signature = BeaconManagementAuth.sign(key, BeaconManagementAuth.command("time_anchor", id, nonce, fields))
        assertThrows(IllegalArgumentException::class.java) {
            BeaconManagementAuth.verify(key, BeaconManagementAuth.command("time_anchor", id, nonce, fields + ("epoch_ms" to "1900000000000")), signature)
        }
    }
    @Test fun acceptanceCannotBeReusedForAnotherChallenge() {
        val accepted = BeaconManagementAuth.accepted("factory_reset", id, nonce, 0)
        val signature = BeaconManagementAuth.sign(key, accepted)
        assertThrows(IllegalArgumentException::class.java) {
            BeaconManagementAuth.verify(key, BeaconManagementAuth.accepted("factory_reset", id, "c".repeat(32), 0), signature)
        }
    }
    @Test fun discardPreviewIsAuthenticatedIncludingCountAndBoundary() {
        val json = JSONObject().put("beacon_id", id).put("nonce", nonce).put("boot_id", 7)
            .put("time_anchor_available", true).put("from_ack", 10).put("through_sequence", 15).put("record_count", 5)
        val message = BeaconManagementAuth.challengeResponse("discard", nonce, json)
        assertEquals("CHALLENGE\ndiscard\n$nonce\n$id\n$nonce\n7\ntrue\n10\n15\n5", message)
        val signature = BeaconManagementAuth.sign(key, message)
        json.put("through_sequence", 16)
        assertThrows(IllegalArgumentException::class.java) {
            BeaconManagementAuth.verify(key, BeaconManagementAuth.challengeResponse("discard", nonce, json), signature)
        }
    }
    @Test fun malformedSignatureIsRejected() {
        for (signature in listOf("", "wrong", "ff".repeat(32))) {
            assertThrows(IllegalArgumentException::class.java) { BeaconManagementAuth.verify(key, "synthetic", signature) }
        }
    }
    @Test fun historyAckBindsSequenceIdentityAndOneUseChallenge() {
        val request = BeaconManagementAuth.historyAck(id, nonce, 42)
        assertEquals("POST\nack\n$id\n$nonce\n42", request)
        val signature = BeaconManagementAuth.sign(key, request)
        assertEquals("356ddb15814d953e1ce416459282ba04c704d31a0be3c8d76a3e5ac7c092b3e8", signature)
        BeaconManagementAuth.verify(key, request, signature)
        for (changed in listOf(
            BeaconManagementAuth.historyAck(id, nonce, 43),
            BeaconManagementAuth.historyAck("c".repeat(32), nonce, 42),
            BeaconManagementAuth.historyAck(id, "c".repeat(32), 42)
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                BeaconManagementAuth.verify(key, changed, signature)
            }
        }
        val response = BeaconManagementAuth.accepted("ack", id, nonce, 42)
        val responseMac = BeaconManagementAuth.sign(key, response)
        BeaconManagementAuth.verify(key, response, responseMac)
        assertThrows(IllegalArgumentException::class.java) {
            BeaconManagementAuth.verify(key, BeaconManagementAuth.accepted("ack", id, nonce, 43), responseMac)
        }
    }
}
