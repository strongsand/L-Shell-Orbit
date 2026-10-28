package io.github.strongsand.lshell

import androidx.compose.runtime.collectAsState

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.strongsand.lshell.beacon.BeaconDevice
import io.github.strongsand.lshell.beacon.BeaconErrorCode
import io.github.strongsand.lshell.beacon.BeaconRepository
import io.github.strongsand.lshell.beacon.BeaconRuntimeStatus
import io.github.strongsand.lshell.beacon.BeaconSettings
import io.github.strongsand.lshell.beacon.BeaconState
import io.github.strongsand.lshell.beacon.runtimeStatusOrNull
import java.util.Locale

private enum class BeaconPage { MAIN, SETUP, SETTINGS, POWER }

@Composable
fun BeaconScreen(
    state: BeaconState,
    repository: BeaconRepository,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val targetSaver = remember { androidx.compose.runtime.saveable.Saver<BeaconDevice?, List<Any>>(
        save = { device -> device?.let { listOf(it.id, it.name, it.host, it.port) } },
        restore = { BeaconDevice(it[0] as String, it[1] as String, it[2] as String, it[3] as Int, implementation = "android") }) }
    var qrTarget by rememberSaveable(stateSaver = targetSaver) { mutableStateOf<BeaconDevice?>(null) }
    val qrScanner = rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        val target = qrTarget
        qrTarget = null
        if (target != null && result.contents != null) repository.pairAndroid(target, result.contents)
    }
    var pageName by rememberSaveable { mutableStateOf(BeaconPage.MAIN.name) }
    val page = BeaconPage.valueOf(pageName)
    // The toolbar and Android Back return to the same parent page.
    BackHandler(page != BeaconPage.MAIN) {
        pageName = if (page == BeaconPage.POWER) BeaconPage.SETTINGS.name else BeaconPage.MAIN.name
    }
    val haptics = rememberDashHaptics()
    val context = LocalContext.current
    val recentEvents by repository.recentEvents.collectAsState()
    val maintenance by repository.management.collectAsState()
    var permissionSheetVisible by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val bluetoothPermissions = remember {
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (bluetoothPermissions.all { result[it] == true ||
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            Log.i("L-ShellBeacon", "BEACON_PERMISSIONS_OK")
            permissionDenied = false
            permissionSheetVisible = false
            repository.startDiscovery()
        } else {
            Log.i("L-ShellBeacon", "BEACON_PERMISSIONS_DENIED")
            permissionDenied = true
            permissionSheetVisible = true
            repository.reportBluetoothPermissionDenied()
        }
    }
    val startConfiguration = {
        if (!repository.requiresBluetoothSetup()) repository.startDiscovery()
        else if (bluetoothPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }) repository.startDiscovery()
        else {
            permissionSheetVisible = true
        }
    }
    LaunchedEffect(maintenance.resetAccepted) {
        if (maintenance.resetAccepted) pageName = BeaconPage.MAIN.name
    }
    DisposableEffect(repository) {
        onDispose { repository.cancelInteractiveSession() }
    }
    LaunchedEffect(state::class) {
        when (state) {
            is BeaconState.Found, is BeaconState.BleFound -> haptics.perform(DashFeedback.SPECIAL)
            is BeaconState.SetupComplete -> haptics.perform(DashFeedback.CONFIRM)
            else -> Unit
        }
    }
    if (page == BeaconPage.POWER) {
        BeaconPowerDiagnosticScreen(repository, modifier, onBack = { pageName = BeaconPage.SETTINGS.name })
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = hapticClick {
                if (page == BeaconPage.MAIN) onBack() else pageName = BeaconPage.MAIN.name
            }) { Icon(OrbitSymbols.ArrowBack, stringResource(R.string.beacon_back)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.beacon_title), Modifier.orbitSharedBounds("beacon-title"), style = MaterialTheme.typography.headlineMedium)
                if (page == BeaconPage.MAIN) Text(stringResource(R.string.beacon_subtitle),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (page == BeaconPage.MAIN) IconButton(onClick = hapticClick { pageName = BeaconPage.SETTINGS.name }) {
                Icon(OrbitSymbols.Settings, stringResource(R.string.beacon_settings))
            }
        }
        if (page == BeaconPage.MAIN) OutlinedButton(onClick = repository::startAndroidDiscovery,
            enabled = state !is BeaconState.Syncing && state !is BeaconState.Pairing,
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.beacon_android_pair)) }
        if (page == BeaconPage.MAIN) BeaconSyncStatusPill(beaconSyncPillState(state, recentEvents))
        if (page == BeaconPage.MAIN && maintenance.resetAccepted) Text(stringResource(R.string.beacon_reset_accepted))
        if (page == BeaconPage.MAIN && state is BeaconState.Error && state.code == BeaconErrorCode.HISTORY_TIME_UNAVAILABLE)
            BeaconManagementControls(repository, state, showReset = false)
        val pageContent: @Composable (BeaconPage) -> Unit = { selected ->
            when (selected) {
                BeaconPage.SETUP -> BeaconSetupContent()
                BeaconPage.POWER -> Unit // separate diagnostic page keeps its simulated-data banner fixed
                BeaconPage.SETTINGS -> BeaconSettingsContent(
                    initial = repository.settings(),
                    onSave = repository::saveSettings,
                    onForget = repository::forget,
                    repository = repository, state = state,
                    onPower = { pageName = BeaconPage.POWER.name }
                )
                BeaconPage.MAIN -> BeaconMainContent(
                    state = state,
                    onSearch = startConfiguration,
                    onCancelSearch = repository::stopDiscovery,
                    onConnect = { device ->
                        if (device.implementation == "android") {
                            qrTarget = device
                            qrScanner.launch(com.journeyapps.barcodescanner.ScanOptions()
                                .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                                .setPrompt(context.getString(R.string.beacon_android_scan))
                                .setBeepEnabled(false).setBarcodeImageEnabled(false)
                                .setCaptureActivity(BeaconQrCaptureActivity::class.java))
                        } else repository.pair(device)
                    },
                    onBeginProvisioning = repository::beginBleProvisioning,
                    onSubmitWifi = repository::submitWifi,
                    onDismissFound = repository::dismissFound,
                    onSetup = { pageName = BeaconPage.SETUP.name },
                    onSettings = { pageName = BeaconPage.SETTINGS.name },
                    onSync = repository::syncNow,
                    onHistory = onOpenHistory,
                    onClearError = repository::clearError
                )
            }
        }
        if (LocalOrbitMotion.current) AnimatedContent(page, label = "beacon page") { pageContent(it) }
        else pageContent(page)
        if (page == BeaconPage.MAIN) BeaconRecentEvents(recentEvents)
    }
    if (permissionSheetVisible) {
        BeaconPermissionSheet(
            denied = permissionDenied,
            onContinue = {
                Log.i("L-ShellBeacon", "BEACON_PERMISSION_FLOW_START")
                permissionLauncher.launch(bluetoothPermissions)
            },
            onDismiss = {
                permissionSheetVisible = false
                if (state is BeaconState.Error) repository.clearError()
            }
        )
    }
}

