package io.github.strongsand.lshell.beaconprotocol

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Wire primitives shared by Orbit and the Android host. No Android or JSON dependency. */
object BeaconProtocol {
    const val SERVICE_TYPE = "_lshell-beacon._tcp."
    val capabilities = setOf("status", "history_v1", "management_v1", "authenticated_ack_v1",
        "android_beacon_v1", "absolute_timestamp_v1")
    fun validId(id: String) = id.matches(Regex("[a-f0-9]{32}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
    fun randomHex(bytes: Int) = ByteArray(bytes).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun sign(keyHex: String, message: String): String {
        require(keyHex.matches(Regex("[a-f0-9]{64}")))
        val key = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
            }
        } finally { key.fill(0) }
    }
    fun verify(key: String, message: String, signature: String) {
        require(signature.matches(Regex("[a-f0-9]{64}")) && MessageDigest.isEqual(
            sign(key, message).toByteArray(Charsets.US_ASCII), signature.toByteArray(Charsets.US_ASCII))) { "Authentication failed" }
    }
    fun challengeRequest(action: String, id: String, clientNonce: String) = "GET\n$action\n$id\n$clientNonce"
    fun historyAck(id: String, nonce: String, sequence: Long) = "POST\nack\n$id\n$nonce\n$sequence"
    fun accepted(action: String, id: String, nonce: String, through: Long) = "ACCEPTED\n$action\n$id\n$nonce\n$through"
    fun challengeResponse(action: String, clientNonce: String, id: String, nonce: String,
        boot: Long, anchor: Boolean, fromAck: Long, through: Long, records: Long) =
        "CHALLENGE\n$action\n$clientNonce\n$id\n$nonce\n$boot\n$anchor\n$fromAck\n$through\n$records"
    fun absoluteTime(timestamp: Long) = timestamp in 1_577_836_800_001L until 4_102_444_800_000L
    @Synchronized fun managementToken(read: () -> String?, commit: (String) -> Boolean): String {
        read()?.let { require(it.matches(Regex("[a-f0-9]{64}"))); return it }
        return randomHex(32).also { check(commit(it)) { "Credential persistence failed" } }
    }
    fun discovery(attributes: Map<String, String>): Pair<String?, Boolean> =
        (attributes["beacon_id"] ?: attributes["id"]) to (attributes["kind"] == "android")
    fun advertisement(id: String, port: Int): BeaconAdvertisement {
        require(validId(id) && port in 1..65535)
        return BeaconAdvertisement("L-Shell Beacon Android ${id.takeLast(4)}", SERVICE_TYPE, port,
            mapOf("protocol" to "1", "proto" to "1", "kind" to "android", "beacon_id" to id, "name" to "Android"))
    }
}

data class BeaconAdvertisement(val name: String, val type: String, val port: Int, val attributes: Map<String, String>)

/** A capture session can detect clock jumps without manufacturing an absolute time. */
class BeaconCaptureClock {
    private var previous: Pair<Long, Long>? = null
    fun valid(wall: Long, elapsed: Long): Boolean {
        val old = previous
        val valid = BeaconProtocol.absoluteTime(wall) && elapsed >= 0 && (old == null ||
            (BeaconProtocol.absoluteTime(old.first) && elapsed >= old.second &&
                kotlin.math.abs((wall - old.first) - (elapsed - old.second)) <= 120_000L))
        previous = wall to elapsed
        return valid
    }
}

/** Deliberately not a data class: accidental logs/toString must not expose the token. */
class BeaconPairing(val id: String, val token: String) {
    init { require(BeaconProtocol.validId(id) && token.matches(Regex("[a-f0-9]{64}"))) }
    fun encode() = "lshell-beacon://pair?v=1&id=$id&token=$token"
    fun matches(actualId: String) { require(id == actualId) { "Beacon identity mismatch" } }
    companion object {
        fun parse(text: String): BeaconPairing {
            require(text.length in 1..256)
            val uri = URI(text)
            require(uri.scheme == "lshell-beacon" && uri.rawAuthority == "pair" &&
                uri.rawPath.isNullOrEmpty() && uri.rawFragment == null)
            val fields = uri.rawQuery.orEmpty().split('&').map {
                val parts = it.split('=', limit = 2); require(parts.size == 2); parts[0] to parts[1]
            }
            require(fields.size == 3 && fields.map { it.first }.toSet() == setOf("v", "id", "token"))
            val values = fields.toMap(); require(values["v"] == "1")
            return BeaconPairing(values.getValue("id"), values.getValue("token"))
        }
    }
}

/** One bounded challenge slot. A newer challenge invalidates the preceding one. */
class BeaconAckAuthenticator(private val id: String, private val token: String, private val elapsed: () -> Long) {
    private var pending: Pair<String, Long>? = null
    @Synchronized fun challenge(requestId: String, clientNonce: String, signature: String): String {
        require(requestId == id && clientNonce.matches(Regex("[a-f0-9]{32}")))
        BeaconProtocol.verify(token, BeaconProtocol.challengeRequest("ack", id, clientNonce), signature)
        return BeaconProtocol.randomHex(16).also { pending = it to elapsed() }
    }
    @Synchronized fun acknowledge(requestId: String, nonce: String, sequence: Long, signature: String,
        commit: (Long) -> Unit): String {
        require(requestId == id && sequence > 0)
        val challenge = pending ?: error("Challenge required")
        require(challenge.first == nonce && elapsed() - challenge.second in 0..60_000L)
        BeaconProtocol.verify(token, BeaconProtocol.historyAck(id, nonce, sequence), signature)
        pending = null // Single-use even if SQLite rejects a future or invalid sequence.
        commit(sequence)
        return BeaconProtocol.sign(token, BeaconProtocol.accepted("ack", id, nonce, sequence))
    }
}
