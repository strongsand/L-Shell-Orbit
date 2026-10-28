package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.core.network.grpc.RouterClientInfo
import io.github.strongsand.lshell.core.network.grpc.RouterInfo
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun RouterScreen(modifier: Modifier = Modifier) {
    val runtime = remember { RouterTelemetryRuntime.shared }
    val telemetry by rememberRouterTelemetry(includeDetails = true)
    var router by remember { mutableStateOf<RouterInfo?>(null) }
    val error = telemetry.error
    val refreshing = telemetry.refreshing || (!telemetry.includesDetails && error == null)
    var confirmReboot by remember { mutableStateOf(false) }
    var topologyInfo by rememberSaveable { mutableStateOf(false) }
    var actionBusy by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var actionDetail by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(telemetry.info, telemetry.includesDetails) {
        if (telemetry.includesDetails) telemetry.info?.let { router = retainRouterSnapshot(router, it) }
    }

    val info = router
    val repeaters = remember(info?.clients) { info?.let { RouterTopology.reportedRepeaters(it.clients) }.orEmpty() }
    val otherClients = remember(info?.clients) { info?.let { RouterTopology.unassignedClients(it.clients) }.orEmpty() }
    LazyColumn(modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp)) {
        item("heading") { Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(OrbitSymbols.Router, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(androidx.compose.ui.res.stringResource(R.string.editorial_router), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            FilledTonalIconButton(enabled = !refreshing, onClick = hapticClick { runtime.requestRefresh() }) {
                Icon(OrbitSymbols.Refresh, contentDescription = androidx.compose.ui.res.stringResource(R.string.editorial_refresh_router))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.router_refresh_interval), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp)) {
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(12.dp))

        } }
        item("error") { Column {
        if (error != null) {
            RouterCard(stringResource(R.string.router_connection), MaterialTheme.colorScheme.errorContainer) {
                Text(androidx.compose.ui.res.stringResource(R.string.editorial_router_error), color = amoledCardContent(MaterialTheme.colorScheme.onErrorContainer))
                if (LocalExperience.current.technical) Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.router_connection_help))
            }
            Spacer(Modifier.height(12.dp))
        }
        } }
        if (info == null) {
            item("loading") {
                if (refreshing && error == null) Text(stringResource(R.string.density_loading),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
        item("commands") {
            Column {
        RouterCard(stringResource(R.string.router_commands), MaterialTheme.colorScheme.surfaceContainerLow, OrbitSymbols.Tune) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitActionButton(style = OrbitActionStyle.COMMAND, onClick = hapticClick { confirmReboot = true }, enabled = !actionBusy,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.router_reboot))
                }
                OrbitActionButton(style = OrbitActionStyle.COMMAND, onClick = hapticClick {
                    actionBusy = true
                    scope.launch {
                        actionMessage = runtime.refresh().fold(
                            onSuccess = {
                                router = retainRouterSnapshot(router, it)
                                actionDetail = null
                                if (it.hasPendingUpdate)
                                    context.getString(R.string.editorial_router_update_pending)
                                else context.getString(R.string.editorial_router_no_update)
                            },
                            onFailure = { actionDetail = it.message; context.getString(R.string.editorial_router_error) })
                        actionBusy = false
                    }
                }, enabled = !actionBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.router_check_update))
                }
            }
            actionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (LocalExperience.current.technical) actionDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.height(12.dp))

            }
        }
        item("state") {
            Column {
        RouterCard(stringResource(R.string.router_state), MaterialTheme.colorScheme.primaryContainer) {
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.editorial_devices_found, info.clients.size, info.clients.size), style = MaterialTheme.typography.headlineSmall, color = amoledCardContent(MaterialTheme.colorScheme.onPrimaryContainer))
            Spacer(Modifier.height(8.dp))
            RouterDetailRow(stringResource(R.string.router_uptime), duration(info.uptimeSeconds))
            RouterDetailRow("WAN IPv4", info.wanIpv4)
            RouterDetailRow("WAN IPv6", info.wanIpv6.joinToString("\n"))
        }
        Spacer(Modifier.height(12.dp))

            }
        }
        item("latency") {
            Column {
        RouterCard(stringResource(R.string.router_latency), MaterialTheme.colorScheme.surfaceContainerLow, OrbitSymbols.Speed) {
            RouterDetailRow("Internet", milliseconds(info.pingMs))
            RouterDetailRow(stringResource(R.string.router_dish), milliseconds(info.dishPingMs))
            RouterDetailRow("POP", milliseconds(info.popPingMs))
        }
        Spacer(Modifier.height(12.dp))

            }
        }
        item("hardware") {
            Column {
        RouterCard(stringResource(R.string.router_hardware), MaterialTheme.colorScheme.surfaceContainerLow, OrbitSymbols.Memory) {
            RouterDetailRow("ID", info.id)
            RouterDetailRow("Hardware", info.hardware)
            RouterDetailRow("Software", info.software)
            RouterDetailRow(stringResource(R.string.router_factory), info.manufacturedSoftware)
        }
        Spacer(Modifier.height(12.dp))

            }
        }
        item("networks") {
            Column {
        if (info.networks.isNotEmpty()) {
            RouterCard(stringResource(R.string.router_networks), MaterialTheme.colorScheme.surfaceContainerHigh) {
                info.networks.forEachIndexed { index, network ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text(network.domain.ifBlank { context.getString(R.string.router_network_number, index + 1) }, style = MaterialTheme.typography.titleMedium)
                    RouterDetailRow("IPv4", network.ipv4)
                    RouterDetailRow("IPv6", network.ipv6.joinToString("\n"))
                    RouterDetailRow("Ethernet", network.ethernetClients.toString())
                    RouterDetailRow("Wi-Fi 2,4 GHz", network.wifi24Clients.toString())
                    RouterDetailRow("Wi-Fi 5 GHz", network.wifi5Clients.toString())
                }
            }
            Spacer(Modifier.height(12.dp))
        }

            }
        }
        item("boot-alerts") {
            Column {
        RouterCard(stringResource(R.string.router_boot_alerts), MaterialTheme.colorScheme.surfaceContainerHigh) {
            RouterDetailRow(stringResource(R.string.router_boot_reason), routerBootLabel(info.bootReason))
            RouterDetailRow(stringResource(R.string.router_total), info.bootCount.takeIf { it > 0 }?.toString().orEmpty())
            info.bootCounts.forEach { (reason, count) -> RouterDetailRow(routerBootLabel(reason), count.toString()) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (info.alerts.isEmpty()) Text(stringResource(R.string.router_no_alerts))
            else info.alerts.forEach { Text("• ${stringResource(RouterClientPresentation.alertLabel(it))}", color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(20.dp))

            }
        }
        item("devices-heading") { Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(OrbitSymbols.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.router_devices), style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(10.dp))
        } }
        if (info.clients.isEmpty()) {
            item("no-clients") {
                RouterCard(stringResource(R.string.router_no_clients), MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(stringResource(R.string.router_clients_empty))
                }
            }
        } else {
            if (repeaters.isNotEmpty()) {
                item("repeaters-heading") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pluralStringResource(R.plurals.rich_mesh_nodes, repeaters.size), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = hapticClick { topologyInfo = true }) {
                            Icon(OrbitSymbols.Info, stringResource(R.string.rich_mesh_info))
                        }
                    }
                }
                items(repeaters, key = { "client:" + it.mac.ifBlank { it.ipv4.ifBlank { it.name } } }, contentType = { "client" }) { repeater ->
                    Column { RouterClientCard(repeater); Spacer(Modifier.height(10.dp)) }
                }
                item("repeaters-spacing") { Spacer(Modifier.height(16.dp)) }
            }
            if (repeaters.isNotEmpty() && otherClients.isNotEmpty()) {
                item("other-heading") {
                    Column {
                        Text(stringResource(R.string.rich_other_devices), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            items(otherClients, key = { "client:" + it.mac.ifBlank { it.ipv4.ifBlank { it.name } } }, contentType = { "client" }) { connected ->
                Column { RouterClientCard(connected); Spacer(Modifier.height(10.dp)) }
            }
        }
        }
    }
    if (topologyInfo) {
        AlertDialog(onDismissRequest = { topologyInfo = false },
            icon = { Icon(OrbitSymbols.Hub, null) },
            title = { Text(stringResource(R.string.rich_mesh_info)) },
            text = { Text(stringResource(R.string.router_topology_limit)) },
            confirmButton = { TextButton(onClick = hapticClick { topologyInfo = false }) { Text(stringResource(R.string.rich_done)) } })
    }
    if (confirmReboot) {
        AlertDialog(onDismissRequest = { confirmReboot = false },
            title = { Text(stringResource(R.string.router_reboot_question)) },
            text = { Text(stringResource(R.string.router_reboot_warning)) },
            confirmButton = { TextButton(onClick = hapticClick(DashFeedback.CONFIRM) {
                confirmReboot = false
                actionBusy = true
                scope.launch {
                    actionMessage = runtime.reboot().fold(
                        onSuccess = { actionDetail = null; context.getString(R.string.editorial_router_command_sent) },
                        onFailure = { actionDetail = it.message; context.getString(R.string.editorial_router_command_error) })
                    actionBusy = false
                }
            }) { Text(stringResource(R.string.router_reboot)) } },
            dismissButton = { TextButton(onClick = hapticClick { confirmReboot = false }) { Text(stringResource(R.string.router_cancel)) } })
    }
}