@Composable
private fun BeaconMainContent(
    state: BeaconState,
    onSearch: () -> Unit,
    onCancelSearch: () -> Unit,
    onConnect: (BeaconDevice) -> Unit,
    onBeginProvisioning: (BeaconDevice) -> Unit,
    onSubmitWifi: (String, String) -> Unit,
    onDismissFound: () -> Unit,
    onSetup: () -> Unit,
    onSettings: () -> Unit,
    onSync: () -> Unit,
    onHistory: () -> Unit,
    onClearError: () -> Unit
) {
    val stateContent: @Composable (BeaconState) -> Unit = { current ->
        when (current) {
            BeaconState.NotConfigured -> BeaconOnboarding(onSearch, onSetup)
            BeaconState.Discovering -> BeaconDiscovering(onCancelSearch, R.string.beacon_searching, true)
            BeaconState.ScanningBle -> BeaconDiscovering(onCancelSearch, R.string.beacon_ble_searching, false)
            is BeaconState.Found -> BeaconFound(current.device, onConnect, onDismissFound)
            is BeaconState.BleFound -> BeaconFound(
                current.device, onBeginProvisioning, onDismissFound, bluetooth = true
            )
            is BeaconState.Pairing -> BeaconPairing(current.device)
            is BeaconState.WifiCredentialsRequired -> BeaconWifiCredentials(current.device, onSubmitWifi)
            is BeaconState.WifiConnecting -> BeaconProgress(
                R.string.beacon_wifi_connecting, R.string.beacon_wifi_connecting_body
            )
            is BeaconState.DiscoveringLan -> BeaconProgress(
                R.string.beacon_lan_searching, R.string.beacon_mdns_service
            )
            is BeaconState.SetupComplete -> BeaconSetupComplete(current.device, onSearch, onSettings)
            is BeaconState.Connected -> BeaconDashboard(current.status, false, false, onSync, onHistory, onSettings)
            is BeaconState.Recording -> BeaconDashboard(current.status, false, false, onSync, onHistory, onSettings)
            is BeaconState.Syncing -> BeaconDashboard(
                current.status, true, false, onSync, onHistory, onSettings,
                current.processedRecords, current.totalRecords, current.acknowledgedPages
            )
            is BeaconState.DishyUnavailable -> BeaconDashboard(current.status, false, false, onSync, onHistory, onSettings)
            is BeaconState.StorageWarning -> BeaconDashboard(current.status, false, true, onSync, onHistory, onSettings)
            is BeaconState.BeaconOffline -> BeaconOfflineContent(current.device, onSearch, onSettings)
            is BeaconState.Error -> BeaconErrorContent(current.code, current.technicalDetail, onClearError, onSetup)
        }
    }
    if (LocalOrbitMotion.current) {
        AnimatedContent(state, modifier = Modifier.fillMaxWidth(), contentKey = { it::class }, label = "beacon state") { stateContent(it) }
    } else Box(Modifier.fillMaxWidth()) { stateContent(state) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BeaconOnboarding(onSearch: () -> Unit, onSetup: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BeaconHeroIcon()
        Text(stringResource(R.string.beacon_description), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                R.string.beacon_benefit_history to OrbitSymbols.History,
                R.string.beacon_benefit_outages to OrbitSymbols.Router,
                R.string.beacon_benefit_phone to OrbitSymbols.Memory,
                R.string.beacon_benefit_sync to OrbitSymbols.Sync,
                R.string.beacon_benefit_local to OrbitSymbols.Wifi,
                R.string.beacon_benefit_cloud to OrbitSymbols.CloudOff
            ).forEach { (label, icon) ->
                Surface(shape = DashDesign.pill, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, Modifier.size(18.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Button(onClick = hapticClick(DashFeedback.SPECIAL, onSearch), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.beacon_configure))
        }
        OutlinedButton(onClick = hapticClick(action = onSetup), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.beacon_setup))
        }
    }
}

