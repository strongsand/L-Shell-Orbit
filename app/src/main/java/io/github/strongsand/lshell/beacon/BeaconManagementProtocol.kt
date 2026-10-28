package io.github.strongsand.lshell.beacon

import org.json.JSONObject

data class BeaconDiscardPreview(val fromAck: Long, val throughSequence: Long, val recordCount: Long)

internal object BeaconManagementProtocol {
    fun challenge(json: JSONObject, device: BeaconDevice): String {
        require(json.getString("beacon_id") == device.id) { "Beacon identity changed" }
        return json.getString("nonce").also { require(it.matches(Regex("[a-f0-9]{32}"))) }
    }
    fun discardPreview(json: JSONObject): BeaconDiscardPreview = BeaconDiscardPreview(
        json.getLong("from_ack"), json.getLong("through_sequence"), json.getLong("record_count")
    ).also {
        require(it.fromAck >= 0 && it.throughSequence > it.fromAck && it.recordCount > 0 &&
            it.recordCount <= it.throughSequence - it.fromAck) { "No unrecoverable pending prefix" }
    }
    fun verifyDiscard(expected: BeaconDiscardPreview, current: BeaconDiscardPreview) {
        require(expected == current) { "Discard preview changed; review again" }
    }
}

data class BeaconManagementState(val busy: Boolean = false, val failed: Boolean = false,
    val resetAccepted: Boolean = false, val discarded: Long? = null)