@Composable
internal fun RouterClientCard(client: RouterClientInfo) {
    var details by rememberSaveable(client.mac, client.ipv4, client.name) { mutableStateOf(false) }
    val motion = LocalOrbitMotion.current
    val rotation = androidx.compose.animation.core.animateFloatAsState(if (details) 180f else 0f,
        OrbitMotion.spec(OrbitMotionRole.SETTLE, motion), label = "client disclosure")
    val role = stringResource(RouterClientPresentation.roleLabel(client.role))
    val band = stringResource(RouterClientPresentation.interfaceLabel(client.interfaceKind))
    val status = stringResource(if (client.active) R.string.router_active else R.string.router_inactive)
    val disclosure = stringResource(if (details) R.string.reports_expanded else R.string.reports_collapsed)
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (client.reportedRole == 2) OrbitSymbols.Hub else OrbitSymbols.Devices, null,
                        Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(routerClientName(client), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                }
                Text("$role · $band", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HomeStatusPill(status, if (client.active) HealthTone.GOOD else HealthTone.UNKNOWN)
                client.signalDbm?.let {
                    val value = format(it)
                    val spoken = stringResource(R.string.rich_signal_spoken, value)
                    Text("${stringResource(R.string.router_signal)} · $value dBm",
                        Modifier.clearAndSetSemantics { contentDescription = spoken }, style = MaterialTheme.typography.labelLarge)
                }
            }
            val counters = RouterClientPresentation.counters(client)
            if (counters.isNotEmpty()) ReportSummaryGrid(counters.map { (label, value) ->
                { ReportSummaryMetric(stringResource(label), bytes(value)) }
            }, minimumWidthDp = 300f)
            TextButton(onClick = hapticClick { details = !details },
                modifier = Modifier.semantics { stateDescription = disclosure }) {
                Text(stringResource(R.string.router_details))
                Icon(OrbitSymbols.ExpandMore, null, Modifier.graphicsLayer { rotationZ = rotation.value })
            }
            AnimatedVisibility(details,
                enter = if (motion) fadeIn() + expandVertically(animationSpec = OrbitMotion.spec(OrbitMotionRole.STATE, true)) else EnterTransition.None,
                exit = if (motion) fadeOut() + shrinkVertically(animationSpec = OrbitMotion.spec(OrbitMotionRole.STATE, true)) else ExitTransition.None) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.router_unassigned), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RouterDetailRow("IPv4", client.ipv4)
                    RouterDetailRow("IPv6", client.ipv6.joinToString("\n"))
                    RouterDetailRow("MAC", client.mac)
                    if (client.snrDb != null) RouterDetailRow(stringResource(R.string.router_snr), "${format(client.snrDb)} dB")
                    if (client.connectedSeconds > 0) RouterDetailRow(stringResource(R.string.router_associated), duration(client.connectedSeconds))
                    if (LocalExperience.current.technical) {
                        RouterDetailRow(stringResource(R.string.rich_mesh_raw_role), client.reportedRole.toString())
                    }
                }
            }
        }
    }
}