@Composable
private fun BeaconDiscovering(onCancel: () -> Unit, title: Int, showMdns: Boolean) {
    Surface(shape = DashDesign.hero, color = amoledCardColor(MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.size(116.dp).expressiveReveal("beacon-discovery").graphicsLayer { alpha = .18f },
                    shape = DashDesign.cookie, color = MaterialTheme.colorScheme.primary) {}
                BeaconHeroIcon(compact = true)
            }
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            if (showMdns) Text(stringResource(R.string.beacon_mdns_service), style = MaterialTheme.typography.bodySmall,
                color = amoledCardContent(MaterialTheme.colorScheme.onPrimaryContainer).copy(alpha = .75f))
            TextButton(onClick = hapticClick(action = onCancel)) { Text(stringResource(R.string.beacon_not_now)) }
        }
    }
}

@Composable
private fun BeaconFound(
    device: BeaconDevice,
    onConnect: (BeaconDevice) -> Unit,
    onDismiss: () -> Unit,
    bluetooth: Boolean = false
) {
    Surface(Modifier.fillMaxWidth().expressiveReveal(device.id, distance = 16.dp)
        .animateContentSize(if (LocalOrbitMotion.current) spring(dampingRatio = .76f, stiffness = 260f) else snap()),
        shape = DashDesign.hero, color = amoledCardColor(MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BeaconHeroIcon(compact = true)
            Text(stringResource(if (bluetooth) R.string.beacon_ble_found else R.string.beacon_found),
                style = MaterialTheme.typography.headlineSmall)
            Text(device.name, style = MaterialTheme.typography.titleLarge)
            BeaconInfoRow(stringResource(R.string.beacon_address),
                if (bluetooth) stringResource(R.string.beacon_nearby) else "${device.host}:${device.port}")
            if (device.implementation == "android") Text(stringResource(R.string.beacon_android_type), style = MaterialTheme.typography.labelLarge)
            BeaconInfoRow(stringResource(R.string.beacon_firmware),
                device.firmwareVersion ?: stringResource(R.string.beacon_unknown))
            BeaconInfoRow(stringResource(R.string.beacon_status),
                if (device.status == "SETUP_BLE") stringResource(R.string.beacon_ready_to_configure)
                else device.status ?: stringResource(R.string.beacon_unknown))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = hapticClick(DashFeedback.CONFIRM) { onConnect(device) }, modifier = Modifier.weight(1f).orbitSharedBounds("beacon-title")) {
                    Text(stringResource(if (bluetooth) R.string.beacon_configure else if (device.implementation == "android") R.string.beacon_android_scan_action else R.string.beacon_connect))
                }
                TextButton(onClick = hapticClick(action = onDismiss)) { Text(stringResource(R.string.beacon_not_now)) }
            }
        }
    }
}

