package com.hurricane.lshell.beacon

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.hurricane.lshell.HistoryStore
import com.hurricane.lshell.DailyReportStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

class BeaconRepository private constructor(
    private val preferences: BeaconPreferences,
    private val discovery: BeaconDiscovery,
    private val bleProvisioning: BeaconBleProvisioning,
    private val lanClient: BeaconLanClient,
    private val credentials: BeaconCredentialStore,
    private val syncManager: BeaconSyncManager,
    private val connectivityManager: ConnectivityManager?,
    private val scope: CoroutineScope
) : AutoCloseable {
    private val initialDevice = preferences.configuredBeacon() ?: preferences.pairedBeacon()?.device
    private val _state = MutableStateFlow<BeaconState>(
        initialDevice?.let { BeaconState.DiscoveringLan(it) } ?: BeaconState.NotConfigured
    )
    val state: StateFlow<BeaconState> = _state.asStateFlow()

    private var discoveryJob: Job? = null
    private var savedHostJob: Job? = null
    private var discoveryTimeoutJob: Job? = null
    private var refreshJob: Job? = null
    private var provisioningJob: Job? = null
    private var provisioningTimeoutJob: Job? = null
    private var setupDevice: BeaconDevice? = null
    private val syncMutex = Mutex()
    private var discoveryGeneration = 0L
    private var resolvedGeneration = -1L
    @Volatile private var wifiAvailable = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val reconnect = !wifiAvailable
            wifiAvailable = true
            if (reconnect && knownDevice() != null) triggerAutoSync("wifi_reconnected")
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val reconnect = hasWifi && !wifiAvailable
            wifiAvailable = hasWifi
            if (reconnect && knownDevice() != null) triggerAutoSync("wifi_reconnected")
        }

        override fun onLost(network: Network) {
            wifiAvailable = false
        }
    }

    init {
        scope.launch {
            state.map(::uiStateName).distinctUntilChanged().collect {
                Log.i(TAG, "BEACON_UI_STATE=$it")
            }
        }
        runCatching {
            connectivityManager?.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                networkCallback
            )
        }.onFailure { Log.w(TAG, "Unable to observe Wi-Fi changes", it) }
    }

    fun startDiscovery() {
        cancelLanDiscovery()
        knownDevice()?.let { startLanDiscovery(it); return }
        Log.i(TAG, "BEACON_NEARBY_SCAN_START")
        _state.value = BeaconState.ScanningBle
        discoveryJob = scope.launch {
            bleProvisioning.scan().collect { result ->
                result.fold(
                    onSuccess = { device ->
                        if (_state.value is BeaconState.ScanningBle) {
                            Log.i(TAG, "BEACON_NEARBY_FOUND")
                            Log.i(TAG, "BEACON_NEARBY_SCAN_STOP")
                            _state.value = BeaconState.BleFound(device)
                            discoveryJob?.cancel()
                        }
                    },
                    onFailure = { error ->
                        Log.i(TAG, "BEACON_NEARBY_SCAN_STOP")
                        _state.value = BeaconState.Error(
                            if (error is SecurityException) BeaconErrorCode.BLUETOOTH_PERMISSION_REQUIRED
                            else BeaconErrorCode.BLUETOOTH_UNAVAILABLE,
                            error.message
                        )
                    }
                )
            }
        }
        discoveryTimeoutJob = scope.launch {
            delay(BLE_SCAN_TIMEOUT_MS)
            if (_state.value is BeaconState.ScanningBle) {
                Log.i(TAG, "BEACON_NEARBY_SCAN_STOP")
                discoveryJob?.cancel()
                _state.value = BeaconState.NotConfigured
            }
        }
    }

    private fun startLanDiscovery(expected: BeaconDevice, preserveRuntimeState: Boolean = false) {
        cancelLanDiscovery()
        val generation = ++discoveryGeneration
        resolvedGeneration = -1L
        Log.i(TAG, "BEACON_DISCOVERY_START")
        if (!preserveRuntimeState || _state.value.runtimeStatusOrNull() == null) {
            _state.value = BeaconState.DiscoveringLan(expected)
        }

        if (expected.host.isNotBlank() && expected.port in 1..65535) {
            savedHostJob = scope.launch {
                Log.i(TAG, "BEACON_SAVED_HOST_TRY")
                validateCandidate(expected, expected, generation).fold(
                    onSuccess = { (device, status) ->
                        Log.i(TAG, "BEACON_SAVED_HOST_OK")
                        completeDiscovery(device, status, generation)
                    },
                    onFailure = { error ->
                        if (generation == discoveryGeneration && resolvedGeneration != generation) {
                            Log.i(TAG, "BEACON_SAVED_HOST_FAIL")
                            if (error is IncompatibleProtocolException) {
                                resolvedGeneration = generation
                                _state.value = BeaconState.Error(
                                    BeaconErrorCode.PROTOCOL_INCOMPATIBLE,
                                    "Protocol ${error.version}",
                                    expected
                                )
                                discoveryJob?.cancel()
                                discoveryTimeoutJob?.cancel()
                            }
                        }
                    }
                )
            }
        }

        discoveryJob = scope.launch {
            Log.i(TAG, "MDNS_DISCOVERY_START")
            discovery.discover().collect { result ->
                if (generation != discoveryGeneration || resolvedGeneration == generation) return@collect
                result.onSuccess { discovered ->
                    Log.i(TAG, "MDNS_FOUND")
                    validateCandidate(discovered, expected, generation).fold(
                        onSuccess = { (device, status) -> completeDiscovery(device, status, generation) },
                        onFailure = { error ->
                            if (error is IncompatibleProtocolException) {
                                resolvedGeneration = generation
                                _state.value = BeaconState.Error(
                                    BeaconErrorCode.PROTOCOL_INCOMPATIBLE,
                                    "Protocol ${error.version}",
                                    discovered
                                )
                                savedHostJob?.cancel()
                                discoveryTimeoutJob?.cancel()
                            }
                        }
                    )
                }.onFailure { Log.w(TAG, "mDNS discovery error", it) }
            }
        }

        discoveryTimeoutJob = scope.launch {
            delay(LAN_DISCOVERY_TIMEOUT_MS)
            if (generation == discoveryGeneration && resolvedGeneration != generation) {
                Log.i(TAG, "MDNS_TIMEOUT")
                resolvedGeneration = generation
                discoveryJob?.cancel()
                savedHostJob?.cancel()
                _state.value = BeaconState.BeaconOffline(expected)
            }
        }
    }

    private suspend fun validateCandidate(
        candidate: BeaconDevice,
        expected: BeaconDevice,
        generation: Long
    ): Result<Pair<BeaconDevice, BeaconRuntimeStatus>> = runCatching {
        check(generation == discoveryGeneration && resolvedGeneration != generation)
        val device = lanClient.info(candidate).getOrThrow()
        if (expected.id.isNotBlank() && !expected.id.startsWith("ble-") && device.id != expected.id) {
            throw DifferentBeaconException()
        }
        if (device.protocolVersion != SUPPORTED_PROTOCOL_VERSION) {
            throw IncompatibleProtocolException(device.protocolVersion)
        }
        device to lanClient.status(device).getOrThrow()
    }

    private suspend fun completeDiscovery(
        device: BeaconDevice,
        status: BeaconRuntimeStatus,
        generation: Long
    ) {
        if (generation != discoveryGeneration || resolvedGeneration == generation) return
        resolvedGeneration = generation
        preferences.saveConfiguredBeacon(device)
        activateConfigured(device, preferences.settings().automaticSync, status, "discovery")
        discoveryJob?.cancel()
        savedHostJob?.cancel()
        discoveryTimeoutJob?.cancel()
    }

    fun beginBleProvisioning(device: BeaconDevice) {
        Log.i(TAG, "BEACON_PAIRING_START")
        cancelLanDiscovery()
        setupDevice = device
        _state.value = BeaconState.Pairing(device)
        provisioningJob?.cancel()
        provisioningTimeoutJob?.cancel()
        provisioningJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            bleProvisioning.events.collect { (event, detail) -> handleProvisioningEvent(event, detail) }
        }
        provisioningTimeoutJob = scope.launch {
            delay(PROVISIONING_TIMEOUT_MS)
            if (_state.value is BeaconState.Pairing ||
                _state.value is BeaconState.WifiCredentialsRequired ||
                _state.value is BeaconState.WifiConnecting
            ) {
                provisioningJob?.cancel()
                bleProvisioning.close()
                _state.value = BeaconState.Error(
                    BeaconErrorCode.PAIRING_FAILED,
                    "Beacon setup timed out",
                    device
                )
            }
        }
        bleProvisioning.connect(device).onFailure {
            provisioningJob?.cancel()
            provisioningTimeoutJob?.cancel()
            bleProvisioning.close()
            _state.value = BeaconState.Error(BeaconErrorCode.PAIRING_FAILED, it.message, device)
        }
    }

    private fun handleProvisioningEvent(event: BeaconProvisioningEvent, detail: String?) {
        var device = setupDevice ?: return
        when (event) {
            BeaconProvisioningEvent.CONNECTED -> {
                val info = detail?.let { runCatching { JSONObject(it) }.getOrNull() }
                val beaconId = info?.optString("beacon_id").orEmpty()
                val protocolVersion = info?.optInt("protocol_version", -1) ?: -1
                if (beaconId.isBlank() || protocolVersion != SUPPORTED_PROTOCOL_VERSION) {
                    provisioningJob?.cancel()
                    provisioningTimeoutJob?.cancel()
                    bleProvisioning.close()
                    _state.value = BeaconState.Error(
                        BeaconErrorCode.PROTOCOL_INCOMPATIBLE,
                        "Protocol $protocolVersion",
                        device
                    )
                    return
                }
                device = device.copy(
                    id = beaconId,
                    firmwareVersion = info?.optString("firmware_version")?.takeIf { it.isNotBlank() },
                    protocolVersion = protocolVersion
                )
                setupDevice = device
                _state.value = BeaconState.WifiCredentialsRequired(device)
            }
            BeaconProvisioningEvent.WIFI_CONNECTING -> _state.value = BeaconState.WifiConnecting(device)
            BeaconProvisioningEvent.COMPLETE -> {
                Log.i(TAG, "BEACON_PROVISIONING_COMPLETE")
                preferences.saveConfiguredBeacon(device)
                startLanDiscovery(device)
                provisioningJob?.cancel()
                provisioningTimeoutJob?.cancel()
                bleProvisioning.close()
            }
            BeaconProvisioningEvent.BOND_INCONSISTENT -> {
                provisioningJob?.cancel()
                provisioningTimeoutJob?.cancel()
                bleProvisioning.close()
                _state.value = BeaconState.Error(BeaconErrorCode.BLUETOOTH_BOND_INCONSISTENT, device = device)
            }
            BeaconProvisioningEvent.ERROR -> {
                provisioningJob?.cancel()
                provisioningTimeoutJob?.cancel()
                bleProvisioning.close()
                _state.value = BeaconState.Error(BeaconErrorCode.PAIRING_FAILED, detail, device)
            }
            BeaconProvisioningEvent.DISCONNECTED -> {
                provisioningTimeoutJob?.cancel()
                if (_state.value !is BeaconState.DiscoveringLan && _state.value !is BeaconState.SetupComplete) {
                    _state.value = BeaconState.Error(BeaconErrorCode.PAIRING_FAILED, "BLE disconnected", device)
                }
            }
        }
    }

    fun submitWifi(ssid: String, password: String) {
        val device = setupDevice ?: return
        bleProvisioning.submitWifi(ssid, password).onFailure {
            _state.value = BeaconState.Error(BeaconErrorCode.PAIRING_FAILED, it.message, device)
        }
    }

    fun reportBluetoothPermissionDenied() {
        _state.value = BeaconState.Error(BeaconErrorCode.BLUETOOTH_PERMISSION_REQUIRED)
    }

    fun requiresBluetoothSetup(): Boolean = knownDevice() == null

    fun stopDiscovery() {
        if (_state.value is BeaconState.ScanningBle || _state.value is BeaconState.BleFound) {
            Log.i(TAG, "BEACON_NEARBY_SCAN_STOP")
        }
        cancelLanDiscovery()
        provisioningTimeoutJob?.cancel()
        if (_state.value is BeaconState.Discovering || _state.value is BeaconState.Found ||
            _state.value is BeaconState.ScanningBle || _state.value is BeaconState.BleFound ||
            _state.value is BeaconState.DiscoveringLan
        ) {
            _state.value = knownDevice()?.let { BeaconState.BeaconOffline(it) } ?: BeaconState.NotConfigured
        }
    }

    fun cancelInteractiveSession() {
        cancelLanDiscovery()
        provisioningJob?.cancel()
        provisioningTimeoutJob?.cancel()
        bleProvisioning.close()
        if (_state.value is BeaconState.Discovering || _state.value is BeaconState.ScanningBle ||
            _state.value is BeaconState.BleFound || _state.value is BeaconState.Pairing ||
            _state.value is BeaconState.WifiCredentialsRequired ||
            _state.value is BeaconState.WifiConnecting || _state.value is BeaconState.DiscoveringLan
        ) {
            _state.value = knownDevice()?.let { BeaconState.BeaconOffline(it) } ?: BeaconState.NotConfigured
        }
    }

    /** Connects a Beacon already discovered over LAN using the implemented local protocol. */
    fun pair(device: BeaconDevice) {
        cancelLanDiscovery()
        scope.launch {
            val validated = lanClient.info(device).getOrElse {
                _state.value = BeaconState.Error(BeaconErrorCode.DISCOVERY_FAILED, it.message, device)
                return@launch
            }
            if (validated.protocolVersion != SUPPORTED_PROTOCOL_VERSION) {
                _state.value = BeaconState.Error(
                    BeaconErrorCode.PROTOCOL_INCOMPATIBLE,
                    "Protocol ${validated.protocolVersion}",
                    validated
                )
                return@launch
            }
            preferences.saveConfiguredBeacon(validated)
            activateConfigured(validated, preferences.settings().automaticSync, reason = "manual_connect")
        }
    }

    fun triggerAutoSync(reason: String) {
        Log.i(TAG, "BEACON_AUTO_SYNC_TRIGGER=$reason")
        if (knownDevice() != null) refreshStatus(reason)
    }

    fun refreshStatus(reason: String = "status_refresh") {
        val device = knownDevice() ?: return
        if (refreshJob?.isActive == true || syncMutex.isLocked) return
        refreshJob = scope.launch {
            Log.i(TAG, "BEACON_DISCOVERY_START")
            Log.i(TAG, "BEACON_SAVED_HOST_TRY")
            lanClient.info(device).fold(
                onSuccess = { validated ->
                    if (validated.protocolVersion != SUPPORTED_PROTOCOL_VERSION) {
                        _state.value = BeaconState.Error(
                            BeaconErrorCode.PROTOCOL_INCOMPATIBLE,
                            "Protocol ${validated.protocolVersion}",
                            validated
                        )
                    } else {
                        lanClient.status(validated).fold(
                            onSuccess = { status ->
                                Log.i(TAG, "BEACON_SAVED_HOST_OK")
                                preferences.saveConfiguredBeacon(validated)
                                activateConfigured(
                                    validated,
                                    preferences.settings().automaticSync,
                                    status,
                                    reason
                                )
                            },
                            onFailure = {
                                Log.i(TAG, "BEACON_SAVED_HOST_FAIL")
                                startLanDiscovery(device, preserveRuntimeState = true)
                            }
                        )
                    }
                },
                onFailure = {
                    Log.i(TAG, "BEACON_SAVED_HOST_FAIL")
                    startLanDiscovery(device, preserveRuntimeState = true)
                }
            )
        }
    }

    fun syncNow() {
        val device = preferences.configuredBeacon() ?: preferences.pairedBeacon()?.device ?: return
        val current = _state.value.runtimeStatusOrNull() ?: return
        scope.launch {
            synchronizeWithRetry(device, current, "manual")
        }
    }

    private suspend fun activateConfigured(
        device: BeaconDevice,
        sync: Boolean,
        knownStatus: BeaconRuntimeStatus? = null,
        reason: String = "online"
    ) {
        val status = knownStatus ?: lanClient.status(device).getOrElse {
            _state.value = BeaconState.BeaconOffline(device)
            return
        }
        val current = withLocalSyncTime(status)
        _state.value = runtimeState(current)
        if (sync && (current.pendingRecords ?: 0L) > 0L) {
            synchronizeWithRetry(device, current, reason)
        }
    }

    private suspend fun synchronizeWithRetry(
        device: BeaconDevice,
        status: BeaconRuntimeStatus,
        reason: String
    ) {
        if (!syncMutex.tryLock()) return
        try {
            var failure: Throwable? = null
            repeat(SYNC_MAX_ATTEMPTS) { attempt ->
                val result = syncManager.synchronize(device) { processed, total ->
                    _state.value = BeaconState.Syncing(
                        status = withLocalSyncTime(status),
                        progress = if (total > 0L) processed.toFloat() / total else 1f,
                        processedRecords = processed,
                        totalRecords = total
                    )
                }
                if (result.isSuccess) {
                    lanClient.status(device).fold(
                        onSuccess = { refreshed -> _state.value = runtimeState(withLocalSyncTime(refreshed)) },
                        onFailure = { _state.value = runtimeState(withLocalSyncTime(status)) }
                    )
                    return
                }
                failure = result.exceptionOrNull()
                if (attempt + 1 < SYNC_MAX_ATTEMPTS) {
                    Log.i(TAG, "SYNC_RETRY=${attempt + 1}")
                    delay(SYNC_RETRY_DELAYS_MS[attempt])
                }
            }
            _state.value = BeaconState.Error(BeaconErrorCode.SYNC_FAILED, failure?.message, device)
        } finally {
            syncMutex.unlock()
        }
    }

    private fun withLocalSyncTime(status: BeaconRuntimeStatus): BeaconRuntimeStatus = status.copy(
        lastSyncAtMillis = preferences.lastSyncAtMillis() ?: status.lastSyncAtMillis
    )

    fun settings(): BeaconSettings = preferences.settings()
    fun saveSettings(settings: BeaconSettings) = preferences.saveSettings(settings)

    fun forget() {
        cancelLanDiscovery()
        provisioningJob?.cancel()
        provisioningTimeoutJob?.cancel()
        bleProvisioning.close()
        preferences.pairedBeacon()?.keyAlias?.let(credentials::delete)
        preferences.clearPairing()
        setupDevice = null
        _state.value = BeaconState.NotConfigured
    }

    fun dismissFound() = stopDiscovery()

    fun clearError() {
        _state.value = knownDevice()?.let { BeaconState.BeaconOffline(it) } ?: BeaconState.NotConfigured
    }

    override fun close() {
        cancelLanDiscovery()
        refreshJob?.cancel()
        provisioningJob?.cancel()
        provisioningTimeoutJob?.cancel()
        bleProvisioning.close()
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        syncManager.close()
        scope.cancel()
    }

    private fun cancelLanDiscovery() {
        discoveryGeneration++
        discoveryJob?.cancel()
        savedHostJob?.cancel()
        discoveryTimeoutJob?.cancel()
        discoveryJob = null
        savedHostJob = null
        discoveryTimeoutJob = null
    }

    private fun knownDevice(): BeaconDevice? =
        preferences.configuredBeacon() ?: preferences.pairedBeacon()?.device

    private fun runtimeState(status: BeaconRuntimeStatus): BeaconState = when {
        !status.storageHealthy -> BeaconState.StorageWarning(status)
        status.dishyAvailable == false -> BeaconState.DishyUnavailable(status)
        status.storageCapacityBytes != null && status.storageCapacityBytes > 0L &&
            status.pendingRecords != null &&
            status.pendingRecords.toDouble() / (status.storageCapacityBytes / 64L).coerceAtLeast(1L) >= .9 ->
            BeaconState.StorageWarning(status)
        status.recording -> BeaconState.Recording(status)
        else -> BeaconState.Connected(status)
    }

    private fun uiStateName(state: BeaconState): String = when (state) {
        BeaconState.Discovering, is BeaconState.DiscoveringLan, BeaconState.ScanningBle -> "DISCOVERING"
        is BeaconState.Connected, is BeaconState.SetupComplete -> "ONLINE"
        is BeaconState.Recording -> "RECORDING"
        is BeaconState.Syncing -> "SYNCING"
        is BeaconState.BeaconOffline -> "OFFLINE"
        is BeaconState.Error -> if (state.code == BeaconErrorCode.PROTOCOL_INCOMPATIBLE)
            "PROTOCOL_INCOMPATIBLE" else "ERROR"
        else -> state::class.simpleName?.uppercase() ?: "UNKNOWN"
    }

    private class DifferentBeaconException : IllegalStateException()
    private class IncompatibleProtocolException(val version: Int?) : IllegalStateException()

    companion object {
        private const val TAG = "L-ShellBeacon"
        private const val SUPPORTED_PROTOCOL_VERSION = 1
        private const val BLE_SCAN_TIMEOUT_MS = 8_000L
        private const val LAN_DISCOVERY_TIMEOUT_MS = 12_000L
        private const val PROVISIONING_TIMEOUT_MS = 180_000L
        private const val SYNC_MAX_ATTEMPTS = 3
        private val SYNC_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_500L)

        fun create(context: Context): BeaconRepository {
            val app = context.applicationContext
            val preferences = BeaconPreferences(app)
            val lanClient = AndroidBeaconLanClient()
            return BeaconRepository(
                preferences = preferences,
                discovery = AndroidNsdBeaconDiscovery(app),
                bleProvisioning = AndroidBeaconBleProvisioning(app),
                lanClient = lanClient,
                credentials = AndroidKeystoreCredentialStore(),
                syncManager = BeaconSyncManager(
                    historyStore = HistoryStore(app),
                    preferences = preferences,
                    transport = lanClient,
                    onHistoryPersisted = { start, end ->
                        DailyReportStore(app).markStaleForRange(start, end)
                    }
                ),
                connectivityManager = app.getSystemService(ConnectivityManager::class.java),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            )
        }
    }
}
