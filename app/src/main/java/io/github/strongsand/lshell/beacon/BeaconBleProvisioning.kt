package io.github.strongsand.lshell.beacon

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject

interface BeaconBleProvisioning {
    val events: Flow<Pair<BeaconProvisioningEvent, String?>>
    fun scan(): Flow<Result<BeaconDevice>>
    fun connect(device: BeaconDevice): Result<Unit>
    fun submitWifi(ssid: String, password: String): Result<Unit>
    fun close()
}

@SuppressLint("MissingPermission")
class AndroidBeaconBleProvisioning(context: Context) : BeaconBleProvisioning {
    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val _events = MutableSharedFlow<Pair<BeaconProvisioningEvent, String?>>(extraBufferCapacity = 16)
    override val events = _events.asSharedFlow()
    private var gatt: BluetoothGatt? = null
    private var service: BluetoothGattService? = null
    private val writes = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private val descriptors = ArrayDeque<BluetoothGattDescriptor>()
    private var writing = false
    private var activeWriteValue: ByteArray? = null
    private var descriptorWriting = false
    private var infoRequested = false
    private var bondReceiverRegistered = false
    private var targetDevice: BluetoothDevice? = null
    private var bondRequested = false
    private var gattStarted = false
    private var gattConnected = false
    private var servicesDiscoveryStarted = false
    private var servicesDiscoveryAttempts = 0
    private var notificationsSetupStarted = false
    private var gattReady = false
    private var terminalSecurityError = false
    private var startedWithExistingBond = false
    private var firmwareBondConfirmed = false
    private var bondStatePollAttempts = 0

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            if (device.address != targetDevice?.address) return
            val previousState = intent.getIntExtra(
                BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                BluetoothDevice.BOND_NONE
            )
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            Log.i(TAG, "BEACON_BOND_STATE=$bondState")
            when (bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    cancelBondStatePoll()
                    Log.i(TAG, "BOND_STATE_BONDED")
                    continueGattSetupAfterBond()
                }
                BluetoothDevice.BOND_BONDING -> {
                    Log.i(TAG, "BOND_STATE_BONDING")
                    scheduleBondStatePoll()
                }
                BluetoothDevice.BOND_NONE -> {
                    Log.i(TAG, "BOND_STATE_NONE")
                    if (previousState == BluetoothDevice.BOND_BONDED) {
                        cancelBondStatePoll()
                        emitBondInconsistent()
                    } else if (previousState == BluetoothDevice.BOND_BONDING) {
                        cancelBondStatePoll()
                        _events.tryEmit(
                            BeaconProvisioningEvent.ERROR to "Bluetooth security confirmation failed"
                        )
                    }
                }
            }
        }
    }

    override fun scan(): Flow<Result<BeaconDevice>> = callbackFlow {
        if (!hasScanPermission()) {
            trySend(Result.failure(SecurityException("Bluetooth scan permission required")))
            close()
            return@callbackFlow
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || adapter?.isEnabled != true) {
            trySend(Result.failure(IllegalStateException("Bluetooth unavailable")))
            close()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (!name.startsWith("L-Shell Beacon")) return
                val shortId = name.substringAfterLast(' ', "unknown")
                discoveredDevices[result.device.address] = result.device
                trySend(Result.success(BeaconDevice(
                    id = "ble-$shortId",
                    name = name,
                    host = "",
                    port = 0,
                    status = "SETUP_BLE",
                    bleAddress = result.device.address,
                    protocolVersion = PROTOCOL_VERSION
                )))
            }

            override fun onScanFailed(errorCode: Int) {
                trySend(Result.failure(IllegalStateException("BLE scan error $errorCode")))
                close()
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        try {
            scanner.startScan(listOf(filter), ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        } catch (error: RuntimeException) {
            trySend(Result.failure(error))
            close(error)
        }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    override fun connect(device: BeaconDevice): Result<Unit> = runCatching {
        check(hasScanPermission()) { "Bluetooth scan permission required" }
        check(hasConnectPermission()) { "Bluetooth connect permission required" }
        val address = requireNotNull(device.bleAddress) { "Missing BLE address" }
        val remote = discoveredDevices[address]
            ?: requireNotNull(adapter?.getRemoteDevice(address)) { "Bluetooth unavailable" }
        closeGatt()
        Log.i(TAG, "BEACON_SELECTED")
        Log.i(TAG, "DEVICE_ADDRESS=${maskAddress(remote.address)}")
        Log.i(TAG, "CURRENT_BOND_STATE=${remote.bondState}")
        targetDevice = remote
        startedWithExistingBond = remote.bondState == BluetoothDevice.BOND_BONDED
        registerBondReceiver()
        connectGattOnce(remote)
    }

    override fun submitWifi(ssid: String, password: String): Result<Unit> = runCatching {
        require(ssid.toByteArray(StandardCharsets.UTF_8).size in 1..32) { "Invalid SSID" }
        require(password.toByteArray(StandardCharsets.UTF_8).size <= 63) { "Invalid password" }
        enqueue(SSID_UUID, ssid).getOrThrow()
        enqueue(PASSWORD_UUID, password).getOrThrow()
        enqueue(APPLY_UUID, "APPLY").getOrThrow()
    }

    override fun close() = closeGatt()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (this@AndroidBeaconBleProvisioning.gatt !== gatt) return
            Log.i(TAG, "GATT_CONNECTION_STATE status=$status newState=$newState")
            Log.i(TAG, "GATT_STATUS=$status")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                cancelGattConnectTimeout()
                gattConnected = true
                Log.i(TAG, "GATT_CONNECTED")
                Log.i(TAG, "BEACON_GATT_CONNECTED")
                mainHandler.removeCallbacks(gattSetupTimeout)
                mainHandler.postDelayed(gattSetupTimeout, GATT_SETUP_TIMEOUT_MS)
                // Service discovery itself is public. Doing it before bonding avoids Android
                // stacks that stall discovery while link encryption is still settling.
                discoverServicesOnce()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cancelGattConnectTimeout()
                cancelServicesDiscoveryCallbacks()
                cancelBondStatePoll()
                gattConnected = false
                servicesDiscoveryStarted = false
                notificationsSetupStarted = false
                Log.i(TAG, "GATT_DISCONNECTED")
                service = null
                gattReady = false
                if (isSecurityFailure(status) || existingBondNotConfirmed()) {
                    emitBondInconsistent()
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    _events.tryEmit(BeaconProvisioningEvent.ERROR to "Bluetooth connection failed: $status")
                } else {
                    _events.tryEmit(BeaconProvisioningEvent.DISCONNECTED to null)
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                cancelGattConnectTimeout()
                if (isSecurityFailure(status) || existingBondNotConfirmed()) emitBondInconsistent()
                else _events.tryEmit(BeaconProvisioningEvent.ERROR to "Bluetooth connection failed: $status")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            cancelServicesDiscoveryCallbacks()
            Log.i(TAG, "GATT_STATUS=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                servicesDiscoveryStarted = false
                if (isSecurityFailure(status) && existingBondNotConfirmed()) emitBondInconsistent()
                else retryServicesDiscovery("status_$status")
                return
            }
            Log.i(TAG, "SERVICES_DISCOVERED")
            service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                _events.tryEmit(BeaconProvisioningEvent.ERROR to "Beacon GATT service unavailable")
                return
            }
            beginBondAfterGattConnected(gatt.device)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            Log.i(TAG, "GATT_STATUS=$status")
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == INFO_UUID) {
                val text = value.toString(StandardCharsets.UTF_8)
                val info = runCatching { JSONObject(text) }.getOrNull()
                if (info?.optBoolean("bond_known", false) != true) {
                    emitBondInconsistent()
                } else {
                    firmwareBondConfirmed = true
                    mainHandler.removeCallbacks(gattSetupTimeout)
                    _events.tryEmit(BeaconProvisioningEvent.CONNECTED to text)
                }
            } else if (characteristic.uuid == INFO_UUID) {
                if (isSecurityFailure(status) || existingBondNotConfirmed()) emitBondInconsistent()
                else _events.tryEmit(BeaconProvisioningEvent.ERROR to "Could not read Beacon identity")
            }
        }

        @Deprecated("Legacy callback retained for Android 8–12")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) = onCharacteristicRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) = handleNotification(characteristic.uuid, value)

        @Deprecated("Legacy callback retained for Android 8–12")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) =
            handleNotification(characteristic.uuid, characteristic.value ?: byteArrayOf())

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.i(TAG, "GATT_STATUS=$status")
            activeWriteValue?.fill(0)
            activeWriteValue = null
            writing = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                clearPendingWrites()
                if (isSecurityFailure(status) || existingBondNotConfirmed()) emitBondInconsistent()
                else _events.tryEmit(BeaconProvisioningEvent.ERROR to "GATT write failed: $status")
            } else drainWrites()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(TAG, "GATT_STATUS=$status")
            descriptorWriting = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                descriptors.clear()
                if (isSecurityFailure(status) || existingBondNotConfirmed()) emitBondInconsistent()
                else _events.tryEmit(BeaconProvisioningEvent.ERROR to "Could not enable Beacon notifications")
            } else {
                drainDescriptors()
            }
        }
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        val text = value.toString(StandardCharsets.UTF_8)
        if (uuid == STATE_UUID) {
            val event = when (text) {
                "WAITING_WIFI_CONFIG", "AUTHORIZED" -> null
                "WIFI_CONNECTING" -> BeaconProvisioningEvent.WIFI_CONNECTING
                "COMPLETE" -> BeaconProvisioningEvent.COMPLETE
                "ERROR" -> BeaconProvisioningEvent.ERROR
                else -> null
            }
            event?.let { _events.tryEmit(it to null) }
        } else if (uuid == RESULT_UUID) {
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            if (parsed?.optBoolean("ok") == false) {
                val code = parsed.optString("code", "INTERNAL_ERROR")
                if (code == "BLE_SECURITY_REQUIRED") emitBondInconsistent()
                else _events.tryEmit(BeaconProvisioningEvent.ERROR to code)
            }
        }
    }

    private fun enqueue(uuid: UUID, text: String): Result<Unit> = runCatching {
        check(hasConnectPermission()) { "Bluetooth connect permission required" }
        check(gattReady) { "Beacon GATT is not ready" }
        val characteristic = requireNotNull(service?.getCharacteristic(uuid)) { "Missing GATT characteristic" }
        writes += characteristic to text.toByteArray(StandardCharsets.UTF_8)
        drainWrites()
    }

    private fun drainWrites() {
        if (writing || writes.isEmpty()) return
        val (characteristic, value) = writes.removeFirst()
        activeWriteValue = value
        writing = true
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt?.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { characteristic.value = value; gatt?.writeCharacteristic(characteristic) == true }
        }
        if (!started) {
            activeWriteValue?.fill(0)
            activeWriteValue = null
            writing = false
            clearPendingWrites()
            _events.tryEmit(BeaconProvisioningEvent.ERROR to "Could not start GATT write")
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, uuid: UUID): Boolean {
        val characteristic = service?.getCharacteristic(uuid) ?: run {
            _events.tryEmit(BeaconProvisioningEvent.ERROR to "Missing Beacon notification characteristic")
            return false
        }
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            _events.tryEmit(BeaconProvisioningEvent.ERROR to "Could not enable Beacon notifications")
            return false
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: run {
            _events.tryEmit(BeaconProvisioningEvent.ERROR to "Missing Beacon notification descriptor")
            return false
        }
        descriptors += descriptor
        return true
    }

    private fun drainDescriptors() {
        if (descriptorWriting) return
        if (descriptors.isEmpty()) {
            if (!gattReady) {
                gattReady = true
                Log.i(TAG, "NOTIFICATIONS_READY")
                Log.i(TAG, "BEACON_SERVICES_READY")
                requestDeviceInfo()
            }
            return
        }
        val descriptor = descriptors.removeFirst()
        descriptorWriting = true
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt?.writeDescriptor(descriptor) == true
            }
        }
        if (!started) {
            descriptorWriting = false
            descriptors.clear()
            _events.tryEmit(BeaconProvisioningEvent.ERROR to "Could not enable Beacon notifications")
        }
    }

    private fun requestDeviceInfo() {
        if (infoRequested || !gattReady) return
        val characteristic = service?.getCharacteristic(INFO_UUID) ?: run {
            _events.tryEmit(BeaconProvisioningEvent.ERROR to "Missing Beacon identity characteristic")
            return
        }
        infoRequested = true
        if (gatt?.readCharacteristic(characteristic) != true) {
            infoRequested = false
            if (existingBondNotConfirmed()) emitBondInconsistent()
            else _events.tryEmit(BeaconProvisioningEvent.ERROR to "Could not read Beacon identity")
        }
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        ContextCompat.registerReceiver(
            app,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            // Bluetooth broadcasts may originate from the privileged system Bluetooth
            // process instead of the framework UID. The actual device address and its
            // live bondState are still verified before advancing setup.
            ContextCompat.RECEIVER_EXPORTED
        )
        bondReceiverRegistered = true
    }

    private fun beginBondAfterGattConnected(device: BluetoothDevice) {
        if (!gattConnected || terminalSecurityError) return
        Log.i(TAG, "CURRENT_BOND_STATE=${device.bondState}")
        Log.i(TAG, "BEACON_BOND_STATE=${device.bondState}")
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                cancelBondStatePoll()
                Log.i(TAG, "BOND_STATE_BONDED")
                Log.i(TAG, "CREATE_BOND_SKIPPED_ALREADY_BONDED")
                continueGattSetupAfterBond()
            }
            BluetoothDevice.BOND_BONDING -> {
                Log.i(TAG, "BOND_STATE_BONDING")
                scheduleBondStatePoll(reset = true)
            }
            BluetoothDevice.BOND_NONE -> {
                Log.i(TAG, "BOND_STATE_NONE")
                if (bondRequested) return
                bondRequested = true
                Log.i(TAG, "CREATE_BOND_CALLED")
                if (!device.createBond()) {
                    when (device.bondState) {
                        BluetoothDevice.BOND_BONDING -> {
                            Log.i(TAG, "BOND_STATE_BONDING")
                            scheduleBondStatePoll(reset = true)
                        }
                        BluetoothDevice.BOND_BONDED -> {
                            Log.i(TAG, "BOND_STATE_BONDED")
                            continueGattSetupAfterBond()
                        }
                        else -> _events.tryEmit(
                            BeaconProvisioningEvent.ERROR to "Could not start Bluetooth security"
                        )
                    }
                } else scheduleBondStatePoll(reset = true)
            }
        }
    }

    private fun scheduleBondStatePoll(reset: Boolean = false) {
        if (reset) bondStatePollAttempts = 0
        mainHandler.removeCallbacks(bondStatePoll)
        mainHandler.postDelayed(bondStatePoll, BOND_STATE_POLL_MS)
    }

    private val bondStatePoll = object : Runnable {
        override fun run() {
            val device = targetDevice ?: return
            if (!gattConnected || terminalSecurityError || gattReady) return
            val state = device.bondState
            Log.i(TAG, "BOND_STATE_POLL=$state")
            if (state == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "BOND_STATE_BONDED")
                continueGattSetupAfterBond()
                return
            }
            bondStatePollAttempts++
            if (bondStatePollAttempts >= BOND_STATE_POLL_MAX_ATTEMPTS) {
                Log.i(TAG, "BOND_STATE_POLL_TIMEOUT")
                _events.tryEmit(
                    BeaconProvisioningEvent.ERROR to "Bluetooth security confirmation timed out"
                )
                return
            }
            mainHandler.postDelayed(this, BOND_STATE_POLL_MS)
        }
    }

    private fun cancelBondStatePoll() {
        mainHandler.removeCallbacks(bondStatePoll)
        bondStatePollAttempts = 0
    }

    private fun connectGattOnce(device: BluetoothDevice) {
        if (gattStarted || terminalSecurityError) return
        gattStarted = true
        Log.i(TAG, "CONNECT_GATT_START")
        gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
        Log.i(TAG, "CONNECT_GATT_RETURNED")
        if (gatt == null) {
            gattStarted = false
            if (existingBondNotConfirmed()) emitBondInconsistent()
            else _events.tryEmit(BeaconProvisioningEvent.ERROR to "Could not create Bluetooth connection")
        } else {
            mainHandler.postDelayed(gattConnectTimeout, GATT_CONNECT_TIMEOUT_MS)
        }
    }

    private fun discoverServicesOnce() {
        if (!gattConnected || servicesDiscoveryStarted || terminalSecurityError) return
        gatt ?: return
        mainHandler.removeCallbacks(startServicesDiscovery)
        Log.i(TAG, "SERVICES_DISCOVERY_SCHEDULED")
        mainHandler.postDelayed(startServicesDiscovery, SERVICES_DISCOVERY_SETTLE_MS)
    }

    private val startServicesDiscovery = Runnable {
        val currentGatt = gatt ?: return@Runnable
        if (!gattConnected || servicesDiscoveryStarted || gattReady || terminalSecurityError) return@Runnable
        servicesDiscoveryStarted = true
        servicesDiscoveryAttempts++
        Log.i(TAG, "SERVICES_DISCOVERY_START attempt=$servicesDiscoveryAttempts")
        val started = currentGatt.discoverServices()
        Log.i(TAG, "SERVICES_DISCOVERY_RETURNED=${if (started) 1 else 0}")
        if (started) {
            mainHandler.postDelayed(servicesDiscoveryTimeout, SERVICES_DISCOVERY_TIMEOUT_MS)
        } else {
            servicesDiscoveryStarted = false
            retryServicesDiscovery("not_started")
        }
    }

    private val servicesDiscoveryTimeout = Runnable {
        if (!servicesDiscoveryStarted || gattReady || terminalSecurityError) return@Runnable
        Log.i(TAG, "SERVICES_DISCOVERY_TIMEOUT")
        servicesDiscoveryStarted = false
        retryServicesDiscovery("timeout")
    }

    private fun retryServicesDiscovery(reason: String) {
        if (!gattConnected || gattReady || terminalSecurityError) return
        if (servicesDiscoveryAttempts >= SERVICES_DISCOVERY_MAX_ATTEMPTS) {
            Log.i(TAG, "SERVICES_DISCOVERY_FAILED reason=$reason")
            if (existingBondNotConfirmed()) emitBondInconsistent()
            else _events.tryEmit(
                BeaconProvisioningEvent.ERROR to "Não foi possível preparar os serviços Bluetooth do Beacon."
            )
            return
        }
        Log.i(TAG, "SERVICES_DISCOVERY_RETRY reason=$reason")
        mainHandler.removeCallbacks(startServicesDiscovery)
        mainHandler.postDelayed(startServicesDiscovery, SERVICES_DISCOVERY_RETRY_MS)
    }

    private fun continueGattSetupAfterBond() {
        if (!gattConnected || terminalSecurityError || gattReady) return
        if (service == null) {
            discoverServicesOnce()
            return
        }
        val currentGatt = gatt ?: return
        if (currentGatt.device.bondState != BluetoothDevice.BOND_BONDED) return
        cancelBondStatePoll()
        mainHandler.removeCallbacks(startNotificationsSetup)
        Log.i(TAG, "NOTIFICATIONS_SETUP_SCHEDULED")
        mainHandler.postDelayed(startNotificationsSetup, BOND_SETTLE_MS)
    }

    private val startNotificationsSetup = Runnable {
        val currentGatt = gatt ?: return@Runnable
        if (!gattConnected || terminalSecurityError || gattReady || notificationsSetupStarted) return@Runnable
        if (currentGatt.device.bondState != BluetoothDevice.BOND_BONDED || service == null) return@Runnable
        notificationsSetupStarted = true
        Log.i(TAG, "NOTIFICATIONS_SETUP_START")
        if (!enableNotifications(currentGatt, STATE_UUID) ||
            !enableNotifications(currentGatt, RESULT_UUID)) {
            notificationsSetupStarted = false
            return@Runnable
        }
        drainDescriptors()
    }

    private fun cancelServicesDiscoveryCallbacks() {
        mainHandler.removeCallbacks(startServicesDiscovery)
        mainHandler.removeCallbacks(servicesDiscoveryTimeout)
        mainHandler.removeCallbacks(startNotificationsSetup)
    }

    private val gattConnectTimeout = Runnable {
        if (!gattStarted || gattConnected || terminalSecurityError) return@Runnable
        Log.i(TAG, "GATT_CONNECT_TIMEOUT")
        _events.tryEmit(BeaconProvisioningEvent.ERROR to "Bluetooth GATT connection timed out")
    }

    private val gattSetupTimeout = Runnable {
        if (!gattConnected || firmwareBondConfirmed || terminalSecurityError) return@Runnable
        Log.i(TAG, "GATT_SETUP_TIMEOUT")
        _events.tryEmit(
            BeaconProvisioningEvent.ERROR to "A conexão segura foi criada, mas o Beacon não concluiu a preparação Bluetooth."
        )
    }

    private fun cancelGattConnectTimeout() {
        mainHandler.removeCallbacks(gattConnectTimeout)
    }

    private fun emitBondInconsistent() {
        if (terminalSecurityError) return
        terminalSecurityError = true
        cancelBondStatePoll()
        mainHandler.removeCallbacks(gattSetupTimeout)
        gattReady = false
        clearPendingWrites()
        descriptors.clear()
        _events.tryEmit(BeaconProvisioningEvent.BOND_INCONSISTENT to null)
    }

    private fun existingBondNotConfirmed(): Boolean =
        startedWithExistingBond && !firmwareBondConfirmed

    private fun maskAddress(address: String): String {
        val parts = address.split(':')
        return if (parts.size == 6) "XX:XX:XX:${parts.takeLast(3).joinToString(":")}" else "masked"
    }

    private fun isSecurityFailure(status: Int): Boolean =
        status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
            status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION

    private fun hasScanPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun closeGatt() {
        cancelGattConnectTimeout()
        cancelServicesDiscoveryCallbacks()
        cancelBondStatePoll()
        mainHandler.removeCallbacks(gattSetupTimeout)
        activeWriteValue?.fill(0); activeWriteValue = null
        clearPendingWrites(); writing = false
        descriptors.clear(); descriptorWriting = false
        infoRequested = false
        gattReady = false
        service = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        targetDevice = null
        bondRequested = false
        gattStarted = false
        gattConnected = false
        servicesDiscoveryStarted = false
        servicesDiscoveryAttempts = 0
        notificationsSetupStarted = false
        terminalSecurityError = false
        startedWithExistingBond = false
        firmwareBondConfirmed = false
        if (bondReceiverRegistered) {
            runCatching { app.unregisterReceiver(bondReceiver) }
            bondReceiverRegistered = false
        }
    }

    private fun clearPendingWrites() {
        writes.forEach { (_, value) -> value.fill(0) }
        writes.clear()
    }

    companion object {
        private const val TAG = "L-ShellBeaconBLE"
        private const val GATT_CONNECT_TIMEOUT_MS = 20_000L
        private const val GATT_SETUP_TIMEOUT_MS = 35_000L
        private const val SERVICES_DISCOVERY_SETTLE_MS = 600L
        private const val SERVICES_DISCOVERY_TIMEOUT_MS = 8_000L
        private const val SERVICES_DISCOVERY_RETRY_MS = 900L
        private const val SERVICES_DISCOVERY_MAX_ATTEMPTS = 3
        private const val BOND_SETTLE_MS = 700L
        private const val BOND_STATE_POLL_MS = 500L
        private const val BOND_STATE_POLL_MAX_ATTEMPTS = 60
        const val PROTOCOL_VERSION = 1
        val SERVICE_UUID: UUID = UUID.fromString("7d2ea1d0-6f2b-4b5f-9e20-4c53484c0001")
        val INFO_UUID: UUID = UUID.fromString("7d2ea1d0-6f2b-4b5f-9e20-4c53484c0002")
        val STATE_UUID: UUID = UUID.fromString("7d2ea1d0-6f2b-4b5f-9e20-4c53484c0003")
        val SSID_UUID: UUID = UUID.fromString("7d2ea1d0-6f2b-4b5f-9e20-4c53484c0004")
        val PASSWORD_UUID: UUID = UUID.fromString("7d2ea1d0-6f2b-4b5f-9e20-4c53484c0005")
        val APPLY_UUID: UUID = UUID.fromString("7d2ea1d0-6f2b-4b5f-9e20-4c53484c0006")
        val RESULT_UUID: UUID = UUID.fromString("7d2ea1d0-6f2b-4b5f-9e20-4c53484c0007")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