@Composable
private fun BeaconWifiCredentials(device: BeaconDevice, onSubmit: (String, String) -> Unit) {
    var ssid by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    Surface(shape = DashDesign.hero, color = amoledCardColor(MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(OrbitSymbols.Wifi, null, Modifier.size(40.dp))
            Text(stringResource(R.string.beacon_wifi_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.beacon_wifi_body, device.name), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = ssid,
                onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= 32) ssid = it },
                label = { Text(stringResource(R.string.beacon_wifi_ssid)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= 63) password = it },
                label = { Text(stringResource(R.string.beacon_wifi_password)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = hapticClick { showPassword = !showPassword }) {
                        Icon(if (showPassword) OrbitSymbols.VisibilityOff else OrbitSymbols.Visibility, null)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = hapticClick(DashFeedback.CONFIRM) {
                    val submittedPassword = password
                    password = ""
                    showPassword = false
                    onSubmit(ssid, submittedPassword)
                },
                enabled = ssid.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.beacon_wifi_connect)) }
            Text(stringResource(R.string.beacon_wifi_local_note), style = MaterialTheme.typography.bodySmall,
                color = amoledCardContent(MaterialTheme.colorScheme.onPrimaryContainer).copy(alpha = .75f))
        }
    }
}

@Composable
private fun BeaconProgress(title: Int, body: Int) {
    Surface(shape = DashDesign.hero, color = amoledCardColor(MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BeaconSetupComplete(device: BeaconDevice, onVerify: () -> Unit, onSettings: () -> Unit) {
    Surface(shape = DashDesign.hero, color = amoledCardColor(MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(OrbitSymbols.CheckCircle, null, Modifier.size(54.dp))
            Text(stringResource(R.string.beacon_setup_complete), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.beacon_setup_complete_body, device.name),
                style = MaterialTheme.typography.bodyLarge)
            FilledTonalButton(onClick = hapticClick(action = onVerify)) {
                Text(stringResource(R.string.beacon_verify_network))
            }
            TextButton(onClick = hapticClick(action = onSettings)) {
                Text(stringResource(R.string.beacon_settings))
            }
        }
    }
}

@Composable
private fun BeaconPairing(device: BeaconDevice) {
    Surface(shape = DashDesign.hero, color = amoledCardColor(MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.beacon_pairing), style = MaterialTheme.typography.headlineSmall)
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun BeaconDashboard(
    status: BeaconRuntimeStatus,
    syncing: Boolean,
    storageWarning: Boolean,
    onSync: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    syncedRecords: Long = 0L,
    totalRecords: Long = 0L,
    pages: Int = 0
) {
    var syncCompletedVisible by remember(status.lastSyncAtMillis) {
        mutableStateOf(status.lastSyncAtMillis?.let { System.currentTimeMillis() - it in 0L..5_000L } == true)
    }
    LaunchedEffect(syncCompletedVisible) {
        if (syncCompletedVisible) {
            kotlinx.coroutines.delay(1_800L)
            syncCompletedVisible = false
        }
    }
    val statusText = when {
        syncing -> stringResource(R.string.beacon_online_syncing)
        syncCompletedVisible -> stringResource(R.string.ui_sync_completed)
        !status.storageHealthy -> stringResource(R.string.beacon_online_storage_error)
        status.dishyAvailable == false -> stringResource(R.string.beacon_online_dishy_unavailable)
        status.recording -> stringResource(R.string.beacon_online_recording)
        else -> stringResource(R.string.beacon_online_ready)
    }
    val tone = when {
        !status.storageHealthy -> HealthTone.DANGER
        status.dishyAvailable == false || storageWarning -> HealthTone.CAUTION
        else -> HealthTone.GOOD
    }
    val statusColors = healthColors(tone)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = DashDesign.hero, color = amoledCardColor(statusColors.container),
            contentColor = amoledCardContent(statusColors.foreground)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BeaconActivityIndicator(syncing)
                    Column(Modifier.weight(1f)) {
                        Text(status.device.name, style = MaterialTheme.typography.titleLarge)
                        Text(statusText, style = MaterialTheme.typography.labelLarge)
                    }
                    HealthChip(if (status.recording) androidx.compose.ui.res.stringResource(R.string.editorial_collecting) else androidx.compose.ui.res.stringResource(R.string.editorial_lan), tone)
                }
                if (syncing) Text(stringResource(R.string.beacon_sync_flow, pages),
                    style = MaterialTheme.typography.labelLarge)
                if (syncing && totalRecords > 0L) {
                    Text(androidx.compose.ui.res.stringResource(R.string.editorial_synced_progress, syncedRecords, totalRecords), style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(
                        progress = { (syncedRecords.toFloat() / totalRecords).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = statusColors.foreground,
                        trackColor = statusColors.foreground.copy(alpha = .18f)
                    )
                }
                if (storageWarning) Text(stringResource(
                    if (status.storageHealthy) R.string.beacon_storage_warning else R.string.beacon_storage_error
                ),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BeaconStatTile(androidx.compose.ui.res.stringResource(R.string.beacon_last_sync), relativeTime(status.lastSyncAtMillis), Modifier.weight(1f))
                BeaconStatTile(androidx.compose.ui.res.stringResource(R.string.editorial_pending), status.pendingRecords?.toString() ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BeaconStatTile(androidx.compose.ui.res.stringResource(R.string.editorial_origin),
                    stringResource(beaconImplementationLabel(status.device.implementation)), Modifier.weight(1f))
                BeaconStatTile(androidx.compose.ui.res.stringResource(R.string.function_dish), status.dishyAvailable?.let { if (it) androidx.compose.ui.res.stringResource(R.string.editorial_accessible) else androidx.compose.ui.res.stringResource(R.string.editorial_unreachable) } ?: "—",
                    Modifier.weight(1f))
            }
        }
        BeaconDataCard(stringResource(R.string.beacon_status)) {
            BeaconInfoRow(stringResource(R.string.beacon_address), status.device.host)
            BeaconInfoRow(stringResource(R.string.beacon_contact), relativeTime(status.lastContactAtMillis))
            BeaconInfoRow(stringResource(R.string.beacon_boot_id), status.bootId?.toString() ?: "—")
            BeaconInfoRow(stringResource(R.string.beacon_wifi_rssi), status.wifiRssi?.let { "$it dBm" } ?: "—")
            BeaconInfoRow(stringResource(R.string.beacon_ack), status.acknowledgedSequence?.toString() ?: "—")
            BeaconInfoRow(stringResource(R.string.beacon_imported), status.lastImportedRecords?.toString() ?: "—")
            BeaconInfoRow(stringResource(R.string.beacon_free_storage), status.storageCapacityBytes?.let { capacity ->
                status.storageUsedBytes?.let { used -> "${(capacity - used).coerceAtLeast(0L) / 1024L} KiB" }
            } ?: "—")
            BeaconInfoRow(stringResource(R.string.beacon_last_sample), relativeTime(status.lastSampleAtMillis))
            BeaconInfoRow(stringResource(R.string.beacon_last_collection), relativeTime(status.lastCollectionAtMillis))
            BeaconInfoRow(stringResource(R.string.beacon_last_sync), relativeTime(status.lastSyncAtMillis))
            BeaconInfoRow(stringResource(R.string.beacon_storage), formatStorage(status))
            BeaconInfoRow(stringResource(R.string.beacon_retention), status.estimatedRetentionHours?.let { "$it h" }
                ?: stringResource(R.string.beacon_unknown))
            if (status.device.implementation == "android") Text(stringResource(R.string.beacon_android_type), style = MaterialTheme.typography.labelLarge)
            BeaconInfoRow(stringResource(R.string.beacon_firmware), status.device.firmwareVersion
                ?: stringResource(R.string.beacon_unknown))
            BeaconInfoRow(stringResource(R.string.beacon_wifi), stringResource(
                if (status.wifiConnected) R.string.beacon_connected else R.string.beacon_no
            ))
            BeaconInfoRow(stringResource(R.string.beacon_uptime), status.uptimeSeconds?.let(::formatDuration)
                ?: stringResource(R.string.beacon_unknown))
            BeaconInfoRow(stringResource(R.string.beacon_dishy), status.dishyAvailable?.let {
                stringResource(if (it) R.string.beacon_yes else R.string.beacon_no)
            } ?: stringResource(R.string.beacon_unknown))
            BeaconInfoRow(stringResource(R.string.beacon_pending_records),
                status.pendingRecords?.toString() ?: stringResource(R.string.beacon_unknown))
            BeaconInfoRow(stringResource(R.string.beacon_stored_records),
                status.recordCount?.toString() ?: stringResource(R.string.beacon_unknown))
            BeaconInfoRow(stringResource(R.string.beacon_sequence_range),
                if (status.oldestSequence != null && status.newestSequence != null)
                    "${status.oldestSequence}–${status.newestSequence}"
                else stringResource(R.string.beacon_unknown))
        }
        Button(onClick = hapticClick(DashFeedback.SYNC_STARTED, onSync), enabled = !syncing,
            modifier = Modifier.fillMaxWidth()) { Icon(OrbitSymbols.Sync, null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.beacon_sync_now)) }
        FilledTonalButton(onClick = hapticClick(action = onHistory), modifier = Modifier.fillMaxWidth()) {
            Icon(OrbitSymbols.History, null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.beacon_view_history))
        }
        OutlinedButton(onClick = hapticClick(action = onSettings), modifier = Modifier.fillMaxWidth()) {
            Icon(OrbitSymbols.Settings, null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.beacon_settings))
        }
    }
}

