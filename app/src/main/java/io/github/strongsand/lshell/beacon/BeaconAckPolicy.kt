package io.github.strongsand.lshell.beacon

internal object BeaconAckPolicy {
    enum class Mode { LEGACY, AUTHENTICATED }

    fun mode(expected: BeaconDevice, reported: BeaconDevice): Mode {
        require(reported.id == expected.id && reported.protocolVersion == 1) {
            "Beacon identity/protocol changed"
        }
        if (expected.implementation == "android" || "android_beacon_v1" in expected.capabilities) {
            require(reported.implementation == "android" && "authenticated_ack_v1" in reported.capabilities &&
                "absolute_timestamp_v1" in reported.capabilities) { "Android Beacon capabilities changed" }
        }
        if ("authenticated_ack_v1" !in reported.capabilities) return Mode.LEGACY
        require("management_v1" in reported.capabilities) {
            "Authenticated ACK requires management firmware"
        }
        return Mode.AUTHENTICATED
    }
}
