package io.github.strongsand.lshell.beacon

data class BeaconDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val firmwareVersion: String? = null,
    val status: String? = null,
    val bleAddress: String? = null,
    val protocolVersion: Int? = null
)

data class PairedBeacon(
    val device: BeaconDevice,
    val keyAlias: String,
    val pairedAtMillis: Long,
    val lastSyncedSequence: Long = 0L
)

data class BeaconRuntimeStatus(
    val device: BeaconDevice,
    val lastSampleAtMillis: Long? = null,
    val lastCollectionAtMillis: Long? = null,
    val lastSyncAtMillis: Long? = null,
    val storageUsedBytes: Long? = null,
    val storageCapacityBytes: Long? = null,
    val estimatedRetentionHours: Int? = null,
    val wifiConnected: Boolean = false,
    val wifiName: String? = null,
    val uptimeSeconds: Long? = null,
    val dishyAvailable: Boolean? = null,
    val pendingRecords: Long? = null,
    val recording: Boolean = false,
    val storageHealthy: Boolean = true,
    val oldestSequence: Long? = null,
    val newestSequence: Long? = null,
    val recordCount: Long? = null
)

enum class BeaconSampleSource { LOCAL_PHONE, BEACON }

enum class BeaconErrorCode {
    BLUETOOTH_PERMISSION_REQUIRED,
    BLUETOOTH_UNAVAILABLE,
    DISCOVERY_FAILED,
    PROTOCOL_INCOMPATIBLE,
    PAIRING_FAILED,
    BLUETOOTH_BOND_INCONSISTENT,
    CREDENTIAL_FAILED,
    SYNC_FAILED
}

enum class BeaconProvisioningEvent {
    CONNECTED,
    WIFI_CONNECTING,
    COMPLETE,
    BOND_INCONSISTENT,
    ERROR,
    DISCONNECTED
}

data class BeaconSample(
    val beaconId: String,
    val sequence: Long,
    val timestampMillis: Long,
    val downloadMbps: Float? = null,
    val uploadMbps: Float? = null,
    val latencyMs: Float? = null,
    val dropPercent: Float? = null,
    val obstructionPercent: Float? = null,
    val powerWatts: Float? = null,
    val signal: Float? = null,
    val dishCounter: Long? = null,
    val dishState: String? = null,
    val activeAlerts: List<String> = emptyList(),
    val source: BeaconSampleSource = BeaconSampleSource.BEACON
)

sealed interface BeaconState {
    data object NotConfigured : BeaconState
    data object Discovering : BeaconState
    data class Found(val device: BeaconDevice) : BeaconState
    data object ScanningBle : BeaconState
    data class BleFound(val device: BeaconDevice) : BeaconState
    data class Pairing(val device: BeaconDevice) : BeaconState
    data class WifiCredentialsRequired(val device: BeaconDevice) : BeaconState
    data class WifiConnecting(val device: BeaconDevice) : BeaconState
    data class DiscoveringLan(val device: BeaconDevice) : BeaconState
    data class SetupComplete(val device: BeaconDevice) : BeaconState
    data class Connected(val status: BeaconRuntimeStatus) : BeaconState
    data class Recording(val status: BeaconRuntimeStatus) : BeaconState
    data class Syncing(
        val status: BeaconRuntimeStatus,
        val progress: Float? = null,
        val processedRecords: Long = 0L,
        val totalRecords: Long = 0L
    ) : BeaconState
    data class DishyUnavailable(val status: BeaconRuntimeStatus) : BeaconState
    data class BeaconOffline(val device: BeaconDevice) : BeaconState
    data class StorageWarning(val status: BeaconRuntimeStatus) : BeaconState
    data class Error(
        val code: BeaconErrorCode,
        val technicalDetail: String? = null,
        val device: BeaconDevice? = null
    ) : BeaconState
}

data class BeaconSettings(
    val automaticSync: Boolean = true,
    val wifiOnly: Boolean = true,
    val retentionDays: Int = 30,
    val notifications: Boolean = true,
    val notifyOffline: Boolean = true,
    val notifyDishyUnavailable: Boolean = true
)

fun BeaconState.runtimeStatusOrNull(): BeaconRuntimeStatus? = when (this) {
    is BeaconState.Connected -> status
    is BeaconState.Recording -> status
    is BeaconState.Syncing -> status
    is BeaconState.DishyUnavailable -> status
    is BeaconState.StorageWarning -> status
    else -> null
}