@Composable
private fun BeaconOfflineContent(device: BeaconDevice, onSearch: () -> Unit, onSettings: () -> Unit) {
    Surface(shape = DashDesign.hero, color = amoledCardColor(MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(OrbitSymbols.CloudOff, null, Modifier.size(38.dp))
            Text(stringResource(R.string.beacon_offline), style = MaterialTheme.typography.headlineSmall)
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = hapticClick(action = onSearch)) { Text(stringResource(R.string.beacon_try_again)) }
            TextButton(onClick = hapticClick(action = onSettings)) { Text(stringResource(R.string.beacon_settings)) }
        }
    }
}

@Composable
private fun BeaconErrorContent(code: BeaconErrorCode, technicalDetail: String?, onClear: () -> Unit, onSetup: () -> Unit) {
    val message = stringResource(when (code) {
        BeaconErrorCode.DISCOVERY_FAILED -> R.string.beacon_error_discovery
        BeaconErrorCode.BLUETOOTH_PERMISSION_REQUIRED -> R.string.beacon_error_bluetooth_permission
        BeaconErrorCode.BLUETOOTH_UNAVAILABLE -> R.string.beacon_error_bluetooth_unavailable
        BeaconErrorCode.PROTOCOL_INCOMPATIBLE -> R.string.beacon_error_protocol
        BeaconErrorCode.PAIRING_FAILED -> R.string.beacon_error_pairing
        BeaconErrorCode.BLUETOOTH_BOND_INCONSISTENT -> R.string.beacon_error_bond_inconsistent
        BeaconErrorCode.CREDENTIAL_FAILED -> R.string.beacon_error_credential
        BeaconErrorCode.SYNC_FAILED -> R.string.beacon_error_sync
        BeaconErrorCode.HISTORY_TIME_UNAVAILABLE -> R.string.beacon_error_history_time
    })
    Surface(shape = DashDesign.section, color = amoledCardColor(MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.beacon_error_title), style = MaterialTheme.typography.titleLarge)
            Text(message)
            technicalDetail?.takeIf { it.isNotBlank() && code != BeaconErrorCode.PROTOCOL_INCOMPATIBLE }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = amoledCardContent(MaterialTheme.colorScheme.onErrorContainer).copy(alpha = .75f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = hapticClick(action = onClear)) { Text(stringResource(R.string.beacon_back)) }
                TextButton(onClick = hapticClick(action = onSetup)) { Text(stringResource(R.string.beacon_setup)) }
            }
        }
    }
}

