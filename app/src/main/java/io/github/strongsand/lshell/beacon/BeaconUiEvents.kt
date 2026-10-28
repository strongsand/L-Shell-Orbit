package io.github.strongsand.lshell.beacon

enum class BeaconUiEventKind { ONLINE, SYNC_COMPLETE, ACK, ERROR, NEW_COLLECTION, STORAGE_RECOVERED }
data class BeaconUiEvent(val timestamp: Long, val kind: BeaconUiEventKind, val count: Long? = null)
fun appendBeaconEvent(events: List<BeaconUiEvent>, event: BeaconUiEvent): List<BeaconUiEvent> =
    (events + event).takeLast(16)
