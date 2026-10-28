package io.github.strongsand.lshell.beacon

import org.json.JSONObject

/** Fixed v1 field order, no JSON canonicalization or secret transmission. */
internal object BeaconManagementAuth {
    fun sign(keyHex: String, message: String) = io.github.strongsand.lshell.beaconprotocol.BeaconProtocol.sign(keyHex, message)
    fun verify(key: String, message: String, signature: String) = io.github.strongsand.lshell.beaconprotocol.BeaconProtocol.verify(key, message, signature)
    fun challengeRequest(action: String, id: String, clientNonce: String) = io.github.strongsand.lshell.beaconprotocol.BeaconProtocol.challengeRequest(action, id, clientNonce)
    fun command(action: String, id: String, nonce: String, fields: Map<String, String>) =
        "POST\n$action\n$id\n$nonce\n${fields["confirmation"].orEmpty()}\n${fields["boot_id"].orEmpty()}\n${fields["epoch_ms"].orEmpty()}"
    fun historyAck(id: String, nonce: String, sequence: Long) = io.github.strongsand.lshell.beaconprotocol.BeaconProtocol.historyAck(id, nonce, sequence)
    fun challengeResponse(action: String, clientNonce: String, json: JSONObject) =
        io.github.strongsand.lshell.beaconprotocol.BeaconProtocol.challengeResponse(action, clientNonce,
            json.getString("beacon_id"), json.getString("nonce"), json.getLong("boot_id"),
            json.getBoolean("time_anchor_available"), json.getLong("from_ack"),
            json.getLong("through_sequence"), json.getLong("record_count"))
    fun accepted(action: String, id: String, nonce: String, through: Long) = io.github.strongsand.lshell.beaconprotocol.BeaconProtocol.accepted(action, id, nonce, through)
}