@Composable
private fun BeaconSetupContent() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.beacon_setup), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.beacon_setup_intro))
        BeaconDataCard(stringResource(R.string.beacon_setup)) { Text(stringResource(R.string.beacon_setup_steps)) }
        BeaconDataCard(stringResource(R.string.beacon_led_title)) { Text(stringResource(R.string.beacon_led_states)) }
        BeaconDataCard(stringResource(R.string.beacon_button_title)) { Text(stringResource(R.string.beacon_button_states)) }
    }
}

@Composable
private fun BeaconSettingsContent(initial: BeaconSettings, onSave: (BeaconSettings) -> Unit, onForget: () -> Unit,
    repository: BeaconRepository, state: BeaconState, onPower: () -> Unit) {
    var settings by remember(initial) { mutableStateOf(initial) }
    val maintenance by repository.management.collectAsState()
    var confirmForget by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.beacon_settings), style = MaterialTheme.typography.headlineSmall)
        BeaconDataCard(stringResource(R.string.beacon_settings)) {
            BeaconSettingSwitch(R.string.beacon_auto_sync, settings.automaticSync) {
                settings = settings.copy(automaticSync = it); onSave(settings)
            }
            BeaconSettingSwitch(R.string.beacon_wifi_only, settings.wifiOnly) {
                settings = settings.copy(wifiOnly = it); onSave(settings)
            }
            BeaconSettingSwitch(R.string.beacon_background_sync, settings.backgroundSync) {
                settings = settings.copy(backgroundSync = it); onSave(settings)
            }
            Text(stringResource(R.string.beacon_background_explanation),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (settings.backgroundSync) {
                var chooseInterval by remember { mutableStateOf(false) }
                OutlinedButton(onClick = hapticClick { chooseInterval = true }) {
                    Text(stringResource(R.string.beacon_background_interval, settings.syncIntervalMinutes))
                }
                if (chooseInterval) AlertDialog(
                    onDismissRequest = { chooseInterval = false },
                    title = { Text(stringResource(R.string.beacon_background_sync)) },
                    text = {
                        Column {
                            io.github.strongsand.lshell.beacon.BeaconSyncSchedule.intervals.forEach { minutes ->
                                TextButton(onClick = hapticClick {
                                    settings = settings.copy(syncIntervalMinutes = minutes)
                                    onSave(settings); chooseInterval = false
                                }) { Text(stringResource(R.string.beacon_background_interval, minutes)) }
                            }
                        }
                    },
                    confirmButton = {}
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(stringResource(R.string.beacon_requires_firmware), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            BeaconInfoRow(stringResource(R.string.beacon_retention),
                androidx.compose.ui.res.pluralStringResource(R.plurals.editorial_retention_days, settings.retentionDays, settings.retentionDays))
            BeaconSettingSwitch(R.string.beacon_notifications, settings.notifications, enabled = false) {}
            BeaconSettingSwitch(R.string.beacon_notify_offline, settings.notifyOffline, enabled = false) {}
            BeaconSettingSwitch(R.string.beacon_notify_dishy, settings.notifyDishyUnavailable, enabled = false) {}
        }
        BeaconDataCard(stringResource(R.string.power_development)) {
            Text(stringResource(R.string.power_diagnostic_help), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = hapticClick(action = onPower), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.power_title))
            }
        }
        BeaconManagementControls(repository, state)
        OutlinedButton(onClick = hapticClick { confirmForget = true }, enabled = !maintenance.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(OrbitSymbols.DeleteOutline, null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.beacon_forget))
        }
    }
    if (confirmForget) AlertDialog(onDismissRequest = { confirmForget = false },
        title = { Text(stringResource(R.string.beacon_forget)) },
        text = { Text(stringResource(R.string.beacon_forget_body)) },
        confirmButton = { TextButton(onClick = hapticClick(DashFeedback.CONFIRM) { confirmForget = false; onForget() }) {
            Text(stringResource(R.string.beacon_forget)) } },
        dismissButton = { TextButton(onClick = hapticClick { confirmForget = false }) { Text(stringResource(R.string.beacon_not_now)) } })
}