@Composable
private fun RouterCard(title: String, color: androidx.compose.ui.graphics.Color, icon: ImageVector = OrbitSymbols.Router, content: @Composable ColumnScope.() -> Unit) {
    val contentColor = amoledCardContent(contentColorFor(color))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 18.dp, bottomEnd = 30.dp, bottomStart = 18.dp),
        colors = CardDefaults.cardColors(containerColor = amoledCardColor(color), contentColor = contentColor)
    ) {
        Column(Modifier.padding(DashDesign.inset), content = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpressiveIcon(icon, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            content()
        })
    }
}

@Composable
private fun RouterDetailRow(label: String, value: String) {
    if (value.isBlank()) return
    val color = LocalContentColor.current
    InfoRow(label, value, labelColor = color.copy(alpha = .78f), valueColor = color)
}

private fun milliseconds(value: Float?): String = value?.let { "${format(it)} ms" } ?: ""
private fun format(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)
private fun duration(seconds: Long): String {
    if (seconds <= 0) return ""
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    return "${if (days > 0) "${days}d " else ""}${hours}h ${minutes}min"
}
private fun bytes(value: Long): String = when {
    value >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f GB", value / 1_000_000_000.0)
    value >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.getDefault(), "%.1f KB", value / 1_000.0)
    else -> "$value B"
}
