package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.core.network.grpc.RouterClientInfo
import io.github.strongsand.lshell.core.network.grpc.RouterGrpcClient
import io.github.strongsand.lshell.core.network.grpc.RouterInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun RouterScreen(modifier: Modifier = Modifier) {
    val client = remember { RouterGrpcClient() }
    var router by remember { mutableStateOf<RouterInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var confirmReboot by remember { mutableStateOf(false) }
    var actionBusy by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshKey) {
        while (isActive) {
            refreshing = true
            client.fetch().fold(
                onSuccess = { fresh ->
                    router = fresh.copy(clients = retainRouterClients(router?.clients.orEmpty(), fresh.clients))
                    error = null
                },
                onFailure = { error = it.message ?: "Falha ao consultar o roteador" }
            )
            refreshing = false
            delay(15_000)
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Router, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Roteador", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = hapticClick { refreshKey++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar roteador")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Atualização automática a cada 15 s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        if (error != null) {
            RouterCard("Conexão", MaterialTheme.colorScheme.errorContainer) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.height(6.dp))
                Text("Conecte o celular ao Wi-Fi da Starlink e toque em atualizar. O roteador deve responder em 192.168.1.1:9000.")
            }
            Spacer(Modifier.height(12.dp))
        }
        val info = router
        if (info == null) {
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }

        RouterCard("Comandos do roteador", MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = hapticClick { confirmReboot = true }, enabled = !actionBusy,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Reiniciar")
                }
                OutlinedButton(onClick = hapticClick {
                    actionBusy = true
                    scope.launch {
                        actionMessage = client.fetch().fold(
                            onSuccess = {
                                router = it
                                if (it.alerts.any { alert -> alert == "Atualização pendente" })
                                    "O roteador informa uma atualização pendente."
                                else "Nenhuma atualização pendente informada pelo roteador."
                            },
                            onFailure = { "Falha na consulta: ${it.message ?: "sem resposta"}" })
                        actionBusy = false
                    }
                }, enabled = !actionBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("Verificar atualização")
                }
            }
            actionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.height(12.dp))

        RouterCard("Estado do roteador", MaterialTheme.colorScheme.primaryContainer) {
            Text("${info.clients.size} dispositivos encontrados", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(8.dp))
            RouterDetailRow("Ligado há", duration(info.uptimeSeconds))
            RouterDetailRow("WAN IPv4", info.wanIpv4)
            RouterDetailRow("WAN IPv6", info.wanIpv6.joinToString("\n"))
        }
        Spacer(Modifier.height(12.dp))

        RouterCard("Latência", MaterialTheme.colorScheme.surfaceContainerHigh) {
            RouterDetailRow("Internet", milliseconds(info.pingMs))
            RouterDetailRow("Antena", milliseconds(info.dishPingMs))
            RouterDetailRow("POP", milliseconds(info.popPingMs))
        }
        Spacer(Modifier.height(12.dp))

        RouterCard("Hardware e software", MaterialTheme.colorScheme.surfaceContainerHigh) {
            RouterDetailRow("ID", info.id)
            RouterDetailRow("Hardware", info.hardware)
            RouterDetailRow("Software", info.software)
            RouterDetailRow("Versão de fábrica", info.manufacturedSoftware)
        }
        Spacer(Modifier.height(12.dp))

        if (info.networks.isNotEmpty()) {
            RouterCard("Redes locais", MaterialTheme.colorScheme.surfaceContainerHigh) {
                info.networks.forEachIndexed { index, network ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text(network.domain.ifBlank { "Rede ${index + 1}" }, style = MaterialTheme.typography.titleMedium)
                    RouterDetailRow("IPv4", network.ipv4)
                    RouterDetailRow("IPv6", network.ipv6.joinToString("\n"))
                    RouterDetailRow("Ethernet", network.ethernetClients.toString())
                    RouterDetailRow("Wi-Fi 2,4 GHz", network.wifi24Clients.toString())
                    RouterDetailRow("Wi-Fi 5 GHz", network.wifi5Clients.toString())
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        RouterCard("Inicializações e alertas", MaterialTheme.colorScheme.surfaceContainerHigh) {
            RouterDetailRow("Último motivo", info.bootReason)
            RouterDetailRow("Total", info.bootCount.takeIf { it > 0 }?.toString().orEmpty())
            info.bootCounts.forEach { (reason, count) -> RouterDetailRow(reason, count.toString()) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (info.alerts.isEmpty()) Text("Nenhum alerta informado")
            else info.alerts.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("Dispositivos", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(10.dp))
        if (info.clients.isEmpty()) {
            RouterCard("Nenhum dispositivo retornado", MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text("O roteador respondeu, mas não enviou a lista de clientes nesta consulta.")
            }
        } else {
            info.clients.forEach { connected ->
                RouterClientCard(connected)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
    if (confirmReboot) {
        AlertDialog(onDismissRequest = { confirmReboot = false },
            title = { Text("Reiniciar roteador?") },
            text = { Text("O Wi-Fi e a conexão com a antena ficarão indisponíveis por alguns minutos.") },
            confirmButton = { TextButton(onClick = hapticClick(DashFeedback.CONFIRM) {
                confirmReboot = false
                actionBusy = true
                scope.launch {
                    actionMessage = client.reboot().fold(
                        onSuccess = { "Comando enviado ao roteador." },
                        onFailure = { "Falha ao enviar: ${it.message ?: "sem resposta"}" })
                    actionBusy = false
                }
            }) { Text("Reiniciar") } },
            dismissButton = { TextButton(onClick = hapticClick { confirmReboot = false }) { Text("Cancelar") } })
    }
}

private fun retainRouterClients(previous: List<RouterClientInfo>, current: List<RouterClientInfo>): List<RouterClientInfo> {
    fun RouterClientInfo.key() = mac.ifBlank { ipv4.ifBlank { "$name|$interfaceName" } }
    val previousByKey = previous.associateBy { it.key() }
    val currentKeys = current.mapTo(mutableSetOf()) { it.key() }
    val refreshed = current.map { client ->
        val old = previousByKey[client.key()]
        client.copy(
            receivedBytes = maxOf(client.receivedBytes, old?.receivedBytes ?: 0L),
            sentBytes = maxOf(client.sentBytes, old?.sentBytes ?: 0L)
        )
    }
    val temporarilyMissing = previous.filter { it.key() !in currentKeys }.map { it.copy(active = false) }
    return (refreshed + temporarilyMissing)
        .sortedWith(compareByDescending<RouterClientInfo> { it.active }.thenBy { it.name })
}

@Composable
private fun RouterClientCard(client: RouterClientInfo) {
    RouterCard(client.name, MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text("${client.role} · ${client.interfaceName}${if (client.active) " · Ativo" else ""}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(10.dp))
        RouterDetailRow("IPv4", client.ipv4)
        if (client.ipv6.isNotEmpty()) RouterDetailRow("IPv6", client.ipv6.joinToString("\n"))
        RouterDetailRow("MAC", client.mac)
        if (client.signalDbm != null) RouterDetailRow("Sinal", "${format(client.signalDbm)} dBm")
        if (client.snrDb != null) RouterDetailRow("Sinal/ruído", "${format(client.snrDb)} dB")
        if (client.connectedSeconds > 0) RouterDetailRow("Conectado há", duration(client.connectedSeconds))
        RouterDetailRow("Recebido", bytes(client.receivedBytes))
        RouterDetailRow("Enviado", bytes(client.sentBytes))
    }
}

@Composable
private fun RouterCard(title: String, color: androidx.compose.ui.graphics.Color, content: @Composable ColumnScope.() -> Unit) {
    val contentColor = contentColorFor(color)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = DashDesign.section,
        colors = CardDefaults.cardColors(containerColor = color, contentColor = contentColor)
    ) {
        Column(Modifier.padding(DashDesign.inset), content = {
            SectionHeading(title)
            Spacer(Modifier.height(12.dp))
            content()
        })
    }
}

@Composable
private fun RouterDetailRow(label: String, value: String) {
    if (value.isBlank()) return
    val color = LocalContentColor.current
    InfoRow(label, value, labelColor = color, valueColor = color)
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