@Composable
private fun BeaconSettingSwitch(label: Int, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).alpha(if (enabled) 1f else .55f))
        Switch(checked, hapticToggle(onChange), enabled = enabled)
    }
}

@Composable
private fun BeaconHeroIcon(compact: Boolean = false) {
    Surface(shape = DashDesign.cookie, color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(if (compact) 72.dp else 108.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(OrbitSymbols.Memory, null, Modifier.size(if (compact) 34.dp else 50.dp)) }
    }
}

@Composable
private fun BeaconActivityIndicator(syncing: Boolean) {
    if (syncing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    else Surface(Modifier.size(24.dp), shape = DashDesign.cookie,
        color = MaterialTheme.colorScheme.primary) {}

}

@Composable
private fun BeaconStatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun BeaconInfoRow(label: String, value: String) {
    InfoRow(label, value)

}

@Composable
private fun BeaconDataCard(title: String, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(DashDesign.inset)) {
            SectionHeading(title)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun relativeTime(timestamp: Long?): String = timestamp?.takeIf { it > 0L }?.let {
    DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
} ?: stringResource(R.string.beacon_never)

private fun formatDuration(seconds: Long): String = when {
    seconds >= 86_400 -> "${seconds / 86_400} d"
    seconds >= 3_600 -> "${seconds / 3_600} h"
    else -> "${seconds / 60} min"
}

@Composable
private fun formatStorage(status: BeaconRuntimeStatus): String {
    val used = status.storageUsedBytes ?: return stringResource(R.string.beacon_unknown)
    val capacity = status.storageCapacityBytes ?: return "${used / 1_048_576} MiB"
    if (capacity <= 0L) return stringResource(R.string.beacon_unknown)
    return String.format(Locale.getDefault(), "%.0f%%", used.toDouble() / capacity * 100.0)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BeaconHomeCard(state: BeaconState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (state !is BeaconState.Connected && state !is BeaconState.Recording &&
        state !is BeaconState.Syncing && state !is BeaconState.DishyUnavailable &&
        state !is BeaconState.StorageWarning && state !is BeaconState.BeaconOffline &&
        state !is BeaconState.DiscoveringLan && state !is BeaconState.Error) return
    val runtime = state.runtimeStatusOrNull()
    val tone = when (state) {
        is BeaconState.BeaconOffline, is BeaconState.Error -> HealthTone.DANGER
        is BeaconState.DiscoveringLan -> HealthTone.UNKNOWN
        is BeaconState.DishyUnavailable, is BeaconState.StorageWarning -> HealthTone.CAUTION
        else -> HealthTone.GOOD
    }
    val colors = homeStatusColors(tone)
    val label = stringResource(beaconHomeLabel(state))
    Surface(onClick = hapticClick(action = onOpen), modifier = modifier.fillMaxWidth().expressiveReveal("beacon-home", 45).semantics(mergeDescendants = true) {},
        shape = DashDesign.section, color = colors.container, contentColor = colors.foreground) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state is BeaconState.BeaconOffline || state is BeaconState.Error)
                Icon(OrbitSymbols.CloudOff, null, Modifier.size(24.dp))
            else BeaconActivityIndicator(state is BeaconState.Syncing)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.beacon_title), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.orbitSharedBounds("beacon-title"))
                    runtime?.pendingRecords?.takeIf { it > 0L }?.let {
                        Surface(shape = DashDesign.pill, color = colors.foreground.copy(alpha = .12f)) {
                            Text(androidx.compose.ui.res.stringResource(R.string.editorial_pending_count, it), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = colors.foreground.copy(alpha = .86f))
                runtime?.lastSampleAtMillis?.let {
                    Text(stringResource(R.string.rich_sample, relativeTime(it)),
                        style = MaterialTheme.typography.bodySmall, color = colors.foreground.copy(alpha = .76f))
                }

            }
            Icon(OrbitSymbols.KeyboardArrowRight, null, Modifier.size(18.dp))
        }
    }
}


@Composable
private fun BeaconManagementControls(repository: BeaconRepository, state: BeaconState, showReset: Boolean = true) {
    val device = state.runtimeStatusOrNull()?.device ?: when (state) {
        is BeaconState.Error -> state.device
        is BeaconState.BeaconOffline -> state.device
        else -> null
    }
    if (device?.implementation == "android") {
        if (state is BeaconState.Error && state.code == BeaconErrorCode.HISTORY_TIME_UNAVAILABLE)
            Text(stringResource(R.string.beacon_android_clock_help), color = MaterialTheme.colorScheme.error)
        return
    }
    val management by repository.management.collectAsState()
    val preview by repository.discardPreview.collectAsState()
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var resetText by rememberSaveable { mutableStateOf("") }
    val connected = state.runtimeStatusOrNull() != null || state is BeaconState.Error || state is BeaconState.BeaconOffline
    BeaconDataCard(stringResource(R.string.beacon_management_title)) {
        if (showReset) {
            Text(stringResource(R.string.beacon_factory_reset_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = hapticClick { resetText = ""; confirmReset = true },
                enabled = connected && !management.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.beacon_factory_reset_title), color = MaterialTheme.colorScheme.error)
            }
        }
        if (state is BeaconState.Error && state.code == BeaconErrorCode.HISTORY_TIME_UNAVAILABLE) {
            Text(stringResource(R.string.beacon_discard_body), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = hapticClick { repository.previewUnrecoverableHistory() }, enabled = !management.busy) {
                Text(stringResource(R.string.beacon_discard_title))
            }
        }
        if (management.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (management.failed) Text(stringResource(R.string.beacon_management_failed), color = MaterialTheme.colorScheme.error)
        if (management.resetAccepted) Text(stringResource(R.string.beacon_reset_accepted))
        management.discarded?.let { Text(stringResource(R.string.beacon_discard_done, it)) }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { if (!management.busy) confirmReset = false },
        title = { Text(stringResource(R.string.beacon_factory_reset_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.beacon_factory_reset_warning))
            OutlinedTextField(value = resetText, onValueChange = { resetText = it }, singleLine = true,
                label = { Text(stringResource(R.string.beacon_reset_type)) })
        } },
        confirmButton = { TextButton(enabled = resetText == "RESET" && !management.busy,
            onClick = hapticClick(DashFeedback.CONFIRM) { confirmReset = false; repository.requestFactoryReset() }) {
            Text(stringResource(R.string.beacon_factory_reset_action), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = hapticClick { confirmReset = false }) { Text(stringResource(R.string.beacon_not_now)) } }
    )
    preview?.let { selected -> AlertDialog(
        onDismissRequest = repository::dismissDiscardPreview,
        title = { Text(stringResource(R.string.beacon_discard_title)) },
        text = { Text(stringResource(R.string.beacon_discard_confirmation, selected.recordCount, selected.throughSequence)) },
        confirmButton = { TextButton(enabled = !management.busy, onClick = hapticClick(DashFeedback.CONFIRM) {
            repository.discardUnrecoverableHistory(selected)
        }) { Text(stringResource(R.string.beacon_discard_action), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = repository::dismissDiscardPreview) { Text(stringResource(R.string.beacon_not_now)) } }
    ) }
}
